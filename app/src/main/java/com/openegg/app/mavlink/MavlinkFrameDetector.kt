package com.openegg.app.mavlink

/**
 * Minimal streaming MAVLink v1/v2 frame detector used by the link tester.
 *
 * It finds frames in an arbitrary byte stream, checks length and CRC (when the
 * message's CRC_EXTRA is known) and reports the header fields. Full message
 * decoding lives in the protocol layer; this class exists so we can prove on
 * real hardware which socket carries MAVLink before that layer is finished.
 */
class MavlinkFrameDetector(
    private val crcExtras: Map<Int, Int> = STANDARD_CRC_EXTRAS,
    /** Extra CRC_EXTRA values accepted per id (firmware tables that differ from upstream). */
    private val alternateCrcExtras: Map<Int, Set<Int>> = emptyMap(),
) {

    data class Frame(
        val version: Int,
        val sequence: Int,
        val systemId: Int,
        val componentId: Int,
        val messageId: Int,
        val payload: ByteArray,
        /** null when the CRC_EXTRA for this message id is unknown. */
        val crcValid: Boolean?,
        val signed: Boolean,
    )

    private val buf = ArrayList<Byte>(4096)

    /** Feeds bytes; returns every complete frame found so far. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<Frame> {
        for (i in 0 until length) buf.add(bytes[i])
        val frames = mutableListOf<Frame>()
        while (true) {
            val start = buf.indexOfFirst { it == STX_V1 || it == STX_V2 }
            if (start < 0) { buf.clear(); break }
            if (start > 0) buf.subList(0, start).clear()
            val parsed = tryParse() ?: break
            if (parsed.second == null) {
                // Not a valid frame at this STX; skip the byte and resync.
                buf.removeAt(0)
                continue
            }
            frames += parsed.second!!
            buf.subList(0, parsed.first).clear()
        }
        return frames
    }

    /** @return (frameLength, frame-or-null-if-invalid), or null if more bytes are needed. */
    private fun tryParse(): Pair<Int, Frame?>? {
        if (buf.size < 2) return null
        val v2 = buf[0] == STX_V2
        val len = u8(1)
        val headerLen = if (v2) 10 else 6
        if (buf.size < headerLen) return null
        val incompat = if (v2) u8(2) else 0
        val signed = v2 && (incompat and 0x01) != 0
        val total = headerLen + len + 2 + if (signed) 13 else 0
        if (buf.size < total) return null

        val seq = if (v2) u8(4) else u8(2)
        val sys = if (v2) u8(5) else u8(3)
        val comp = if (v2) u8(6) else u8(4)
        val msgId = if (v2) u8(7) or (u8(8) shl 8) or (u8(9) shl 16) else u8(5)
        val payload = ByteArray(len) { buf[headerLen + it] }
        val rxCrc = u8(headerLen + len) or (u8(headerLen + len + 1) shl 8)

        val candidates = listOfNotNull(crcExtras[msgId]) + alternateCrcExtras[msgId].orEmpty()
        val crcValid = if (candidates.isEmpty()) null else {
            var base = X25.INIT
            for (i in 1 until headerLen + len) base = X25.accumulate(buf[i].toInt() and 0xFF, base)
            candidates.any { X25.accumulate(it, base) == rxCrc }
        }
        // A frame with a known CRC_EXTRA that fails the CRC is treated as noise.
        if (crcValid == false) return total to null
        val frame = Frame(if (v2) 2 else 1, seq, sys, comp, msgId, payload, crcValid, signed)
        // Unverifiable (unknown id): report it, but consume only the STX so a false start byte
        // can never swallow the genuine frames behind it.
        return (if (crcValid == null) 1 else total) to frame
    }

    private fun u8(i: Int) = buf[i].toInt() and 0xFF

    companion object {
        const val STX_V1: Byte = 0xFE.toByte()
        const val STX_V2: Byte = 0xFD.toByte()

        /** CRC_EXTRA for a few common.xml messages, enough to validate a live link. */
        val STANDARD_CRC_EXTRAS = mapOf(
            0 to 50,    // HEARTBEAT
            1 to 124,   // SYS_STATUS
            24 to 24,   // GPS_RAW_INT
            30 to 39,   // ATTITUDE
            33 to 104,  // GLOBAL_POSITION_INT
            74 to 20,   // VFR_HUD
            76 to 152,  // COMMAND_LONG
            77 to 143,  // COMMAND_ACK
            253 to 83,  // STATUSTEXT
        )
    }
}

/** CRC-16/MCRF4XX ("X.25") as used by MAVLink. */
object X25 {
    const val INIT = 0xFFFF

    fun accumulate(byte: Int, crc: Int): Int {
        var tmp = byte xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    fun of(bytes: ByteArray, extra: Int? = null): Int {
        var crc = INIT
        for (b in bytes) crc = accumulate(b.toInt() and 0xFF, crc)
        if (extra != null) crc = accumulate(extra, crc)
        return crc
    }
}
