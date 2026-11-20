package com.openegg.app.link

import com.openegg.app.link.probe.HexDump
import com.openegg.app.link.probe.MavlinkCapture
import com.openegg.app.link.probe.MavlinkProbeBytes
import com.openegg.app.link.probe.VxFlyProtocol
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.PowerVisionDialect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeBytesTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun paramRequestReadMatchesOriginalSdkFrame() {
        // doc 04 §4: the SDK's PARAM_REQUEST_READ for PV_RO_KEY_0 (seq 0, target 1/1, index -1).
        val expected = hex("FE 14 00 FF BE 14 FF FF 01 01 50 56 5F 52 4F 5F 4B 45 59 5F 30 00 00 00 00 00 E3 15")
        val codec = MavlinkProbeBytes.newCodec()
        assertArrayEquals(expected, MavlinkProbeBytes.paramRequestRead(codec, 1, 1, "PV_RO_KEY_0"))
    }

    @Test
    fun gcsHeartbeatHasOriginalFields() {
        val codec = MavlinkProbeBytes.newCodec()
        val hb = MavlinkProbeBytes.gcsHeartbeat(codec)
        assertEquals(0xFE, hb[0].toInt() and 0xFF)
        assertEquals(9, hb[1].toInt())
        assertEquals(255, hb[3].toInt() and 0xFF)
        assertEquals(190, hb[4].toInt() and 0xFF)
        assertEquals(0, MavlinkProbeBytes.messageIdOf(hb))
        val frames = PowerVisionDialect.detector().feed(hb)
        assertEquals(1, frames.size)
        assertEquals(true, frames[0].crcValid)
        val msg = MavlinkCodec(PowerVisionDialect.dialect).decode(frames[0])!!
        assertEquals(6, msg.int("type"))
        assertEquals(0, msg.int("autopilot"))
        assertEquals(0x81, msg.int("base_mode"))
        assertEquals(0L, msg.long("custom_mode"))
        assertEquals(3, msg.int("mavlink_version"))
    }

    @Test
    fun mavlinkProbeOnlyTransmitsHeartbeatAndParamRead() {
        assertEquals(setOf(0, 20), MavlinkProbeBytes.TRANSMIT_ALLOWLIST)
        val codec = MavlinkProbeBytes.newCodec()
        val req = MavlinkProbeBytes.paramRequestRead(codec, 1, 1)
        assertEquals(20, MavlinkProbeBytes.messageIdOf(req))
        assertEquals("PV_V_KEY_FLAG", String(req, 10, 13, Charsets.US_ASCII))
    }

    @Test
    fun captureFindsPx4VehicleAndParamReply() {
        val vehicle = MavlinkCodec(PowerVisionDialect.dialect, systemId = 1, componentId = 1)
        val rc = MavlinkCodec(PowerVisionDialect.dialect, systemId = 1, componentId = 6)
        val bytes = rc.encode("HEARTBEAT", mapOf("type" to 2, "autopilot" to 0, "base_mode" to 0, "custom_mode" to 0L, "system_status" to 4, "mavlink_version" to 3)) +
            vehicle.encode("HEARTBEAT", mapOf("type" to 2, "autopilot" to 12, "base_mode" to 0x51, "custom_mode" to 0x00030000L, "system_status" to 3, "mavlink_version" to 3)) +
            vehicle.encode("STATUSTEXT", mapOf("severity" to 6, "text" to "ready")) +
            vehicle.encode("PARAM_VALUE", mapOf("param_id" to "PV_V_KEY_FLAG", "param_value" to Float.fromBits(0), "param_type" to 6, "param_count" to 300, "param_index" to 12))
        val cap = MavlinkCapture()
        PowerVisionDialect.detector().feed(bytes).forEach { cap.add(it, 100) }
        assertEquals(4, cap.verifiedFrames)
        assertEquals(1, cap.vehicle!!.componentId)
        assertEquals(12, cap.vehicle!!.autopilot)
        assertEquals("standard RC (compid 6)", cap.heartbeats[1 to 6]!!.role)
        assertTrue(cap.summary().contains("PV_V_KEY_FLAG=0x00000000"))
        assertTrue(cap.summary().contains("Position"))
        val lines = cap.lines()
        assertTrue(lines.any { it.contains("NOT activated") })
        assertTrue(lines.any { it.contains("[sev 6] ready") })
        assertTrue(lines.any { it.contains("HEARTBEAT") && it.contains("x2") })
    }

    @Test
    fun vxflyFramesAreTwoBytesAndForbiddenCommandsThrow() {
        assertArrayEquals(hex("1C 00"), VxFlyProtocol.frame(VxFlyProtocol.LOGIN))
        assertArrayEquals(hex("00 00"), VxFlyProtocol.frame(VxFlyProtocol.KEEPALIVE))
        assertArrayEquals(hex("10 00"), VxFlyProtocol.frame(VxFlyProtocol.GET_BATTERY))
        assertArrayEquals(hex("1F 00"), VxFlyProtocol.frame(VxFlyProtocol.GET_RADIO_STATUS))
        for (cmd in VxFlyProtocol.FORBIDDEN) {
            assertTrue("0x%02x must be refused".format(cmd), runCatching { VxFlyProtocol.frame(cmd) }.isFailure)
        }
    }

    @Test
    fun vxflyPlanStartsWithLoginAndNeverTouchesConfig() {
        for (extra in listOf(VxFlyProtocol.GROUND_EXTRA, VxFlyProtocol.AIR_EXTRA)) {
            val plan = VxFlyProtocol.plan(extra, 4500)
            assertEquals(listOf(0x1C, 0x00, 0x10, 0x1F), plan.take(4).map { it.cmd })
            assertEquals(0L, plan.first().atMs)
            assertTrue(plan.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })
            assertTrue(plan.all { it.atMs < 4500 })
            assertFalse(plan.any { it.cmd in VxFlyProtocol.FORBIDDEN })
            assertTrue(plan.drop(4 + extra.size).all { it.cmd == VxFlyProtocol.KEEPALIVE })
        }
    }

    @Test
    fun vxflyDecoderSplitsAndInterpretsReplies() {
        val d = VxFlyProtocol.Decoder()
        val stream = hex("10 05 33 2E 39 31 32 1F 0A 2D 36 32 2C 32 38 2C 2D 39 35 1C")
        val frames = d.feed(stream)
        assertEquals(2, frames.size)
        assertEquals(1, d.leftover)
        assertEquals("3.912", frames[0].text)
        assertTrue(VxFlyProtocol.interpret(frames[0]), VxFlyProtocol.interpret(frames[0]).contains("3.912 V"))
        assertTrue(VxFlyProtocol.interpret(frames[1]).contains("snr 28"))
        // Second half of the split frame.
        val more = d.feed(hex("07 53 55 43 43 45 53 53"))
        assertEquals("SUCCESS", more.single().text)
        assertEquals(0x1C, more.single().cmd)
    }

    @Test
    fun batteryCurveMatchesNativeTable() {
        assertEquals(0.0, VxFlyProtocol.batteryPercent(3300), 0.0)
        assertEquals(100.0, VxFlyProtocol.batteryPercent(4100), 0.0)
        assertEquals(89.78, VxFlyProtocol.batteryPercent(3900), 1e-9)
        // doc 07 §2.6: 3.912 V → 91.06 % natively; interpolation is within ~1 %.
        assertEquals(91.06, VxFlyProtocol.batteryPercent(3912), 1.0)
    }

    @Test
    fun hexDumpTruncates() {
        val bytes = ByteArray(40) { (it + 0x41).toByte() }
        val lines = HexDump.dump(bytes, maxBytes = 20, indent = "")
        assertEquals(3, lines.size)
        assertEquals("0000  41 42 43 44 45 46 47 48 49 4a 4b 4c 4d 4e 4f 50  |ABCDEFGHIJKLMNOP|", lines[0])
        assertTrue(lines[1].startsWith("0010  51 52 53 54 "))
        assertTrue(lines[1].endsWith("|QRST|"))
        assertEquals("... 20 more bytes not shown (40 total)", lines[2])
        assertEquals("fe 00 7f", HexDump.hex(byteArrayOf(0xFE.toByte(), 0, 0x7F)))
        assertEquals("A..", HexDump.ascii(byteArrayOf(0x41, 0, 0x0A)))
    }
}
