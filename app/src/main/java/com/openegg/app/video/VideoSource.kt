package com.openegg.app.video

/**
 * Where the PowerEgg camera stream lives (docs/07-vxfly-video-and-config.md §1).
 *
 * live555 RTSP server on the aircraft, reached through the base-station Wi-Fi. No auth, video is
 * standard RFC 6184 H.264 with SPS/PPS in the SDP; the original client used RTP over UDP unicast.
 */
data class VideoSource(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val path: String = DEFAULT_PATH,
    val query: String? = DEFAULT_QUERY,
    /** Interleaved RTP over the RTSP TCP connection instead of UDP. Unverified on hardware. */
    val forceTcp: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    /** Full RTSP URL, e.g. `rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264`. */
    val url: String
        get() = buildString {
            append("rtsp://")
            // Bare IPv6 literals need brackets in a URI authority.
            if (':' in host && !host.startsWith("[")) append('[').append(host).append(']') else append(host)
            append(':').append(port)
            if (!path.startsWith("/")) append('/')
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query.removePrefix("?"))
        }

    companion object {
        const val DEFAULT_HOST = "192.168.1.240"
        const val DEFAULT_PORT = 8557
        const val DEFAULT_PATH = "/PSIA/Streaming/channels/2"
        const val DEFAULT_QUERY = "videoCodecType=H.264"

        /** The URL decoded from the original app (libvfvideo). */
        const val POWEREGG_URL = "rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264"

        /** The original client's User-Agent; harmless and makes server logs look familiar. */
        const val USER_AGENT = "VFLive (LIVE555 Streaming Media v2017.01.26)"

        val POWEREGG = VideoSource()

        /** Parses an `rtsp://host[:port]/path[?query]` URL (e.g. from a settings field). */
        fun parse(url: String, forceTcp: Boolean = false): VideoSource {
            val trimmed = url.trim()
            require(trimmed.startsWith("rtsp://", ignoreCase = true)) { "not an rtsp:// URL: $url" }
            val rest = trimmed.substring("rtsp://".length)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, slash).substringAfterLast('@') // drop any user:pass@
            val pathAndQuery = rest.substring(slash)
            val (host, port) = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                require(end > 0) { "bad IPv6 host in $url" }
                authority.substring(1, end) to authority.substring(end + 1).removePrefix(":").toPortOr(554)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon < 0) authority to 554
                else authority.substring(0, colon) to authority.substring(colon + 1).toPortOr(554)
            }
            val q = pathAndQuery.indexOf('?')
            val path = (if (q < 0) pathAndQuery else pathAndQuery.substring(0, q)).ifEmpty { "/" }
            val query = if (q < 0) null else pathAndQuery.substring(q + 1).ifEmpty { null }
            return VideoSource(host, port, path, query, forceTcp)
        }

        private fun String.toPortOr(default: Int): Int =
            if (isEmpty()) default else requireNotNull(toIntOrNull()) { "bad port: $this" }
    }
}
