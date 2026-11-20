package com.openegg.app.vxfly

/**
 * One VxFly "VF config" frame (docs/07 §2.2):
 *
 * ```
 * [cmd u8][len u8][payload[len], ASCII]
 * ```
 *
 * No sync byte, sequence number, terminator or checksum. Replies use the same
 * framing and echo the request's cmd byte, which is the only way to match them.
 */
class VxFrame(val cmd: Int, payload: ByteArray = EMPTY) {

    val payload: ByteArray = payload.copyOf()

    init {
        require(cmd in 0..0xFF) { "cmd out of range: $cmd" }
        require(this.payload.size <= 0xFF) { "payload too long: ${this.payload.size} > 255" }
    }

    /**
     * Payload as text. The native code NUL-terminates at `len` and `vsscanf`s it;
     * we additionally strip trailing NULs and surrounding whitespace so a stray
     * CR/LF from the unit cannot turn "FAILED" into a false success.
     */
    val text: String get() = String(payload, Charsets.US_ASCII).trimEnd('\u0000').trim()

    /** Native success test is `strcasecmp(data, "FAILED") != 0` (docs/07 §2.2). */
    val isFailed: Boolean get() = text.equals("FAILED", ignoreCase = true)

    fun encode(): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = cmd.toByte()
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is VxFrame && other.cmd == cmd && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()

    override fun toString(): String =
        "VxFrame(0x%02X, \"%s\")".format(cmd, String(payload, Charsets.US_ASCII))

    companion object {
        private val EMPTY = ByteArray(0)

        /** Text payload (integers are sent as `sprintf("%d")`). */
        fun text(cmd: Int, text: String) = VxFrame(cmd, text.toByteArray(Charsets.US_ASCII))

        fun int(cmd: Int, value: Int) = text(cmd, value.toString())
    }
}

/**
 * Streaming decoder for a TCP byte stream: tolerates frames split across reads
 * and several frames merged into one read. Mirrors `packetIntact` / `getPacket`
 * in libvfconfig: a frame is complete once `buf[1] + 2` bytes are queued.
 *
 * Since there is no sync byte, a corrupted length byte would desynchronise the
 * stream; the client recovers from that the same way the original did, by the
 * liveness timeout closing and reopening the socket.
 */
class VxFrameDecoder {
    private var buf = ByteArray(512)
    private var size = 0

    /** Bytes buffered but not yet forming a whole frame. */
    val pendingBytes: Int get() = size

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<VxFrame> {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        data.copyInto(buf, size, offset, offset + length)
        size += length

        val out = ArrayList<VxFrame>(2)
        var pos = 0
        while (size - pos >= 2) {
            val len = buf[pos + 1].toInt() and 0xFF
            if (size - pos < len + 2) break
            out += VxFrame(buf[pos].toInt() and 0xFF, buf.copyOfRange(pos + 2, pos + 2 + len))
            pos += len + 2
        }
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, size)
            size -= pos
        }
        return out
    }

    fun reset() {
        size = 0
    }
}

/**
 * Command ids (docs/07 §2.4, `vf/config/VFConfig.java:7-46`). Only the ones
 * OpenEgg uses or must avoid are named.
 */
object VxCmd {
    const val KEEPALIVE = 0x00
    const val GET_AP_SSID = 0x03
    const val SET_AP_SSID = 0x04
    const val GET_AP_PASSWORD = 0x05
    const val SET_AP_PASSWORD = 0x06
    const val CU_RESET = 0x07
    const val CU_REBOOT = 0x09
    const val UNBIND_DL = 0x0B
    const val GET_MODEL = 0x0C
    const val GET_SN = 0x0D
    const val GET_DL_HW_VERSION = 0x0E
    const val GET_DL_SW_VERSION = 0x0F
    const val GET_BATTERY_VOLTAGE = 0x10
    const val BIND_DL = 0x11
    const val GET_DL_RSSI = 0x12
    const val GET_DL_CHANNEL = 0x13
    const val SET_DL_CHANNEL = 0x14
    const val GET_DL_POWER = 0x15
    const val SET_DL_POWER = 0x16
    const val GET_VIDEO_RESOLUTION = 0x17
    const val SET_VIDEO_RESOLUTION = 0x18
    const val GET_VIDEO_RATE = 0x19
    const val SET_VIDEO_RATE = 0x1A
    const val APPLY_DL_CONFIG = 0x1B
    const val LOGIN = 0x1C
    const val GET_HW_VERSION = 0x1D
    const val GET_SW_VERSION = 0x1E
    const val GET_RADIO_STATUS = 0x1F
    const val GET_COUNTRY_CODE = 0x20
    const val SET_COUNTRY_CODE = 0x21
    const val GET_SQ_THRESH = 0x22
    const val SET_SQ_THRESH = 0x23
    const val SCAN_DL_CHANNELS = 0x24
    const val UPDATE_FIRMWARE = 0x25
    const val UPDATE_DL_FIRMWARE = 0x26

    /** Getters that only read state; safe to send at any time. */
    val READ_ONLY: Set<Int> = setOf(
        GET_AP_SSID, GET_AP_PASSWORD, GET_MODEL, GET_SN, GET_DL_HW_VERSION, GET_DL_SW_VERSION,
        GET_BATTERY_VOLTAGE, GET_DL_RSSI, GET_DL_CHANNEL, GET_DL_POWER, GET_VIDEO_RESOLUTION,
        GET_VIDEO_RATE, GET_HW_VERSION, GET_SW_VERSION, GET_RADIO_STATUS, GET_COUNTRY_CODE, GET_SQ_THRESH,
    )

    /**
     * Commands that change the radio link, AP or pairing, or reboot/reset the unit
     * (docs/07 §5 plus the other setters). They can unpair the drone. The client
     * never sends these except through its explicit, UI-confirmed setters.
     */
    val DANGEROUS: Set<Int> = setOf(
        SET_AP_SSID, SET_AP_PASSWORD, CU_RESET, CU_REBOOT, UNBIND_DL, BIND_DL, SET_DL_CHANNEL,
        SET_DL_POWER, SET_VIDEO_RESOLUTION, SET_VIDEO_RATE, APPLY_DL_CONFIG, SET_COUNTRY_CODE,
        SET_SQ_THRESH, SCAN_DL_CHANNELS, UPDATE_FIRMWARE, UPDATE_DL_FIRMWARE,
    )
}
