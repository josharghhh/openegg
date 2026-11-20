package com.openegg.app.link.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException

/**
 * VxFly pDDL config protocol (libvfconfig, doc 07 §2): TCP 1234, frames `[cmd u8][len u8][ASCII payload]`.
 *
 * The probe only builds payload-less "get" frames, and [frame] refuses every command that
 * changes the radio link, the access point or pairing (doc 07 §5 item 5).
 */
object VxFlyProtocol {
    const val PORT = 1234

    const val KEEPALIVE = 0x00
    const val GET_MODEL = 0x0C
    const val GET_BATTERY = 0x10
    const val GET_VIDEO_RESOLUTION = 0x17
    const val GET_VIDEO_RATE = 0x19
    const val LOGIN = 0x1C
    const val GET_SW_VERSION = 0x1E
    const val GET_RADIO_STATUS = 0x1F

    val NAMES = mapOf(
        0x00 to "keepalive", 0x03 to "GetApSSID", 0x05 to "GetApPassword", 0x0C to "GetModel", 0x0D to "GetSN",
        0x0E to "GetDlHWVersion", 0x0F to "GetDlSWVersion", 0x10 to "GetBatteryVoltage", 0x12 to "GetDlRSSI",
        0x13 to "GetDlChannel", 0x15 to "GetDlPower", 0x17 to "GetVideoResolution", 0x19 to "GetVideoRate",
        0x1C to "DlLogin", 0x1D to "GetHWVersion", 0x1E to "GetSWVersion", 0x1F to "GetDlRadioStatus",
        0x20 to "GetCountryCode", 0x22 to "GetDlSQThresh",
    )

    /**
     * Never sent by the probe: Set AP SSID/password (04/06), CuReset/CuReboot/UnbindDl (07/09/0B),
     * BindDl (11), SetDlChannel (14), SetDlPower (16), SetVideoResolution (18), SetVideoRate (1A),
     * ApplyDlConfig (1B), SetCountryCode (21), SetDlSQThresh (23), ScanDlChannels (24), firmware (25/26).
     */
    val FORBIDDEN = setOf(0x04, 0x06, 0x07, 0x09, 0x0B, 0x11, 0x14, 0x16, 0x18, 0x1A, 0x1B, 0x21, 0x23, 0x24, 0x25, 0x26)

    fun name(cmd: Int) = NAMES[cmd] ?: "cmd 0x%02x".format(cmd)

    /** A payload-less request frame `[cmd][00]`. */
    fun frame(cmd: Int): ByteArray {
        require(cmd in 0..0xFF) { "bad cmd" }
        require(cmd !in FORBIDDEN) { "VxFly cmd 0x%02x changes link/AP config and must not be sent by the probe".format(cmd) }
        return byteArrayOf(cmd.toByte(), 0)
    }

    data class Frame(val cmd: Int, val payload: ByteArray) {
        val text get() = payload.toString(Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    /** Streaming `[cmd][len][payload]` decoder. */
    class Decoder {
        private var pending = ByteArray(0)
        val leftover: Int get() = pending.size

        fun feed(b: ByteArray, n: Int = b.size): List<Frame> {
            pending += b.copyOf(n)
            val out = mutableListOf<Frame>()
            while (pending.size >= 2) {
                val len = pending[1].toInt() and 0xFF
                if (pending.size < len + 2) break
                out += Frame(pending[0].toInt() and 0xFF, pending.copyOfRange(2, 2 + len))
                pending = pending.copyOfRange(2 + len, pending.size)
            }
            return out
        }
    }

    /** Human reading of a reply, using the formats seen in libvfconfig. */
    fun interpret(f: Frame): String {
        val t = f.text
        val base = "${name(f.cmd)} len=${f.payload.size} \"${HexDump.ascii(f.payload)}\""
        val extra = when (f.cmd) {
            GET_BATTERY -> t.trim().toDoubleOrNull()?.let { v -> " → %.3f V ≈ %.0f%%".format(v, batteryPercent((v * 1000).toInt())) }
            GET_RADIO_STATUS -> t.split(',').map { it.trim() }.takeIf { it.size == 3 }?.let { (r, s, n) -> " → rssi $r, snr $s, noise $n" }
            KEEPALIVE -> " (keepalive)"
            else -> null
        }
        val failed = if (t.equals("FAILED", ignoreCase = true)) " [FAILED]" else ""
        return base + (extra ?: "") + failed
    }

    private val curve = listOf(
        3400 to 0.20, 3425 to 1.08, 3450 to 2.15, 3475 to 3.54, 3500 to 5.40, 3525 to 8.02, 3550 to 11.55, 3575 to 15.77,
        3600 to 20.93, 3625 to 28.63, 3650 to 40.58, 3675 to 50.51, 3700 to 57.36, 3725 to 62.88, 3750 to 67.73, 3775 to 72.22,
        3800 to 76.59, 3825 to 80.76, 3850 to 84.31, 3875 to 87.19, 3900 to 89.78, 3925 to 92.50, 3950 to 95.26, 3975 to 97.54,
        4000 to 98.94, 4025 to 99.60, 4050 to 100.0,
    )

    /** Single Li-ion cell curve from libvfconfig (doc 07 §2.5), linearly interpolated. */
    fun batteryPercent(mv: Int): Double {
        if (mv < 3400) return 0.0
        if (mv >= 4050) return 100.0
        val i = curve.indexOfLast { it.first <= mv }
        val (x0, y0) = curve[i]
        val (x1, y1) = curve[i + 1]
        return y0 + (y1 - y0) * (mv - x0) / (x1 - x0)
    }

    data class Step(val atMs: Long, val cmd: Int)

    /** Doc 07 §5 item 2: login, then the gets ~300 ms apart; keepalive every second after that. */
    fun plan(extraGets: List<Int>, listenMs: Long): List<Step> {
        val first = listOf(LOGIN, KEEPALIVE, GET_BATTERY, GET_RADIO_STATUS) + extraGets
        val steps = first.mapIndexed { i, c -> Step(i * 300L, c) }.toMutableList()
        var t = steps.last().atMs + 1000
        while (t < listenMs - 200) { steps += Step(t, KEEPALIVE); t += 1000 }
        return steps
    }

    /** Extra read-only gets per host: SW version on the ground unit; bitrate/resolution on the air unit (doc 07 §5.4). */
    val GROUND_EXTRA = listOf(GET_SW_VERSION)
    val AIR_EXTRA = listOf(GET_VIDEO_RATE, GET_VIDEO_RESOLUTION)
}

class VxFlyProbe(
    private val listenMs: Long = 4500,
    private val connectTimeoutMs: Int = 2000,
) {
    suspend fun run(env: ProbeEnvironment, label: String, host: String, extraGets: List<Int>): ProbeSection = withContext(Dispatchers.IO) {
        val port = VxFlyProtocol.PORT
        val target = "$host:$port"
        val t0 = System.nanoTime()
        val socket = try {
            env.socketFactory.connectTcp(host, port, connectTimeoutMs)
        } catch (e: Exception) {
            val (v, why) = connectFailure(e)
            return@withContext ProbeSection(GROUP, label, target, v, why, elapsedMs = elapsedSince(t0))
        }
        val notes = mutableListOf("TCP connected in ${elapsedSince(t0)} ms")
        val sent = mutableListOf<String>()
        val rx = mutableListOf<String>()
        val frames = mutableListOf<VxFlyProtocol.Frame>()
        val all = ByteArrayOutputStream()
        val decoder = VxFlyProtocol.Decoder()
        val steps = VxFlyProtocol.plan(extraGets, listenMs)
        var keepalives = 0
        socket.use { s ->
            s.soTimeout = 50
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val start = System.nanoTime()
            fun now() = elapsedSince(start)
            var next = 0
            val buf = ByteArray(2048)
            try {
                while (now() < listenMs) {
                    ensureActive()
                    while (next < steps.size && steps[next].atMs <= now()) {
                        val cmd = steps[next].cmd
                        val bytes = VxFlyProtocol.frame(cmd)
                        out.write(bytes); out.flush()
                        if (cmd == VxFlyProtocol.KEEPALIVE && next > 1) keepalives++
                        else sent += "${fmtT(now())} ${HexDump.hex(bytes)}  ${VxFlyProtocol.name(cmd)}"
                        next++
                    }
                    val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                    if (n < 0) { notes += "remote closed the connection at ${fmtT(now())}"; break }
                    if (n == 0) continue
                    if (all.size() < 4096) all.write(buf, 0, minOf(n, 4096 - all.size()))
                    val chunk = buf.copyOf(n)
                    rx += "${fmtT(now())} RX $n B: ${HexDump.hex(chunk, 96)}${if (n > 96) " …" else ""}  |${HexDump.ascii(chunk, 96)}|"
                    decoder.feed(chunk).forEach { frames += it }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notes += "socket error: ${describeError(e)}"
            }
        }
        if (keepalives > 0) sent += "plus $keepalives × 00 00 keepalive at 1 s intervals"
        val total = all.size()
        val details = buildList {
            addAll(notes)
            add("Received $total bytes in ${rx.size} reads")
            if (rx.isNotEmpty()) { add("Raw replies:"); rx.take(60).forEach { add("  $it") } }
            if (rx.size > 60) add("  … ${rx.size - 60} more reads")
            if (frames.isNotEmpty()) {
                add("Decoded as [cmd][len][payload]:")
                frames.take(60).forEach { add("  " + VxFlyProtocol.interpret(it)) }
            }
            if (decoder.leftover > 0) add("${decoder.leftover} trailing bytes did not complete a frame (framing may differ)")
            if (total > 0) { add("All bytes received:"); addAll(HexDump.dump(all.toByteArray(), 512)) }
        }
        val replyCmds = frames.map { it.cmd }.filter { it != VxFlyProtocol.KEEPALIVE }.distinct()
        val verdict = if (frames.isNotEmpty()) Verdict.FOUND else Verdict.OPEN
        val summary = when {
            frames.isNotEmpty() -> {
                val bat = frames.firstOrNull { it.cmd == VxFlyProtocol.GET_BATTERY }?.let { VxFlyProtocol.interpret(it).substringAfter("→ ", "").takeIf(String::isNotEmpty) }
                val sig = frames.firstOrNull { it.cmd == VxFlyProtocol.GET_RADIO_STATUS }?.let { "\"${it.text}\"" }
                "replies to ${replyCmds.joinToString { VxFlyProtocol.name(it) }.ifEmpty { "keepalive only" }}" +
                    (bat?.let { " · battery $it" } ?: "") + (sig?.let { " · radio $it" } ?: "")
            }
            total > 0 -> "connected, $total bytes, framing unclear: ${HexDump.hex(all.toByteArray(), 16)}"
            else -> "connected, silent for ${listenMs / 1000.0} s"
        }
        ProbeSection(GROUP, label, target, verdict, summary, sent, details, elapsedSince(t0))
    }

    companion object {
        const val GROUP = "VxFly"
    }
}
