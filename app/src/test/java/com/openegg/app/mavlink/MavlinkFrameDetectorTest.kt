package com.openegg.app.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavlinkFrameDetectorTest {

    @Test
    fun x25MatchesPublishedCheckValue() {
        // CRC-16/MCRF4XX check value for ASCII "123456789" is 0x6F91.
        assertEquals(0x6F91, X25.of("123456789".toByteArray()))
    }

    /** Builds a MAVLink v1 HEARTBEAT the way a PX4 autopilot sends it. */
    private fun heartbeatV1(seq: Int, sys: Int = 1, comp: Int = 1): ByteArray {
        // custom_mode u32, type u8, autopilot u8, base_mode u8, system_status u8, mavlink_version u8
        val payload = byteArrayOf(0, 0, 0, 0, 2, 12, 81.toByte(), 3, 3) // quadrotor, PX4
        val header = byteArrayOf(payload.size.toByte(), seq.toByte(), sys.toByte(), comp.toByte(), 0)
        val crc = X25.of(header + payload, extra = 50)
        return byteArrayOf(0xFE.toByte()) + header + payload + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    @Test
    fun findsHeartbeatInNoisyStream() {
        val d = MavlinkFrameDetector()
        // A false STX (0xFE, len 0x37) precedes real frames; it must not swallow them.
        val stream = byteArrayOf(0x00, 0x13, 0xFE.toByte(), 0x37) +
            heartbeatV1(7) + heartbeatV1(8) + heartbeatV1(9) + heartbeatV1(10) + byteArrayOf(0x55)
        val frames = d.feed(stream).filter { it.crcValid == true }
        assertEquals(listOf(7, 8, 9, 10), frames.map { it.sequence })
        val f = frames[0]
        assertEquals(1, f.version)
        assertEquals(0, f.messageId)
        assertEquals(7, f.sequence)
        assertEquals(true, f.crcValid)
        assertEquals(12, f.payload[5].toInt()) // MAV_AUTOPILOT_PX4
    }

    @Test
    fun reassemblesFrameSplitAcrossReads() {
        val d = MavlinkFrameDetector()
        val hb = heartbeatV1(1)
        assertTrue(d.feed(hb.copyOfRange(0, 5)).isEmpty())
        val frames = d.feed(hb.copyOfRange(5, hb.size))
        assertEquals(1, frames.size)
    }

    @Test
    fun rejectsCorruptedCrc() {
        val d = MavlinkFrameDetector()
        val hb = heartbeatV1(2)
        hb[hb.size - 1] = (hb[hb.size - 1] + 1).toByte()
        assertTrue(d.feed(hb).isEmpty())
    }

    @Test
    fun parsesV2Header() {
        val payload = byteArrayOf(0, 0, 0, 0, 2, 12, 81.toByte(), 3, 3)
        val header = byteArrayOf(payload.size.toByte(), 0, 0, 9, 1, 1, 0, 0, 0)
        val crc = X25.of(header + payload, extra = 50)
        val frame = byteArrayOf(0xFD.toByte()) + header + payload + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        val f = MavlinkFrameDetector().feed(frame).single()
        assertEquals(2, f.version)
        assertEquals(9, f.sequence)
        assertEquals(true, f.crcValid)
    }
}
