package com.openegg.app.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded message. Values: Int/Long/Float/Double for scalars, String for char[], arrays for other arrays. */
data class MavMessage(
    val def: MavMessageDef,
    val values: Map<String, Any>,
    val systemId: Int = 0,
    val componentId: Int = 0,
    val sequence: Int = 0,
) {
    val id get() = def.id
    val name get() = def.name

    fun int(field: String): Int = (values[field] as Number).toInt()
    fun long(field: String): Long = (values[field] as Number).toLong()
    fun float(field: String): Float = (values[field] as Number).toFloat()
    fun double(field: String): Double = (values[field] as Number).toDouble()
    fun string(field: String): String = values[field] as String

    override fun toString() = "$name(sys=$systemId comp=$componentId $values)"
}

/**
 * Encodes and decodes MAVLink v1/v2 frames for a [MavDialect].
 * Unsigned values decode to the next wider Kotlin type (uint8/16 -> Int, uint32 -> Long, uint64 -> Long bits).
 */
class MavlinkCodec(
    val dialect: MavDialect,
    var systemId: Int = 255,
    var componentId: Int = 190, // MAV_COMP_ID_MISSIONPLANNER, the conventional GCS component
    var version: Int = 1,
) {
    private var seq = 0

    fun encode(name: String, values: Map<String, Any>): ByteArray = encode(dialect[name], values)

    fun encode(def: MavMessageDef, values: Map<String, Any>): ByteArray {
        val payloadFull = ByteBuffer.allocate(def.maxLength).order(ByteOrder.LITTLE_ENDIAN)
        for (f in def.wireFields) {
            if (version == 1 && f.extension) continue
            writeField(payloadFull, f, values[f.name])
        }
        var len = payloadFull.position()
        val payload = payloadFull.array()
        if (version == 2) {
            // MAVLink 2 truncates trailing zero bytes (minimum length 1).
            while (len > 1 && payload[len - 1].toInt() == 0) len--
        }

        val header = if (version == 2) {
            byteArrayOf(
                len.toByte(), 0, 0, nextSeq().toByte(), systemId.toByte(), componentId.toByte(),
                (def.id and 0xFF).toByte(), ((def.id shr 8) and 0xFF).toByte(), ((def.id shr 16) and 0xFF).toByte(),
            )
        } else {
            require(def.id <= 255) { "${def.name} (id ${def.id}) needs MAVLink 2" }
            byteArrayOf(len.toByte(), nextSeq().toByte(), systemId.toByte(), componentId.toByte(), def.id.toByte())
        }
        val body = header + payload.copyOf(len)
        val crc = X25.of(body, def.crcExtra)
        val stx = if (version == 2) MavlinkFrameDetector.STX_V2 else MavlinkFrameDetector.STX_V1
        return byteArrayOf(stx) + body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    /** Decodes a frame found by [MavlinkFrameDetector]; null if the id is not in the dialect. */
    fun decode(frame: MavlinkFrameDetector.Frame): MavMessage? {
        val def = dialect[frame.messageId] ?: return null
        // Zero-extend truncated v2 payloads (and tolerate short v1 payloads defensively).
        val bytes = frame.payload.copyOf(maxOf(frame.payload.size, def.maxLength))
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = LinkedHashMap<String, Any>()
        for (f in def.wireFields) out[f.name] = readField(buf, f)
        return MavMessage(def, out, frame.systemId, frame.componentId, frame.sequence)
    }

    private fun nextSeq(): Int { val s = seq; seq = (seq + 1) and 0xFF; return s }

    private fun writeField(buf: ByteBuffer, f: MavField, v: Any?) {
        if (f.arrayLength == 0) { writeScalar(buf, f.type, v ?: 0); return }
        if (f.type == MavType.CHAR) {
            val s = (v as? String ?: "").toByteArray(Charsets.US_ASCII)
            for (i in 0 until f.arrayLength) buf.put(if (i < s.size) s[i] else 0)
            return
        }
        val list: List<Any> = when (v) {
            null -> emptyList()
            is IntArray -> v.toList()
            is LongArray -> v.toList()
            is FloatArray -> v.toList()
            is DoubleArray -> v.toList()
            is ByteArray -> v.map { it.toInt() and 0xFF }
            is List<*> -> v.filterNotNull()
            else -> error("Bad array value for ${f.name}: $v")
        }
        for (i in 0 until f.arrayLength) writeScalar(buf, f.type, list.getOrElse(i) { 0 })
    }

    private fun writeScalar(buf: ByteBuffer, t: MavType, v: Any) {
        val n = v as Number
        when (t) {
            MavType.CHAR, MavType.UINT8, MavType.INT8 -> buf.put(n.toInt().toByte())
            MavType.UINT16, MavType.INT16 -> buf.putShort(n.toInt().toShort())
            MavType.UINT32, MavType.INT32 -> buf.putInt(n.toLong().toInt())
            MavType.UINT64, MavType.INT64 -> buf.putLong(n.toLong())
            MavType.FLOAT -> buf.putFloat(n.toFloat())
            MavType.DOUBLE -> buf.putDouble(n.toDouble())
        }
    }

    private fun readField(buf: ByteBuffer, f: MavField): Any {
        if (f.arrayLength == 0) return readScalar(buf, f.type)
        return when (f.type) {
            MavType.CHAR -> {
                val b = ByteArray(f.arrayLength).also { buf.get(it) }
                val end = b.indexOf(0).let { if (it < 0) b.size else it }
                String(b, 0, end, Charsets.US_ASCII)
            }
            MavType.FLOAT -> FloatArray(f.arrayLength) { buf.getFloat() }
            MavType.DOUBLE -> DoubleArray(f.arrayLength) { buf.getDouble() }
            MavType.UINT64, MavType.INT64, MavType.UINT32 -> LongArray(f.arrayLength) { (readScalar(buf, f.type) as Number).toLong() }
            else -> IntArray(f.arrayLength) { (readScalar(buf, f.type) as Number).toInt() }
        }
    }

    private fun readScalar(buf: ByteBuffer, t: MavType): Any = when (t) {
        MavType.CHAR, MavType.UINT8 -> buf.get().toInt() and 0xFF
        MavType.INT8 -> buf.get().toInt()
        MavType.UINT16 -> buf.getShort().toInt() and 0xFFFF
        MavType.INT16 -> buf.getShort().toInt()
        MavType.UINT32 -> buf.getInt().toLong() and 0xFFFFFFFFL
        MavType.INT32 -> buf.getInt()
        MavType.UINT64, MavType.INT64 -> buf.getLong()
        MavType.FLOAT -> buf.getFloat()
        MavType.DOUBLE -> buf.getDouble()
    }
}
