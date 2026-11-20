package com.openegg.app.mavlink

import com.openegg.app.link.ByteTransport
import com.openegg.app.mavlink.gen.MavAutopilot
import com.openegg.app.mavlink.gen.MavType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one MAVLink v1 connection to the PowerEgg: reconnects, sends the GCS
 * heartbeat, decodes inbound frames and tracks the vehicle.
 *
 * Behaviour copied from the original PowerVision SDK (docs/04, docs/03):
 *  - TCP client, reconnect attempt every 3.1 s;
 *  - our heartbeat (type GCS, base_mode 0x81) starts only after the vehicle's first heartbeat;
 *  - the vehicle is "stale" after 3 s without a heartbeat, and the socket is recycled after 10 s.
 */
class MavlinkLink(
    private val transportFactory: () -> ByteTransport,
    val codec: MavlinkCodec,
    private val alternateCrcExtras: Map<Int, Set<Int>> = PowerVisionDialect.alternateCrcExtras,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 3100,
    val staleAfterMs: Long = 3000,
    private val reconnectAfterMs: Long = 10_000,
    private val gcsHeartbeatPeriodMs: Long = 1000,
) {
    sealed interface State {
        data object Stopped : State
        data class Connecting(val target: String, val attempt: Int, val lastError: String?) : State
        /** Socket open; [vehicle] is null until the first vehicle heartbeat. */
        data class Connected(val target: String, val vehicle: Vehicle?) : State
    }

    data class Vehicle(
        val systemId: Int,
        val componentId: Int,
        val autopilot: Int,
        val type: Int,
        val baseMode: Int,
        val customMode: Long,
        val lastHeartbeatAt: Long,
    )

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<MavMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<MavMessage> = _messages.asSharedFlow()

    /** CRC-valid frames whose id is not in our dialect — what's left to reverse-engineer. */
    private val _unknownFrames = MutableSharedFlow<MavlinkFrameDetector.Frame>(extraBufferCapacity = 64)
    val unknownFrames: SharedFlow<MavlinkFrameDetector.Frame> = _unknownFrames.asSharedFlow()

    private val writeLock = Mutex()
    @Volatile private var transport: ByteTransport? = null
    private var job: Job? = null

    val vehicle: Vehicle? get() = (state.value as? State.Connected)?.vehicle

    fun isStale(now: Long = clock()): Boolean = vehicle?.let { now - it.lastHeartbeatAt > staleAfterMs } ?: true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        transport?.close()
        _state.value = State.Stopped
    }

    /** Sends a message; returns false if not connected. */
    suspend fun send(name: String, values: Map<String, Any>): Boolean {
        val t = transport ?: return false
        if (state.value !is State.Connected) return false
        return writeLock.withLock { runCatching { t.write(codec.encode(name, values)) }.isSuccess }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var lastError: String? = null
        while (currentCoroutineContext().isActive) {
            attempt++
            val t = transportFactory()
            _state.value = State.Connecting(t.description, attempt, lastError)
            try {
                t.open()
                transport = t
                _state.value = State.Connected(t.description, null)
                attempt = 0
                session(t)
                lastError = "link lost"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                transport = null
                t.close()
            }
            delay(reconnectDelayMs)
        }
    }

    /** Runs until the stream ends or no vehicle heartbeat arrives for [reconnectAfterMs]. */
    private suspend fun session(t: ByteTransport) = coroutineScope {
        val detector = MavlinkFrameDetector(codec.dialect.byId.mapValues { it.value.crcExtra }, alternateCrcExtras)
        val connectedAt = clock()

        val heartbeat = launch {
            while (isActive) {
                if (vehicle != null) {
                    send(
                        "HEARTBEAT",
                        mapOf(
                            "type" to MavType.GCS, "autopilot" to MavAutopilot.GENERIC, "base_mode" to 0x81,
                            "custom_mode" to 0, "system_status" to 0, "mavlink_version" to 3,
                        ),
                    )
                }
                delay(gcsHeartbeatPeriodMs)
            }
        }
        val watchdog = launch {
            while (isActive) {
                delay(500)
                val last = vehicle?.lastHeartbeatAt ?: connectedAt
                if (clock() - last > reconnectAfterMs) {
                    t.close() // unblocks the reader below
                    break
                }
            }
        }

        val buf = ByteArray(4096)
        try {
            while (isActive) {
                val n = try { t.read(buf) } catch (e: Exception) { -1 }
                if (n < 0) break
                for (frame in detector.feed(buf, n)) {
                    // Unknown ids have no CRC_EXTRA, so crcValid is null; surface them for logging.
                    if (frame.crcValid == null) { _unknownFrames.tryEmit(frame); continue }
                    if (frame.crcValid != true) continue
                    val msg = codec.decode(frame) ?: continue
                    if (msg.name == "HEARTBEAT" && msg.int("type") != MavType.GCS) onVehicleHeartbeat(msg, t)
                    _messages.tryEmit(msg)
                }
            }
        } finally {
            heartbeat.cancel()
            watchdog.cancel()
        }
    }

    private fun onVehicleHeartbeat(msg: MavMessage, t: ByteTransport) {
        val current = vehicle
        // The SDK binds to the first PX4 autopilot it hears; ignore other systems after that.
        if (current != null && current.systemId != msg.systemId) return
        if (current == null && msg.int("autopilot") != MavAutopilot.PX4) return
        val target = (state.value as? State.Connected)?.target ?: t.description
        _state.value = State.Connected(
            target,
            Vehicle(
                systemId = msg.systemId, componentId = msg.componentId,
                autopilot = msg.int("autopilot"), type = msg.int("type"),
                baseMode = msg.int("base_mode"), customMode = msg.long("custom_mode"),
                lastHeartbeatAt = clock(),
            ),
        )
    }
}
