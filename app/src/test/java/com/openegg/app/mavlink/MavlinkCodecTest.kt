package com.openegg.app.mavlink

import com.openegg.app.mavlink.gen.CommonDialect
import com.openegg.app.mavlink.gen.MavAutopilot
import com.openegg.app.mavlink.gen.MavCmd
import com.openegg.app.mavlink.gen.MavType as MavVehicleType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavlinkCodecTest {

    private val dialect = CommonDialect.dialect

    @Test
    fun generatedCrcExtrasMatchSchemaComputation() {
        for (def in dialect.byId.values) {
            assertEquals("CRC_EXTRA mismatch for ${def.name}", def.crcExtra, def.computeCrcExtra())
        }
    }

    @Test
    fun crcExtrasMatchPublishedValues() {
        // Independently published values (pymavlink mavlink_crc tables).
        val known = mapOf(
            "HEARTBEAT" to 50, "SYS_STATUS" to 124, "SYSTEM_TIME" to 137, "PARAM_REQUEST_READ" to 214,
            "PARAM_REQUEST_LIST" to 159, "PARAM_VALUE" to 220, "PARAM_SET" to 168, "GPS_RAW_INT" to 24,
            "ATTITUDE" to 39, "GLOBAL_POSITION_INT" to 104, "RC_CHANNELS" to 118, "MANUAL_CONTROL" to 243,
            "VFR_HUD" to 20, "COMMAND_LONG" to 152, "COMMAND_ACK" to 143, "HOME_POSITION" to 104,
            "EXTENDED_SYS_STATE" to 130, "STATUSTEXT" to 83, "BATTERY_STATUS" to 154, "MISSION_COUNT" to 221,
            "MISSION_ITEM_INT" to 38, "MISSION_REQUEST_INT" to 196, "MISSION_ACK" to 153, "MISSION_CURRENT" to 28,
            "MISSION_REQUEST_LIST" to 132, "MISSION_CLEAR_ALL" to 232, "SET_MODE" to 89,
        )
        for ((name, extra) in known) assertEquals(name, extra, dialect[name].crcExtra)
    }

    @Test
    fun heartbeatWireOrderPutsCustomModeFirst() {
        val names = dialect["HEARTBEAT"].wireFields.map { it.name }
        assertEquals(listOf("custom_mode", "type", "autopilot", "base_mode", "system_status", "mavlink_version"), names)
    }

    @Test
    fun v1RoundTripThroughDetector() {
        val codec = MavlinkCodec(dialect, systemId = 255, componentId = 190, version = 1)
        val bytes = codec.encode(
            "COMMAND_LONG",
            mapOf(
                "target_system" to 1, "target_component" to 1, "command" to MavCmd.NAV_RETURN_TO_LAUNCH,
                "confirmation" to 0, "param1" to 0f,
            ),
        )
        val detector = MavlinkFrameDetector(dialect.byId.mapValues { it.value.crcExtra })
        val frame = detector.feed(bytes).single()
        assertEquals(true, frame.crcValid)
        val msg = codec.decode(frame)!!
        assertEquals("COMMAND_LONG", msg.name)
        assertEquals(MavCmd.NAV_RETURN_TO_LAUNCH, msg.int("command"))
        assertEquals(1, msg.int("target_system"))
        assertEquals(255, msg.systemId)
    }

    @Test
    fun v2TruncatesTrailingZerosAndDecodesBack() {
        val codec = MavlinkCodec(dialect, version = 2)
        val bytes = codec.encode(
            "HEARTBEAT",
            mapOf("type" to MavVehicleType.GCS, "autopilot" to MavAutopilot.INVALID, "mavlink_version" to 0),
        )
        // custom_mode(4) + type, autopilot... trailing zeros dropped.
        assertTrue(bytes[1] < 9)
        val detector = MavlinkFrameDetector(dialect.byId.mapValues { it.value.crcExtra })
        val msg = codec.decode(detector.feed(bytes).single())!!
        assertEquals(MavVehicleType.GCS, msg.int("type"))
        assertEquals(0L, msg.long("custom_mode"))
    }

    @Test
    fun charArraysAndFloatsRoundTrip() {
        val codec = MavlinkCodec(dialect, version = 1)
        val bytes = codec.encode(
            "PARAM_SET",
            mapOf("target_system" to 1, "target_component" to 1, "param_id" to "PV_V_KEY_FLAG", "param_value" to 1.5f, "param_type" to 9),
        )
        val detector = MavlinkFrameDetector(dialect.byId.mapValues { it.value.crcExtra })
        val msg = codec.decode(detector.feed(bytes).single())!!
        assertEquals("PV_V_KEY_FLAG", msg.string("param_id"))
        assertEquals(1.5f, msg.float("param_value"), 0f)
    }

    @Test
    fun knownHeartbeatBytesDecode() {
        // Hand-built PX4-style heartbeat (MAVLink v1, seq 0x2a, sys 1, comp 1); replace with a
        // capture from the user's PowerEgg once the link tester has recorded one.
        val payload = byteArrayOf(0x00, 0x00, 0x04, 0x03, 0x02, 0x0C, 0x1D, 0x04, 0x03)
        val header = byteArrayOf(0x09, 0x2A, 0x01, 0x01, 0x00)
        val crc = X25.of(header + payload, 50)
        val frame = byteArrayOf(0xFE.toByte()) + header + payload + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        val codec = MavlinkCodec(dialect)
        val msg = codec.decode(MavlinkFrameDetector().feed(frame).single())!!
        assertEquals(0x03040000L, msg.long("custom_mode"))   // PX4 main mode 4 (AUTO), sub mode 3 (LOITER)
        assertEquals(MavAutopilot.PX4, msg.int("autopilot"))
        assertArrayEquals(payload, frame.copyOfRange(6, 15))
    }
}
