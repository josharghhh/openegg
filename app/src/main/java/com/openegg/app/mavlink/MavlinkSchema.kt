package com.openegg.app.mavlink

/**
 * Table-driven MAVLink schema. Message definitions are generated from the
 * official XML (see app/tools/gen_mavlink.py); PowerVision's custom messages
 * are added as a separate dialect once reverse-engineered.
 */
enum class MavType(val wireName: String, val size: Int) {
    CHAR("char", 1),
    UINT8("uint8_t", 1),
    INT8("int8_t", 1),
    UINT16("uint16_t", 2),
    INT16("int16_t", 2),
    UINT32("uint32_t", 4),
    INT32("int32_t", 4),
    FLOAT("float", 4),
    UINT64("uint64_t", 8),
    INT64("int64_t", 8),
    DOUBLE("double", 8),
}

data class MavField(
    val name: String,
    val type: MavType,
    /** 0 for scalars. */
    val arrayLength: Int = 0,
    /** MAVLink 2 extension field: appended after base fields, excluded from CRC_EXTRA. */
    val extension: Boolean = false,
) {
    val byteLength get() = type.size * maxOf(1, arrayLength)
}

class MavMessageDef(
    val id: Int,
    val name: String,
    /** Fields in XML declaration order. */
    declared: List<MavField>,
    /** CRC_EXTRA from the generator; verified against [computeCrcExtra] in tests. */
    val crcExtra: Int,
) {
    /** Wire order: base fields stably sorted by element size (largest first), then extensions as declared. */
    val wireFields: List<MavField> =
        declared.filter { !it.extension }.sortedByDescending { it.type.size } + declared.filter { it.extension }

    val baseLength: Int = wireFields.filter { !it.extension }.sumOf { it.byteLength }
    val maxLength: Int = wireFields.sumOf { it.byteLength }

    fun computeCrcExtra(): Int {
        var crc = X25.INIT
        fun str(s: String) { for (c in s) crc = X25.accumulate(c.code and 0xFF, crc) }
        str("$name ")
        for (f in wireFields) {
            if (f.extension) continue
            str("${f.type.wireName} ")
            str("${f.name} ")
            if (f.arrayLength > 0) crc = X25.accumulate(f.arrayLength, crc)
        }
        return (crc and 0xFF) xor (crc shr 8)
    }

    override fun toString() = "MavMessageDef($name#$id)"
}

/** A set of message definitions keyed by id (one or more XML dialects merged). */
class MavDialect(defs: Collection<MavMessageDef>) {
    val byId: Map<Int, MavMessageDef> = defs.associateBy { it.id }
    val byName: Map<String, MavMessageDef> = defs.associateBy { it.name }

    operator fun get(id: Int) = byId[id]
    operator fun get(name: String) = byName[name] ?: error("Unknown MAVLink message $name")

    operator fun plus(other: MavDialect) = MavDialect(byId.values + other.byId.values)
}
