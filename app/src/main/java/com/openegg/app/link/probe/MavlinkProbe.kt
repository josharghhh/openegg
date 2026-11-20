package com.openegg.app.link.probe

import com.openegg.app.drone.Px4Mode
import com.openegg.app.mavlink.MavDialect
import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkFrameDetector
import com.openegg.app.mavlink.PowerVisionDialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException

/**
 * Bytes the MAVLink probe is allowed to send. Only two messages, both harmless:
 * the GCS HEARTBEAT exactly as the original SDK sends it (doc 04 §1.4) and a
 * PARAM_REQUEST_READ by name (doc 04 §4.3). Nothing that sets, arms, commands or
 * changes mode is built here.
 */
object MavlinkProbeBytes {
    const val GCS_SYSID = 255
    const val GCS_COMPID = 190
    const val PARAM_NAME = "PV_V_KEY_FLAG"

    /** HEARTBEAT (0) and PARAM_REQUEST_READ (20): the only ids [MavlinkProbe] will ever transmit. */
    val TRANSMIT_ALLOWLIST = setOf(0, 20)

    fun newCodec() = MavlinkCodec(PowerVisionDialect.dialect, GCS_SYSID, GCS_COMPID, version = 1)

    fun gcsHeartbeat(codec: MavlinkCodec): ByteArray = codec.encode(
        "HEARTBEAT",
        mapOf(
            "type" to 6, // MAV_TYPE_GCS
            "autopilot" to 0, // MAV_AUTOPILOT_GENERIC
            "base_mode" to 0x81, // what the original SDK sends
            "custom_mode" to 0L,
            "system_status" to 0,
            "mavlink_version" to 3,
        ),
    )

    fun paramRequestRead(codec: MavlinkCodec, targetSystem: Int, targetComponent: Int, paramId: String = PARAM_NAME): ByteArray {
        require(paramId.length <= 16) { "param id too long" }
        return codec.encode(
            "PARAM_REQUEST_READ",
            mapOf("target_system" to targetSystem, "target_component" to targetComponent, "param_id" to paramId, "param_index" to -1),
        )
    }

    /** Message id of an encoded v1 frame. */
    fun messageIdOf(frame: ByteArray): Int = frame[5].toInt() and 0xFF
}

/** Accumulates what arrived on a MAVLink socket and renders it for the report. Pure; unit tested. */
class MavlinkCapture(private val dialect: MavDialect = PowerVisionDialect.dialect) {

    data class HeartbeatSource(
        val systemId: Int,
        val componentId: Int,
        val type: Int,
        val autopilot: Int,
        val baseMode: Int,
        val customMode: Long,
        val systemStatus: Int,
        val mavlinkVersion: Int,
        val count: Int,
        val firstAtMs: Long,
    ) {
        val role: String
            get() = when {
                systemId == MavlinkProbeBytes.GCS_SYSID -> "our own id echoed back?"
                componentId == 6 -> "standard RC (compid 6)"
                componentId == 7 -> "motion-sensing RC (compid 7)"
                autopilot == 12 -> "PX4 flight controller"
                autopilot == 3 -> "ArduPilot"
                type == 6 -> "another GCS"
                else -> "unknown"
            }

        fun describe(): String {
            val mode = Px4Mode.fromCustomMode(customMode)
            return "sys=$systemId comp=$componentId type=$type autopilot=$autopilot base_mode=0x%02x%s custom_mode=0x%08x (main %d sub %d = %s) status=%d v=%d · %d beats, first %s · %s".format(
                baseMode, if (baseMode and 0x80 != 0) " ARMED" else "", customMode, mode.main, mode.sub, mode.label,
                systemStatus, mavlinkVersion, count, fmtT(firstAtMs), role,
            )
        }
    }

    private val codec = MavlinkCodec(dialect)
    val counts = LinkedHashMap<Int, Int>()
    val unverified = LinkedHashMap<Int, Int>()
    private val samples = LinkedHashMap<Int, String>()
    val heartbeats = LinkedHashMap<Pair<Int, Int>, HeartbeatSource>()
    val statusTexts = LinkedHashSet<String>()
    val otherParams = LinkedHashMap<String, String>()
    var paramReply: MavMessage? = null
        private set
    var paramReplyAtMs: Long = 0
        private set
    var validBytes = 0L
        private set
    var firstFrameAtMs: Long? = null
        private set
    val versions = sortedSetOf<Int>()

    val verifiedFrames get() = counts.values.sum()

    /** The aircraft: a PX4 heartbeat first (as the original SDK), else any non-GCS, non-RC source. */
    val vehicle: HeartbeatSource?
        get() = heartbeats.values.firstOrNull { it.autopilot == 12 && it.systemId != MavlinkProbeBytes.GCS_SYSID }
            ?: heartbeats.values.firstOrNull {
                it.systemId != MavlinkProbeBytes.GCS_SYSID && it.type != 6 && it.componentId != 6 && it.componentId != 7
            }

    fun add(frame: MavlinkFrameDetector.Frame, atMs: Long) {
        if (frame.crcValid != true) {
            unverified[frame.messageId] = (unverified[frame.messageId] ?: 0) + 1
            return
        }
        if (firstFrameAtMs == null) firstFrameAtMs = atMs
        versions += frame.version
        validBytes += (if (frame.version == 2) 12 else 8) + frame.payload.size + if (frame.signed) 13 else 0
        counts[frame.messageId] = (counts[frame.messageId] ?: 0) + 1
        val msg = runCatching { codec.decode(frame) }.getOrNull() ?: return
        if (frame.messageId !in samples) samples[frame.messageId] = "sys=${frame.systemId} comp=${frame.componentId} " + formatValues(msg)
        when (msg.name) {
            "HEARTBEAT" -> {
                val key = frame.systemId to frame.componentId
                val prev = heartbeats[key]
                heartbeats[key] = HeartbeatSource(
                    frame.systemId, frame.componentId, msg.int("type"), msg.int("autopilot"), msg.int("base_mode"),
                    msg.long("custom_mode"), msg.int("system_status"), msg.int("mavlink_version"),
                    (prev?.count ?: 0) + 1, prev?.firstAtMs ?: atMs,
                )
            }
            "STATUSTEXT" -> if (statusTexts.size < 30) statusTexts += "[sev ${msg.int("severity")}] ${msg.string("text")}"
            "PARAM_VALUE" -> {
                val id = msg.string("param_id")
                if (id == MavlinkProbeBytes.PARAM_NAME) {
                    if (paramReply == null) { paramReply = msg; paramReplyAtMs = atMs }
                } else if (otherParams.size < 20) {
                    otherParams[id] = describeParam(msg)
                }
            }
        }
    }

    fun summary(): String {
        if (verifiedFrames == 0) return "no valid MAVLink"
        val v = vehicle
        val who = if (v != null) "vehicle sys${v.systemId}/comp${v.componentId} autopilot ${v.autopilot} type ${v.type}, mode ${Px4Mode.fromCustomMode(v.customMode).label}"
        else "no vehicle heartbeat"
        val param = paramReply?.let { "${MavlinkProbeBytes.PARAM_NAME}=${paramBitsHex(it)}" } ?: "no param reply"
        return "MAVLink v${versions.joinToString("/")} · $who · ${counts.size} msg types / $verifiedFrames frames · $param"
    }

    fun lines(): List<String> = buildList {
        if (heartbeats.isEmpty()) add("Heartbeats: none")
        else {
            add("Heartbeat sources:")
            heartbeats.values.forEach { add("  " + it.describe()) }
        }
        val v = vehicle
        add("Vehicle (target for our requests): " + (v?.let { "sys=${it.systemId} comp=${it.componentId}" } ?: "none seen, used 1/1"))
        add(
            "Param ${MavlinkProbeBytes.PARAM_NAME}: " + (paramReply?.let { "${describeParam(it)} at ${fmtT(paramReplyAtMs)}" + activationNote(it) }
                ?: "no PARAM_VALUE reply"),
        )
        if (otherParams.isNotEmpty()) {
            add("Other PARAM_VALUE seen:")
            otherParams.forEach { (k, d) -> add("  $k: $d") }
        }
        add("Messages (verified CRC), id name x count:")
        counts.entries.sortedBy { it.key }.forEach { (id, n) -> add("  %3d %-28s x%d".format(id, dialect[id]?.name ?: "?", n)) }
        if (unverified.isNotEmpty()) {
            add("Unverified frames (unknown id, may be noise or PowerVision-only): " +
                unverified.entries.take(20).joinToString { "${it.key}x${it.value}" })
        }
        if (statusTexts.isNotEmpty()) {
            add("STATUSTEXT:")
            statusTexts.forEach { add("  $it") }
        }
        if (samples.isNotEmpty()) {
            add("First instance of each message:")
            samples.entries.sortedBy { it.key }.forEach { (id, s) ->
                val line = "  ${dialect[id]?.name ?: id}: $s"
                add(if (line.length > 240) line.take(240) + "…" else line)
            }
        }
    }

    companion object {
        fun formatValues(m: MavMessage): String = m.values.entries.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }

        private fun formatValue(v: Any): String = when (v) {
            is IntArray -> v.contentToString()
            is LongArray -> v.contentToString()
            is FloatArray -> v.contentToString()
            is DoubleArray -> v.contentToString()
            is String -> "\"$v\""
            else -> v.toString()
        }

        fun paramBitsHex(m: MavMessage): String = "0x%08x".format(m.float("param_value").toRawBits())

        fun describeParam(m: MavMessage): String {
            val f = m.float("param_value")
            return "value=$f bits=${paramBitsHex(m)} (int ${f.toRawBits()}) type=${m.int("param_type")} index=${m.int("param_index")}/${m.int("param_count")}"
        }

        private fun activationNote(m: MavMessage): String =
            if (m.float("param_value").toRawBits() == 0) " → 0 = NOT activated (the original app would prompt for activation)"
            else " → non-zero = activated"
    }
}

/**
 * Active MAVLink probe: connect, wait for the vehicle heartbeat, then send the GCS
 * heartbeat at 1 Hz and PARAM_REQUEST_READ(PV_V_KEY_FLAG) (up to 3 tries, 1 s apart,
 * like the SDK's retry timer), listening for [listenMs] after connect.
 */
class MavlinkProbe(
    private val listenMs: Long = 5500,
    private val connectTimeoutMs: Int = 2000,
    /** If no vehicle heartbeat by then, send ours anyway (some links only talk once a GCS is heard). */
    private val fallbackMs: Long = 2500,
) {
    suspend fun run(env: ProbeEnvironment, label: String, host: String, port: Int): ProbeSection = withContext(Dispatchers.IO) {
        val target = "$host:$port"
        val t0 = System.nanoTime()
        val socket = try {
            env.socketFactory.connectTcp(host, port, connectTimeoutMs)
        } catch (e: Exception) {
            val (v, why) = connectFailure(e)
            return@withContext ProbeSection(GROUP, label, target, v, why, elapsedMs = elapsedSince(t0))
        }
        val connectMs = elapsedSince(t0)
        val sent = mutableListOf<String>()
        val notes = mutableListOf("TCP connected in $connectMs ms")
        val capture = MavlinkCapture()
        val detector = PowerVisionDialect.detector()
        val raw = ByteArrayOutputStream()
        var total = 0L
        var heartbeatsSent = 0
        socket.use { s ->
            s.soTimeout = 100
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val codec = MavlinkProbeBytes.newCodec()
            val start = System.nanoTime()
            fun now() = elapsedSince(start)
            fun send(bytes: ByteArray) {
                check(MavlinkProbeBytes.messageIdOf(bytes) in MavlinkProbeBytes.TRANSMIT_ALLOWLIST)
                out.write(bytes)
                out.flush()
            }
            var lastHb = Long.MIN_VALUE / 2
            var lastParam = Long.MIN_VALUE / 2
            var paramTries = 0
            val buf = ByteArray(8192)
            try {
                while (now() < listenMs) {
                    ensureActive()
                    val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                    if (n < 0) { notes += "remote closed the connection at ${fmtT(now())}"; break }
                    if (n > 0) {
                        total += n
                        if (raw.size() < RAW_KEEP) raw.write(buf, 0, minOf(n, RAW_KEEP - raw.size()))
                        for (f in detector.feed(buf, n)) capture.add(f, now())
                    }
                    val t = now()
                    val vehicle = capture.vehicle
                    if (vehicle == null && t < fallbackMs) continue
                    if (t - lastHb >= 1000) {
                        val hb = MavlinkProbeBytes.gcsHeartbeat(codec)
                        send(hb)
                        if (heartbeatsSent == 0) {
                            sent += "${fmtT(t)} HEARTBEAT type=6 autopilot=0 base_mode=0x81 sys=255 comp=190" +
                                (if (vehicle == null) " (no vehicle heartbeat yet, sent anyway)" else " (after vehicle heartbeat)") +
                                ": ${HexDump.hex(hb)}"
                        }
                        heartbeatsSent++
                        lastHb = t
                    }
                    if (capture.paramReply == null && paramTries < 3 && t - lastParam >= 1000) {
                        val ts = vehicle?.systemId ?: 1
                        val tc = vehicle?.componentId ?: 1
                        val req = MavlinkProbeBytes.paramRequestRead(codec, ts, tc)
                        send(req)
                        paramTries++
                        lastParam = t
                        sent += "${fmtT(t)} PARAM_REQUEST_READ \"${MavlinkProbeBytes.PARAM_NAME}\" target $ts/$tc (try $paramTries): ${HexDump.hex(req)}"
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notes += "socket error at ${fmtT(now())}: ${describeError(e)}"
            }
        }
        if (heartbeatsSent > 1) sent += "HEARTBEAT sent $heartbeatsSent times in total (1 Hz)"
        if (sent.isEmpty()) sent += "nothing (no vehicle heartbeat and the link closed before the ${fallbackMs} ms fallback)"

        val details = buildList {
            addAll(notes)
            add("Received $total bytes in ${listenMs / 1000.0} s; ${capture.validBytes} bytes in CRC-valid frames" +
                (capture.firstFrameAtMs?.let { ", first frame at ${fmtT(it)}" } ?: ""))
            addAll(capture.lines())
            if (raw.size() > 0) {
                add("First ${raw.size()} bytes received:")
                addAll(HexDump.dump(raw.toByteArray(), maxBytes = 256))
            }
        }
        val verdict = when {
            capture.verifiedFrames > 0 -> Verdict.FOUND
            else -> Verdict.OPEN
        }
        val summary = when {
            capture.verifiedFrames > 0 -> capture.summary()
            total > 0 -> "connected, $total bytes but no valid MAVLink: ${HexDump.hex(raw.toByteArray(), 16)}"
            else -> "connected, silent for ${listenMs / 1000.0} s"
        }
        ProbeSection(GROUP, label, target, verdict, summary, sent, details, elapsedSince(t0))
    }

    companion object {
        const val GROUP = "MAVLink"
        private const val RAW_KEEP = 1024
    }
}
