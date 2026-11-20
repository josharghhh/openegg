package com.openegg.app.link.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/** RTSP requests the probe sends: OPTIONS and DESCRIBE only (no SETUP/PLAY, so no stream is started). */
object RtspRequests {
    const val USER_AGENT = "OpenEgg"

    /** The VxFly air unit's default stream, decoded from libvfvideo (doc 07 §1.1). */
    const val VXFLY_URL = "rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264"
    const val VXFLY_URL_CH1 = "rtsp://192.168.1.240:8557/PSIA/Streaming/channels/1?videoCodecType=H.264"

    fun options(url: String, cseq: Int): String =
        "OPTIONS $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: $USER_AGENT\r\n\r\n"

    fun describe(url: String, cseq: Int): String =
        "DESCRIBE $url RTSP/1.0\r\nCSeq: $cseq\r\nAccept: application/sdp\r\nUser-Agent: $USER_AGENT\r\n\r\n"

    fun rootUrl(host: String, port: Int) = "rtsp://$host:$port/"
}

data class RtspResponse(
    val statusLine: String,
    val version: String,
    val code: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: String,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    companion object {
        /**
         * Parses one response from the start of [bytes]. Returns the response and the number of
         * bytes it used, or null if more bytes are needed. Throws if the bytes are not RTSP.
         */
        fun tryParse(bytes: ByteArray, length: Int = bytes.size): Pair<RtspResponse, Int>? {
            val text = String(bytes, 0, length, Charsets.ISO_8859_1)
            var headerEnd = text.indexOf("\r\n\r\n")
            var sepLen = 4
            if (headerEnd < 0) {
                headerEnd = text.indexOf("\n\n"); sepLen = 2
            }
            if (headerEnd < 0) {
                if (length >= 5 && !text.startsWith("RTSP/")) throw IllegalArgumentException("not an RTSP response")
                return null
            }
            val lines = text.substring(0, headerEnd).split("\r\n", "\n")
            val status = lines.first()
            val m = Regex("""^(RTSP/\d\.\d)\s+(\d{3})\s*(.*)$""").find(status)
                ?: throw IllegalArgumentException("bad status line: $status")
            val headers = lines.drop(1).mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
            }
            val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull() ?: 0
            val bodyStart = headerEnd + sepLen
            if (length < bodyStart + contentLength) return null
            val body = String(bytes, bodyStart, contentLength, Charsets.UTF_8)
            return RtspResponse(status, m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], headers, body) to bodyStart + contentLength
        }
    }
}

/** Minimal SDP model: enough to report codec, parameter sets and any resolution hints. */
data class Sdp(
    val sessionName: String?,
    val sessionAttributes: List<Pair<String, String?>>,
    val media: List<Media>,
) {
    data class Media(
        val type: String,
        val port: Int,
        val proto: String,
        val formats: List<String>,
        val attributes: List<Pair<String, String?>>,
    ) {
        fun attr(name: String): String? = attributes.firstOrNull { it.first == name }?.second

        /** payload type -> "H264/90000" */
        val rtpmap: Map<String, String>
            get() = attributes.filter { it.first == "rtpmap" && it.second != null }.associate {
                val v = it.second!!
                v.substringBefore(' ') to v.substringAfter(' ').trim()
            }

        /** payload type -> fmtp parameters (keys lower-cased) */
        val fmtp: Map<String, Map<String, String>>
            get() = attributes.filter { it.first == "fmtp" && it.second != null }.associate {
                val v = it.second!!
                v.substringBefore(' ') to v.substringAfter(' ', "").split(';').mapNotNull { p ->
                    val kv = p.trim()
                    if (kv.isEmpty()) null else {
                        val i = kv.indexOf('=')
                        if (i < 0) kv.lowercase() to "" else kv.substring(0, i).trim().lowercase() to kv.substring(i + 1).trim()
                    }
                }.toMap()
            }
    }

    companion object {
        fun parse(text: String): Sdp {
            var name: String? = null
            val session = mutableListOf<Pair<String, String?>>()
            val media = mutableListOf<Media>()
            var cur: MutableList<Pair<String, String?>>? = null
            var curHead: List<String>? = null
            fun flush() {
                val h = curHead ?: return
                media += Media(h.getOrElse(0) { "" }, h.getOrNull(1)?.substringBefore('/')?.toIntOrNull() ?: 0,
                    h.getOrElse(2) { "" }, h.drop(3), cur.orEmpty())
            }
            for (raw in text.split("\r\n", "\n")) {
                val line = raw.trim()
                if (line.length < 2 || line[1] != '=') continue
                val v = line.substring(2)
                when (line[0]) {
                    's' -> if (curHead == null) name = v
                    'm' -> { flush(); curHead = v.split(' ').filter { it.isNotEmpty() }; cur = mutableListOf() }
                    'a' -> {
                        val i = v.indexOf(':')
                        val attr = if (i < 0) v to null else v.substring(0, i) to v.substring(i + 1)
                        (cur ?: session) += attr
                    }
                }
            }
            flush()
            return Sdp(name, session, media)
        }
    }
}

/** What we could learn about an H.264 track from SDP alone. */
data class H264Track(
    val payloadType: String,
    val rtpmap: String?,
    val profileLevelId: String?,
    val packetizationMode: String?,
    val sps: ByteArray?,
    val pps: ByteArray?,
    val spsInfo: H264Sps.Info?,
    val control: String?,
    val hints: List<String>,
) {
    fun describe(): List<String> = buildList {
        add("payload $payloadType rtpmap=${rtpmap ?: "-"} packetization-mode=${packetizationMode ?: "-"} control=${control ?: "-"}")
        profileLevelId?.let { add("profile-level-id=$it (${H264Sps.profileName(it.take(2).toIntOrNull(16) ?: -1)}, level ${(it.takeLast(2).toIntOrNull(16) ?: 0) / 10.0})") }
        sps?.let { add("SPS (${it.size} B): ${HexDump.hex(it, 64)}${if (it.size > 64) " …" else ""}") }
        pps?.let { add("PPS (${it.size} B): ${HexDump.hex(it, 64)}") }
        spsInfo?.let { add("SPS decode: ${it.describe()}") }
        if (sps != null && spsInfo == null) add("SPS decode: failed")
        hints.forEach { add("resolution/frame hint: $it") }
    }

    companion object {
        fun from(media: Sdp.Media): H264Track? {
            val pt = media.rtpmap.entries.firstOrNull { it.value.startsWith("H264", ignoreCase = true) }?.key
                ?: media.fmtp.keys.firstOrNull { media.fmtp[it]?.containsKey("sprop-parameter-sets") == true }
                ?: return null
            val f = media.fmtp[pt].orEmpty()
            val sets = f["sprop-parameter-sets"]?.split(',')?.mapNotNull { s ->
                runCatching { Base64.getDecoder().decode(s.trim()) }.getOrNull()
            }.orEmpty()
            val sps = sets.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
            val pps = sets.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
            val hints = media.attributes.filter {
                it.first.lowercase() in setOf("framesize", "x-dimensions", "cliprect", "framerate", "x-framerate", "imageattr")
            }.map { "a=${it.first}:${it.second}" }
            return H264Track(pt, media.rtpmap[pt], f["profile-level-id"], f["packetization-mode"], sps, pps,
                sps?.let { H264Sps.parse(it) }, media.attr("control"), hints)
        }
    }
}

/** H.264 sequence parameter set decoder: profile, level and the cropped picture size. */
object H264Sps {

    data class Info(
        val profileIdc: Int,
        val constraintFlags: Int,
        val levelIdc: Int,
        val chromaFormatIdc: Int,
        val width: Int,
        val height: Int,
        val frameMbsOnly: Boolean,
    ) {
        fun describe() = "${width}x$height ${profileName(profileIdc)} profile ($profileIdc) level ${levelIdc / 10.0}, " +
            "chroma_format_idc=$chromaFormatIdc ${if (frameMbsOnly) "progressive" else "interlaced/field"}"
    }

    fun profileName(idc: Int) = when (idc) {
        66 -> "Baseline"; 77 -> "Main"; 88 -> "Extended"; 100 -> "High"; 110 -> "High 10"
        122 -> "High 4:2:2"; 244 -> "High 4:4:4"; else -> "profile $idc"
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00). */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(private val d: ByteArray) {
        private var pos = 0
        fun u1(): Int {
            if (pos >= d.size * 8) throw IndexOutOfBoundsException("SPS truncated")
            val bit = (d[pos / 8].toInt() shr (7 - pos % 8)) and 1
            pos++
            return bit
        }
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or u1() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (u1() == 0) { zeros++; if (zeros > 31) throw IllegalArgumentException("bad exp-golomb") }
            return ((1 shl zeros) - 1) + u(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    /** [nal] starts with the NAL header byte (0x67 / type 7). Returns null if it is not a decodable SPS. */
    fun parse(nal: ByteArray): Info? = runCatching {
        require(nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) { "not an SPS" }
        val rbsp = unescape(nal)
        val b = Bits(rbsp.copyOfRange(1, rbsp.size))
        val profile = b.u(8)
        val constraints = b.u(8)
        val level = b.u(8)
        b.ue() // seq_parameter_set_id
        var chroma = 1
        var separatePlanes = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = b.ue()
            if (chroma == 3) separatePlanes = b.u1() == 1
            b.ue(); b.ue() // bit depths
            b.u1() // qpprime_y_zero_transform_bypass_flag
            if (b.u1() == 1) { // seq_scaling_matrix_present_flag
                for (i in 0 until if (chroma != 3) 8 else 12) {
                    if (b.u1() == 1) {
                        val size = if (i < 6) 16 else 64
                        var last = 8; var next = 8
                        for (j in 0 until size) {
                            if (next != 0) next = (last + b.se() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        b.ue() // log2_max_frame_num_minus4
        when (b.ue()) { // pic_order_cnt_type
            0 -> b.ue()
            1 -> { b.u1(); b.se(); b.se(); repeat(b.ue()) { b.se() } }
        }
        b.ue() // max_num_ref_frames
        b.u1() // gaps_in_frame_num_value_allowed_flag
        val wMbs = b.ue() + 1
        val hMapUnits = b.ue() + 1
        val frameMbsOnly = b.u1() == 1
        if (!frameMbsOnly) b.u1() // mb_adaptive_frame_field_flag
        b.u1() // direct_8x8_inference_flag
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (b.u1() == 1) { cl = b.ue(); cr = b.ue(); ct = b.ue(); cb = b.ue() }
        val arrayType = if (separatePlanes) 0 else chroma
        val (subW, subH) = when (arrayType) { 1 -> 2 to 2; 2 -> 2 to 1; else -> 1 to 1 }
        val cropX = if (arrayType == 0) 1 else subW
        val cropY = (if (arrayType == 0) 1 else subH) * (if (frameMbsOnly) 1 else 2)
        val width = wMbs * 16 - cropX * (cl + cr)
        val height = (if (frameMbsOnly) 1 else 2) * hMapUnits * 16 - cropY * (ct + cb)
        Info(profile, constraints, level, chroma, width, height, frameMbsOnly)
    }.getOrNull()
}

/**
 * Active RTSP probe. Sends OPTIONS, and optionally DESCRIBE for each given URL on the same
 * connection (reconnecting once if the server closed it). No SETUP or PLAY.
 */
class RtspProbe(
    private val connectTimeoutMs: Int = 2000,
    private val responseTimeoutMs: Long = 3000,
) {
    private class Exchange(val request: String, val response: RtspResponse?, val raw: ByteArray, val error: String?, val ms: Long)

    suspend fun run(
        env: ProbeEnvironment,
        label: String,
        host: String,
        port: Int,
        optionsUrl: String = RtspRequests.rootUrl(host, port),
        describeUrls: List<String> = emptyList(),
    ): ProbeSection = withContext(Dispatchers.IO) {
        val target = "$host:$port"
        val t0 = System.nanoTime()
        var socket: Socket = try {
            env.socketFactory.connectTcp(host, port, connectTimeoutMs)
        } catch (e: Exception) {
            val (v, why) = connectFailure(e)
            return@withContext ProbeSection(GROUP, label, target, v, why, elapsedMs = elapsedSince(t0))
        }
        val notes = mutableListOf("TCP connected in ${elapsedSince(t0)} ms")
        val exchanges = mutableListOf<Exchange>()
        val requests = listOf(RtspRequests.options(optionsUrl, 1)) +
            describeUrls.mapIndexed { i, u -> RtspRequests.describe(u, i + 2) }
        try {
            for (req in requests) {
                ensureActive()
                var ex = exchange(socket, req)
                if (ex.response == null && ex.raw.isEmpty() && ex.error != null && ex.error.startsWith("closed")) {
                    // The server hung up after the previous reply; retry on a fresh connection.
                    runCatching { socket.close() }
                    socket = try { env.socketFactory.connectTcp(host, port, connectTimeoutMs) } catch (e: Exception) {
                        notes += "reconnect failed: ${describeError(e)}"; break
                    }
                    notes += "server closed the connection; reconnected for the next request"
                    ex = exchange(socket, req)
                }
                exchanges += ex
            }
        } finally {
            runCatching { socket.close() }
        }

        val sent = exchanges.map { it.request.lineSequence().first() + " (CSeq " + RtspResponse.cseqOf(it.request) + ")" }
        val details = mutableListOf<String>()
        details += notes
        var bestTrack: H264Track? = null
        for (ex in exchanges) {
            val reqLine = ex.request.lineSequence().first()
            details += "→ $reqLine"
            val r = ex.response
            if (r == null) {
                details += "  no RTSP reply in ${ex.ms} ms" + (ex.error?.let { " ($it)" } ?: "")
                if (ex.raw.isNotEmpty()) { details += "  raw reply:"; details += HexDump.dump(ex.raw, 256, "    ") }
                continue
            }
            details += "← ${r.statusLine}  (${ex.ms} ms)"
            r.headers.forEach { (k, v) -> details += "    $k: $v" }
            if (r.body.isNotEmpty()) {
                details += "  body (${r.body.length} chars):"
                HexDump.visibleText(r.body, 3000).lines().forEach { details += "    $it" }
            }
            if ((r.code == 200 && r.header("Content-Type")?.contains("sdp", true) == true) || r.body.startsWith("v=0")) {
                val sdp = Sdp.parse(r.body)
                details += "  SDP: session '${sdp.sessionName ?: "-"}', ${sdp.media.size} media"
                for (m in sdp.media) {
                    details += "    m=${m.type} port ${m.port} ${m.proto} fmt ${m.formats.joinToString(",")}"
                    val track = H264Track.from(m)
                    if (track != null) {
                        track.describe().forEach { details += "      $it" }
                        if (bestTrack == null) bestTrack = track
                    } else {
                        m.rtpmap.forEach { (pt, enc) -> details += "      rtpmap $pt $enc" }
                    }
                }
            }
        }

        val replies = exchanges.mapNotNull { it.response }
        val verdict = when {
            replies.isNotEmpty() -> Verdict.FOUND
            else -> Verdict.OPEN
        }
        val summary = if (replies.isEmpty()) {
            val raw = exchanges.firstOrNull { it.raw.isNotEmpty() }?.raw
            if (raw != null) "connected, non-RTSP reply: ${HexDump.ascii(raw, 40)}" else "connected, no reply to OPTIONS"
        } else buildString {
            append(exchanges.joinToString(" · ") { e ->
                val method = e.request.substringBefore(' ')
                method + " " + (e.response?.let { "${it.code} ${it.reason}" } ?: "no reply")
            })
            replies.firstNotNullOfOrNull { it.header("Server") }?.let { append(" · Server: $it") }
            bestTrack?.let { t ->
                append(" · H264")
                t.spsInfo?.let { append(" ${it.width}x${it.height} ${profileShort(it)}") }
                if (t.sps != null) append(" · sprop ok")
            }
            if (describeUrls.isEmpty()) replies.firstNotNullOfOrNull { it.header("Public") }?.let { append(" · Public: $it") }
        }
        ProbeSection(GROUP, label, target, verdict, summary, sent, details, elapsedSince(t0))
    }

    private fun profileShort(i: H264Sps.Info) = "${H264Sps.profileName(i.profileIdc)}@${i.levelIdc / 10.0}"

    private fun exchange(socket: Socket, request: String): Exchange {
        val start = System.nanoTime()
        try {
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
        } catch (e: Exception) {
            return Exchange(request, null, ByteArray(0), "closed: ${describeError(e)}", elapsedSince(start))
        }
        socket.soTimeout = 200
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val input = socket.getInputStream()
        while (elapsedSince(start) < responseTimeoutMs) {
            val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 } catch (e: Exception) {
                return Exchange(request, null, acc.toByteArray(), "closed: ${describeError(e)}", elapsedSince(start))
            }
            if (n < 0) return Exchange(request, null, acc.toByteArray(), "closed by server", elapsedSince(start))
            if (n == 0) continue
            acc.write(buf, 0, n)
            if (acc.size() > 64 * 1024) break
            val bytes = acc.toByteArray()
            val parsed = try { RtspResponse.tryParse(bytes) } catch (e: IllegalArgumentException) {
                return Exchange(request, null, bytes, e.message, elapsedSince(start))
            }
            if (parsed != null) return Exchange(request, parsed.first, bytes, null, elapsedSince(start))
        }
        return Exchange(request, null, acc.toByteArray(), "timed out", elapsedSince(start))
    }

    companion object {
        const val GROUP = "RTSP"
    }
}

internal fun RtspResponse.Companion.cseqOf(request: String): String =
    request.lineSequence().firstOrNull { it.startsWith("CSeq:", true) }?.substringAfter(':')?.trim() ?: "?"
