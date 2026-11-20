package com.openegg.app.vxfly

import com.openegg.app.link.ByteTransport
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory stand-in for a VxFly unit's config service, for unit tests and demo mode.
 * Use `transportFactory = station::transport` on [VxStationClient]; each call is a
 * fresh "TCP connection" sharing the station's state.
 *
 * Reply payloads for login and set commands are guesses (the real text is
 * unknown, docs/07 §2.6): login answers with an empty payload, setters with
 * "SUCCESS", keepalives are echoed (also unconfirmed, docs/07 §5 item 3).
 */
class SimulatedVxStation(
    /** Max bytes handed out per read(), to exercise split-read handling. */
    private val maxReadChunk: Int = Int.MAX_VALUE,
) {
    /** Answer nothing at all (radio gone / unit hung, TCP still up). */
    @Volatile var silent = false
    /** Refuse new connections (unit off / out of Wi-Fi range). */
    @Volatile var acceptConnections = true
    @Volatile var answerLogin = true
    @Volatile var echoKeepalive = true

    @Volatile var batteryReply = "3.912"
    @Volatile var radioStatusReply = "-55,25,-90"
    @Volatile var swVersion = "PV-FTA-S-V-SIM"
    @Volatile var downlinkChannel = 12
    @Volatile var videoRateMbps = 3
    @Volatile var videoResolution = 0

    /** Commands answered with "FAILED". */
    val failCommands: MutableSet<Int> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    /** Commands silently dropped (no reply), to exercise request timeouts. */
    val ignoreCommands: MutableSet<Int> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val _received = CopyOnWriteArrayList<VxFrame>()
    /** Every frame received from clients, in order, across connections. */
    val received: List<VxFrame> get() = _received.toList()

    private val _connections = AtomicInteger()
    val connections: Int get() = _connections.get()

    private val open = CopyOnWriteArrayList<Connection>()

    fun transport(): ByteTransport = Connection()

    /** Simulates the unit resetting the TCP connection. */
    fun dropConnections() {
        open.forEach { it.close() }
    }

    fun clearReceived() = _received.clear()

    private fun respond(f: VxFrame): VxFrame? {
        if (f.cmd in ignoreCommands) return null
        if (f.cmd in failCommands) return VxFrame.text(f.cmd, "FAILED")
        return when (f.cmd) {
            VxCmd.KEEPALIVE -> if (echoKeepalive) VxFrame(VxCmd.KEEPALIVE) else null
            VxCmd.LOGIN -> if (answerLogin) VxFrame(VxCmd.LOGIN) else null
            VxCmd.GET_BATTERY_VOLTAGE -> VxFrame.text(f.cmd, batteryReply)
            VxCmd.GET_RADIO_STATUS -> VxFrame.text(f.cmd, radioStatusReply)
            VxCmd.GET_SW_VERSION -> VxFrame.text(f.cmd, swVersion)
            VxCmd.GET_DL_CHANNEL -> VxFrame.int(f.cmd, downlinkChannel)
            VxCmd.GET_VIDEO_RATE -> VxFrame.int(f.cmd, videoRateMbps)
            VxCmd.GET_VIDEO_RESOLUTION -> VxFrame.int(f.cmd, videoResolution)
            VxCmd.SET_DL_CHANNEL -> f.text.toIntOrNull()
                ?.let { downlinkChannel = it; VxFrame.text(f.cmd, "SUCCESS") }
                ?: VxFrame.text(f.cmd, "FAILED")
            VxCmd.SET_VIDEO_RATE -> f.text.toIntOrNull()
                ?.let { videoRateMbps = it; VxFrame.text(f.cmd, "SUCCESS") }
                ?: VxFrame.text(f.cmd, "FAILED")
            else -> VxFrame.text(f.cmd, "SUCCESS")
        }
    }

    private inner class Connection : ByteTransport {
        override val description = "simulated VxFly station"
        private val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = VxFrameDecoder()
        private var pending = ByteArray(0)

        override suspend fun open() {
            if (!acceptConnections) throw IOException("connection refused (simulated)")
            _connections.incrementAndGet()
            open += this
        }

        override suspend fun read(buffer: ByteArray): Int {
            if (pending.isEmpty()) pending = toClient.receiveCatching().getOrNull() ?: return -1
            val n = minOf(buffer.size, pending.size, maxReadChunk)
            pending.copyInto(buffer, 0, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }

        override suspend fun write(bytes: ByteArray) {
            if (toClient.isClosedForSend) throw IOException("connection closed (simulated)")
            val frames = synchronized(decoder) { decoder.feed(bytes) }
            for (f in frames) {
                _received += f
                if (silent) continue
                respond(f)?.let { toClient.trySend(it.encode()) }
            }
        }

        override fun close() {
            toClient.close()
            open -= this
        }
    }
}
