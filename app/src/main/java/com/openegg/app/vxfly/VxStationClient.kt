package com.openegg.app.vxfly

import com.openegg.app.link.ByteTransport
import com.openegg.app.link.TcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Which VxFly box a client talks to; commands are routed per docs/07 §2.4. */
enum class VxUnit { GROUND, AIR }

/** Timing defaults come from libvfconfig (docs/07 §2.3); all overridable for tests or field tuning. */
data class VxTimings(
    /** Dead-man after connect, before the login reply (`setRecvTimeout(5000)` in didConnectCallback). */
    val initialRecvTimeoutMs: Long = 5000,
    /** Dead-man after a login (0x1C) or battery (0x10) reply (`setRecvTimeout(2000)`). */
    val loggedInRecvTimeoutMs: Long = 2000,
    /** Dead-man set just before sending GetDlRadioStatus (0x1F). */
    val radioStatusRecvTimeoutMs: Long = 5000,
    /** How long a request waits for the reply with the same cmd byte (native has none: fire-and-forget). */
    val requestTimeoutMs: Long = 2000,
    /** Flight-screen poll: first run at 1 s, then every 5 s (VFConfigController.UpdateTimeTask). */
    val pollInitialDelayMs: Long = 1000,
    val pollPeriodMs: Long = 5000,
    /** Pause between reconnect attempts. */
    val reconnectDelayMs: Long = 2000,
    /** Watchdog resolution (native polls with a 10 ms select). */
    val watchdogTickMs: Long = 50,
)

/**
 * Snapshot of one VxFly unit. Radio fields are cleared when the link drops;
 * battery and [lastSeenAt] are kept (stale) so the UI can show "last known".
 * UI must gate everything on [linked].
 */
data class StationStatus(
    val host: String,
    val unit: VxUnit,
    /** TCP session open. */
    val connected: Boolean = false,
    /** Station has sent at least one byte this session and the dead-man has not fired. */
    val linked: Boolean = false,
    /** The unit answered the 0x1C login. */
    val loggedIn: Boolean = false,
    /** Wall-clock ms of the last received byte. */
    val lastSeenAt: Long? = null,
    val groundBatteryVolts: Double? = null,
    /** 0.0..100.0 via the §2.5 table. */
    val groundBatteryPercent: Double? = null,
    val rssi: Int? = null,
    val snr: Int? = null,
    val noise: Int? = null,
    /** 0..5 from SNR; null = no reading or SNR < 0 (original shows the "no link" icon). */
    val signalBars: Int? = null,
    val swVersion: String? = null,
    val connectAttempts: Int = 0,
    /** Why the last session ended or failed to start. */
    val lastError: String? = null,
)

sealed interface VxReply {
    /** Reply with the same cmd that is not "FAILED". */
    data class Ok(val frame: VxFrame) : VxReply
    /** Reply text was "FAILED" (case-insensitive). */
    data class Failed(val frame: VxFrame) : VxReply
    /** No reply with that cmd within the request timeout. Outcome unknown. */
    data object Timeout : VxReply
    /** No session, or the session ended before a reply. Outcome unknown if it had been sent. */
    data object NotConnected : VxReply

    val isOk: Boolean get() = this is Ok
}

/** Result of SetDlChannel + ApplyDlConfig. [apply] is null when the set was not acknowledged OK. */
data class ConfigChangeResult(val set: VxReply, val apply: VxReply?) {
    val ok: Boolean get() = set is VxReply.Ok && apply is VxReply.Ok
}

/**
 * Client for the VxFly "VF config" TCP service (libvfconfig, docs/07 §2) on one unit:
 * ground radio [GROUND_HOST] or air unit [AIR_HOST], port [PORT].
 *
 * - Reconnects forever while started.
 * - Sends login `1C 00` on connect, keepalive `00 00` every dead-man/2
 *   (2.5 s, then 1 s once login/battery has been answered).
 * - Closes and reconnects when nothing at all is received for the dead-man
 *   (5 s, then 2 s; 5 s again after sending 0x1F). Any received byte counts as alive.
 * - On the ground unit polls battery (0x10) and radio status (0x1F) every 5 s,
 *   plus the SW version (0x1E) once per session, as the original flight screen did.
 *
 * Commands in [VxCmd.DANGEROUS] are only reachable through [setDownlinkChannel] and
 * [setVideoRateMbps], which change radio config and must be confirmed in the UI.
 */
class VxStationClient(
    val host: String,
    val port: Int = PORT,
    private val transportFactory: () -> ByteTransport = { TcpTransport(host, port) },
    private val scope: CoroutineScope,
    val unit: VxUnit = if (host == AIR_HOST) VxUnit.AIR else VxUnit.GROUND,
    private val timings: VxTimings = VxTimings(),
    private val pollEnabled: Boolean = unit == VxUnit.GROUND,
    /** Monotonic clock for the dead-man (tests pass virtual time). */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PORT = 1234
        const val GROUND_HOST = "192.168.1.2"
        const val AIR_HOST = "192.168.1.240"
        /** Channels offered by the original Link-settings UI. */
        val DL_CHANNEL_RANGE = 6..19
        val VIDEO_RATE_RANGE_MBPS = 1..4
    }

    private val _status = MutableStateFlow(StationStatus(host, unit))
    val status: StateFlow<StationStatus> = _status.asStateFlow()

    private var job: Job? = null
    @Volatile private var session: Session? = null
    private val cmdLocks = ConcurrentHashMap<Int, Mutex>()
    @Volatile private var lastAcceptedMv: Int? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        _status.update { it.dropped(it.lastError) }
    }

    /** Sends a read-only getter and waits for its reply. Rejects anything outside [VxCmd.READ_ONLY]. */
    suspend fun query(cmd: Int): VxReply {
        require(cmd in VxCmd.READ_ONLY) { "0x%02X is not a read-only command".format(cmd) }
        return exchange(cmd)
    }

    /** Current downlink channel (ground), or null if unavailable. */
    suspend fun readDownlinkChannel(): Int? {
        check(unit == VxUnit.GROUND) { "GetDlChannel goes to the ground unit" }
        return (query(VxCmd.GET_DL_CHANNEL) as? VxReply.Ok)?.frame?.text?.toIntOrNull()
    }

    /** Current video bitrate code in Mbps (air), or null if unavailable. */
    suspend fun readVideoRateMbps(): Int? {
        check(unit == VxUnit.AIR) { "GetVideoRate goes to the air unit" }
        return (query(VxCmd.GET_VIDEO_RATE) as? VxReply.Ok)?.frame?.text?.toIntOrNull()
    }

    /**
     * CHANGES RADIO CONFIG; UI MUST CONFIRM. A wrong channel can lose the downlink.
     *
     * Sends SetDlChannel `14 nn "<ch>"` to the ground unit and, only if that is
     * acknowledged OK, ApplyDlConfig `1B 00`. (The original sent both back-to-back
     * without waiting; we refuse to apply an unacknowledged change.) Applying may
     * restart the radio and drop this TCP session, so `apply` = Timeout/NotConnected
     * means "sent, outcome unconfirmed": re-read the channel after reconnect.
     */
    suspend fun setDownlinkChannel(channel: Int): ConfigChangeResult {
        require(channel in DL_CHANNEL_RANGE) { "channel $channel outside $DL_CHANNEL_RANGE" }
        check(unit == VxUnit.GROUND) { "SetDlChannel goes to the ground unit" }
        val set = exchange(VxCmd.SET_DL_CHANNEL, channel.toString().toByteArray(Charsets.US_ASCII))
        if (set !is VxReply.Ok) return ConfigChangeResult(set, null)
        return ConfigChangeResult(set, exchange(VxCmd.APPLY_DL_CONFIG))
    }

    /**
     * CHANGES RADIO CONFIG; UI MUST CONFIRM. Sends SetVideoRate `1A 01 "<mbps>"`
     * to the air unit (1..4 Mbps). Timeout/NotConnected = outcome unconfirmed.
     */
    suspend fun setVideoRateMbps(mbps: Int): VxReply {
        require(mbps in VIDEO_RATE_RANGE_MBPS) { "rate $mbps outside $VIDEO_RATE_RANGE_MBPS Mbps" }
        check(unit == VxUnit.AIR) { "SetVideoRate goes to the air unit" }
        return exchange(VxCmd.SET_VIDEO_RATE, mbps.toString().toByteArray(Charsets.US_ASCII))
    }

    // ---- session machinery ----------------------------------------------------------

    private class SessionClosedException : Exception("session closed")

    private inner class Session(val transport: ByteTransport) {
        val writeLock = Mutex()
        val pending = ConcurrentHashMap<Int, CompletableDeferred<VxFrame>>()
        val end = CompletableDeferred<String>()
        @Volatile var recvTimeoutMs = timings.initialRecvTimeoutMs
        @Volatile var lastRxAt = monotonicMs()

        suspend fun send(frame: VxFrame): Boolean {
            if (end.isCompleted) return false
            if (frame.cmd == VxCmd.GET_RADIO_STATUS) recvTimeoutMs = timings.radioStatusRecvTimeoutMs
            return try {
                writeLock.withLock { transport.write(frame.encode()) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                end.complete("write failed: ${e.message ?: e::class.simpleName}")
                false
            }
        }
    }

    private suspend fun runLoop() {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            attempts++
            _status.update { it.copy(connectAttempts = attempts) }
            val reason = try {
                runSession(transportFactory())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "connect failed: ${e.message ?: e::class.simpleName}"
            }
            _status.update { it.dropped(reason) }
            delay(timings.reconnectDelayMs)
        }
    }

    /** Runs one TCP session; returns why it ended. */
    private suspend fun runSession(t: ByteTransport): String = coroutineScope {
        val sessionScope = this
        val s = Session(t)
        try {
            t.open()
            s.lastRxAt = monotonicMs()
            session = s
            _status.update { it.copy(connected = true, linked = false, loggedIn = false, lastError = null) }
            s.send(VxFrame(VxCmd.LOGIN))
            launch { readLoop(s) }
            launch { keepaliveLoop(s) }
            launch { watchdog(s) }
            if (pollEnabled) launch { pollLoop(s, sessionScope) }
            s.end.await()
        } finally {
            if (session === s) session = null
            s.end.complete("closed")
            s.pending.values.forEach { it.completeExceptionally(SessionClosedException()) }
            t.close() // unblocks a blocking socket read before we wait for the children
            sessionScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun readLoop(s: Session) {
        val decoder = VxFrameDecoder()
        val buf = ByteArray(1024)
        try {
            while (!s.end.isCompleted) {
                val n = s.transport.read(buf)
                if (n < 0) {
                    s.end.complete("closed by station")
                    return
                }
                if (n == 0) continue
                s.lastRxAt = monotonicMs()
                _status.update { it.copy(linked = true, lastSeenAt = wallClockMs()) }
                for (f in decoder.feed(buf, 0, n)) handleFrame(s, f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            s.end.complete("read failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun keepaliveLoop(s: Session) {
        val keepalive = VxFrame(VxCmd.KEEPALIVE)
        while (!s.end.isCompleted) {
            delay(s.recvTimeoutMs / 2)
            if (!s.send(keepalive)) return
        }
    }

    private suspend fun watchdog(s: Session) {
        while (!s.end.isCompleted) {
            val timeout = s.recvTimeoutMs
            val idle = monotonicMs() - s.lastRxAt
            if (idle > timeout) {
                s.end.complete("no data for ${idle} ms (limit $timeout ms)")
                return
            }
            delay(minOf(timings.watchdogTickMs, timeout - idle + 1).coerceAtLeast(1))
        }
    }

    private suspend fun pollLoop(s: Session, sessionScope: CoroutineScope) {
        delay(timings.pollInitialDelayMs)
        var askedVersion = false
        while (!s.end.isCompleted) {
            // Fire both like the original; replies update status in handleFrame either way.
            sessionScope.launch { exchange(VxCmd.GET_BATTERY_VOLTAGE) }
            sessionScope.launch { exchange(VxCmd.GET_RADIO_STATUS) }
            if (!askedVersion) {
                askedVersion = true
                sessionScope.launch { exchange(VxCmd.GET_SW_VERSION) }
            }
            delay(timings.pollPeriodMs)
        }
    }

    private fun handleFrame(s: Session, f: VxFrame) {
        when (f.cmd) {
            VxCmd.KEEPALIVE -> return // echoes/keepalives hit no case natively; RX already counted as alive
            VxCmd.LOGIN -> {
                s.recvTimeoutMs = timings.loggedInRecvTimeoutMs
                _status.update { it.copy(loggedIn = true) }
            }
            VxCmd.GET_BATTERY_VOLTAGE -> {
                s.recvTimeoutMs = timings.loggedInRecvTimeoutMs // native falls through into the 0x1C case
                if (!f.isFailed) parseVolts(f.text)?.let(::onBattery)
            }
            VxCmd.GET_RADIO_STATUS -> if (!f.isFailed) {
                VxSignal.parseRadioStatus(f.text)?.let { (rssi, snr, noise) ->
                    _status.update {
                        it.copy(rssi = rssi, snr = snr, noise = noise, signalBars = VxSignal.barsFromSnr(snr))
                    }
                }
            }
            VxCmd.GET_SW_VERSION -> if (!f.isFailed && f.text.isNotEmpty()) {
                _status.update { it.copy(swVersion = f.text) }
            }
        }
        s.pending.remove(f.cmd)?.complete(f)
    }

    private fun onBattery(volts: Double) {
        val mV = VxBattery.applyHysteresis(lastAcceptedMv, VxBattery.millivolts(volts))
        if (mV == lastAcceptedMv) {
            _status.update { it.copy(groundBatteryVolts = volts) }
            return
        }
        lastAcceptedMv = mV
        _status.update {
            it.copy(groundBatteryVolts = volts, groundBatteryPercent = VxBattery.percentFromMillivolts(mV))
        }
    }

    private val leadingNumber = Regex("^[-+]?\\d+(\\.\\d+)?")

    /** Like `scan("%lf")`: takes the leading number, ignores any trailing unit text. */
    private fun parseVolts(text: String): Double? = leadingNumber.find(text)?.value?.toDoubleOrNull()

    /** Send [cmd] and wait for the next frame with the same cmd. One request per cmd in flight. */
    private suspend fun exchange(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = timings.requestTimeoutMs,
    ): VxReply = cmdLocks.computeIfAbsent(cmd) { Mutex() }.withLock {
        val s = session ?: return@withLock VxReply.NotConnected
        val reply = CompletableDeferred<VxFrame>()
        s.pending[cmd] = reply
        try {
            if (!s.send(VxFrame(cmd, payload))) return@withLock VxReply.NotConnected
            val f = withTimeoutOrNull(timeoutMs) { reply.await() }
            when {
                f == null -> VxReply.Timeout
                f.isFailed -> VxReply.Failed(f)
                else -> VxReply.Ok(f)
            }
        } catch (e: SessionClosedException) {
            VxReply.NotConnected
        } finally {
            s.pending.remove(cmd, reply)
        }
    }

    private fun StationStatus.dropped(reason: String?) = copy(
        connected = false, linked = false, loggedIn = false,
        rssi = null, snr = null, noise = null, signalBars = null,
        lastError = reason,
    )
}
