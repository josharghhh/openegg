package com.openegg.app.vxfly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VxFrameTest {

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    // ---- encoding, worked frames from docs/07 §2.6 ----

    @Test fun encodesLogin() = assertArrayEquals(hex("1C 00"), VxFrame(VxCmd.LOGIN).encode())
    @Test fun encodesKeepalive() = assertArrayEquals(hex("00 00"), VxFrame(VxCmd.KEEPALIVE).encode())
    @Test fun encodesGetBattery() = assertArrayEquals(hex("10 00"), VxFrame(VxCmd.GET_BATTERY_VOLTAGE).encode())
    @Test fun encodesGetRadioStatus() = assertArrayEquals(hex("1F 00"), VxFrame(VxCmd.GET_RADIO_STATUS).encode())
    @Test fun encodesGetSwVersion() = assertArrayEquals(hex("1E 00"), VxFrame(VxCmd.GET_SW_VERSION).encode())
    @Test fun encodesSetDlChannel12() = assertArrayEquals(hex("14 02 31 32"), VxFrame.int(VxCmd.SET_DL_CHANNEL, 12).encode())
    @Test fun encodesApplyDlConfig() = assertArrayEquals(hex("1B 00"), VxFrame(VxCmd.APPLY_DL_CONFIG).encode())
    @Test fun encodesSetVideoRate3() = assertArrayEquals(hex("1A 01 33"), VxFrame.int(VxCmd.SET_VIDEO_RATE, 3).encode())

    // ---- decoding ----

    @Test
    fun decodesBatteryReply() {
        val f = VxFrameDecoder().feed(hex("10 05 33 2E 39 31 32")).single()
        assertEquals(VxCmd.GET_BATTERY_VOLTAGE, f.cmd)
        assertEquals("3.912", f.text)
        assertFalse(f.isFailed)
    }

    @Test
    fun decodesRadioStatusReply() {
        val f = VxFrameDecoder().feed(hex("1F 0A 2D 36 32 2C 32 38 2C 2D 39 35")).single()
        assertEquals("-62,28,-95", f.text)
        val (rssi, snr, noise) = VxSignal.parseRadioStatus(f.text)!!
        assertEquals(-62, rssi); assertEquals(28, snr); assertEquals(-95, noise)
        assertEquals(5, VxSignal.barsFromSnr(snr))
    }

    @Test
    fun successReplyIsNotFailed() {
        val f = VxFrameDecoder().feed(hex("14 07 53 55 43 43 45 53 53")).single()
        assertEquals("SUCCESS", f.text)
        assertFalse(f.isFailed)
    }

    @Test
    fun failedIsCaseInsensitiveAndIgnoresNulAndWhitespace() {
        assertTrue(VxFrame.text(0x14, "FAILED").isFailed)
        assertTrue(VxFrame.text(0x14, "failed").isFailed)
        assertTrue(VxFrame(0x14, "FAILED\u0000".toByteArray()).isFailed)
        assertTrue(VxFrame.text(0x14, "FAILED\r\n").isFailed)
        assertFalse(VxFrame.text(0x14, "FAILED2").isFailed)
        assertFalse(VxFrame(0x14).isFailed)
    }

    @Test
    fun roundTripsEveryLength() {
        for (len in listOf(0, 1, 2, 100, 255)) {
            val f = VxFrame(0x42, ByteArray(len) { ('a' + it % 26).code.toByte() })
            assertEquals(f, VxFrameDecoder().feed(f.encode()).single())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlongPayload() {
        VxFrame(0x10, ByteArray(256))
    }

    @Test
    fun decodesByteByByte() {
        val stream = hex("1C 00 10 05 33 2E 39 31 32 00 00 1F 0A 2D 36 32 2C 32 38 2C 2D 39 35")
        val d = VxFrameDecoder()
        val out = stream.flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(listOf(0x1C, 0x10, 0x00, 0x1F), out.map { it.cmd })
        assertEquals("3.912", out[1].text)
        assertEquals("-62,28,-95", out[3].text)
        assertEquals(0, d.pendingBytes)
    }

    @Test
    fun decodesMergedReadAndKeepsTail() {
        val d = VxFrameDecoder()
        // login reply + keepalive echo + first 3 bytes of a battery reply in one read
        val first = d.feed(hex("1C 00 00 00 10 05 33"))
        assertEquals(listOf(0x1C, 0x00), first.map { it.cmd })
        assertEquals(3, d.pendingBytes)
        val second = d.feed(hex("2E 39 31 32 1F"))
        assertEquals("3.912", second.single().text)
        assertEquals(1, d.pendingBytes) // header split: only the cmd byte of the next frame
        assertEquals("-55", d.feed(hex("03 2D 35 35")).single().text)
    }

    @Test
    fun honoursOffsetAndLength() {
        val buf = hex("FF FF 10 05 33 2E 39 31 32 EE")
        assertEquals("3.912", VxFrameDecoder().feed(buf, 2, 7).single().text)
    }

    @Test
    fun dangerousAndReadOnlySetsAreDisjointAndCoverDocSection5() {
        assertTrue((VxCmd.DANGEROUS intersect VxCmd.READ_ONLY).isEmpty())
        // docs/07 §5: do not send 14/1B, 1A, 04/06/09, 07/0B, 11 casually
        for (c in listOf(0x14, 0x1B, 0x1A, 0x04, 0x06, 0x09, 0x07, 0x0B, 0x11)) assertTrue(c in VxCmd.DANGEROUS)
    }
}
