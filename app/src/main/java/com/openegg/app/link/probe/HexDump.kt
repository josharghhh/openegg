package com.openegg.app.link.probe

/** Hex helpers for the link report. */
object HexDump {

    /** "fe 09 00 ff" style, space separated, no truncation. */
    fun hex(bytes: ByteArray, length: Int = bytes.size): String =
        (0 until minOf(length, bytes.size)).joinToString(" ") { "%02x".format(bytes[it].toInt() and 0xFF) }

    /** Printable ASCII with '.' for everything else. */
    fun ascii(bytes: ByteArray, length: Int = bytes.size): String = buildString {
        for (i in 0 until minOf(length, bytes.size)) {
            val c = bytes[i].toInt() and 0xFF
            append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
    }

    /**
     * Classic 16-byte-per-line dump: `0000  fe 09 ...  |....|`. Output stops after [maxBytes]
     * with a note giving how many bytes were left out.
     */
    fun dump(bytes: ByteArray, maxBytes: Int = 256, indent: String = "  "): List<String> {
        val shown = minOf(bytes.size, maxBytes)
        val lines = mutableListOf<String>()
        var off = 0
        while (off < shown) {
            val n = minOf(16, shown - off)
            val chunk = bytes.copyOfRange(off, off + n)
            lines += "%s%04x  %-47s  |%s|".format(indent, off, hex(chunk), ascii(chunk))
            off += n
        }
        if (bytes.size > shown) lines += "$indent... ${bytes.size - shown} more bytes not shown (${bytes.size} total)"
        return lines
    }

    /** Text with control characters made visible, for RTSP/SDP excerpts; truncated to [maxChars]. */
    fun visibleText(text: String, maxChars: Int = 4000): String {
        val t = if (text.length > maxChars) text.substring(0, maxChars) + "\n... (${text.length - maxChars} more chars)" else text
        return t.replace("\r\n", "\n").replace("\r", "\\r")
    }
}
