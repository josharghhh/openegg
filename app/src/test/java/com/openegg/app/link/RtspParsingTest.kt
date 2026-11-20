package com.openegg.app.link

import com.openegg.app.link.probe.H264Sps
import com.openegg.app.link.probe.H264Track
import com.openegg.app.link.probe.RtspRequests
import com.openegg.app.link.probe.RtspResponse
import com.openegg.app.link.probe.Sdp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Writes H.264 bitstream syntax so the tests can build SPS NAL units with known dimensions. */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var cur = 0
    private var n = 0
    fun u(bits: Int, v: Int) { for (i in bits - 1 downTo 0) bit((v shr i) and 1) }
    fun bit(b: Int) { cur = (cur shl 1) or b; n++; if (n == 8) { out.write(cur); cur = 0; n = 0 } }
    fun ue(v: Int) {
        val x = v + 1
        val len = 32 - Integer.numberOfLeadingZeros(x)
        repeat(len - 1) { bit(0) }
        u(len, x)
    }
    fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)
    /** rbsp_trailing_bits, then emulation prevention. */
    fun finish(): ByteArray {
        bit(1)
        while (n != 0) bit(0)
        return escape(out.toByteArray())
    }
    private fun escape(rbsp: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0 }
            o.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }
}

internal object TestSps {
    /** Baseline 1280x720, no cropping. */
    fun baseline720(): ByteArray = BitWriter().run {
        u(8, 0x67); u(8, 66); u(8, 0xC0); u(8, 31)
        ue(0); ue(0); ue(2); ue(1); bit(0)
        ue(79); ue(44)
        bit(1); bit(1); bit(0); bit(0)
        finish()
    }

    /** High profile 1920x1080 (1088 coded, 8 rows cropped) with a scaling list to exercise that path. */
    fun high1080(): ByteArray = BitWriter().run {
        u(8, 0x67); u(8, 100); u(8, 0); u(8, 40)
        ue(0) // sps id
        ue(1); ue(0); ue(0); bit(0) // chroma 4:2:0, 8-bit, no bypass
        bit(1) // scaling matrix present
        bit(1); se(8); repeat(15) { se(0) } // list 0 present
        repeat(7) { bit(0) }
        ue(0); ue(0); ue(2) // log2_max_frame_num, poc type 0, log2_max_poc_lsb
        ue(4); bit(0)
        ue(119); ue(67)
        bit(1); bit(1)
        bit(1); ue(0); ue(0); ue(0); ue(4) // crop bottom 4 * 2 rows
        bit(0)
        finish()
    }

    val PPS = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
}

class RtspParsingTest {

    @Test
    fun requestsCarryCseqUserAgentAndAccept() {
        val d = RtspRequests.describe(RtspRequests.VXFLY_URL, 2)
        assertEquals(
            "DESCRIBE rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264 RTSP/1.0\r\n" +
                "CSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: OpenEgg\r\n\r\n",
            d,
        )
        val o = RtspRequests.options("rtsp://192.168.1.10:554/", 1)
        assertEquals("OPTIONS rtsp://192.168.1.10:554/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: OpenEgg\r\n\r\n", o)
    }

    @Test
    fun parsesResponseIncrementallyWithBody() {
        val body = "v=0\r\ns=test\r\n"
        val full = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val bytes = full.toByteArray()
        // Incomplete header, then incomplete body: need more bytes.
        assertNull(RtspResponse.tryParse(bytes, 20))
        assertNull(RtspResponse.tryParse(bytes, bytes.size - 3))
        val (r, used) = RtspResponse.tryParse(bytes)!!
        assertEquals(bytes.size, used)
        assertEquals(200, r.code)
        assertEquals("OK", r.reason)
        assertEquals("2", r.header("cseq"))
        assertEquals(body, r.body)
    }

    @Test
    fun parsesErrorStatusAndRejectsNonRtsp() {
        val (r, _) = RtspResponse.tryParse("RTSP/1.0 404 Stream Not Found\r\nCSeq: 3\r\n\r\n".toByteArray())!!
        assertEquals(404, r.code)
        assertEquals("Stream Not Found", r.reason)
        val http = "HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()
        assertTrue(runCatching { RtspResponse.tryParse(http) }.isFailure)
    }

    @Test
    fun unescapeRemovesEmulationPreventionBytes() {
        val escaped = byteArrayOf(0x67, 0, 0, 3, 1, 0, 0, 3, 0, 5)
        assertArrayEquals(byteArrayOf(0x67, 0, 0, 1, 0, 0, 0, 5), H264Sps.unescape(escaped))
    }

    @Test
    fun decodesBaseline720pSps() {
        val info = H264Sps.parse(TestSps.baseline720())!!
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals(66, info.profileIdc)
        assertEquals(31, info.levelIdc)
        assertTrue(info.frameMbsOnly)
    }

    @Test
    fun decodesHighProfile1080pSpsWithScalingListAndCropping() {
        val info = H264Sps.parse(TestSps.high1080())!!
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(100, info.profileIdc)
        assertEquals(40, info.levelIdc)
    }

    @Test
    fun garbageSpsReturnsNull() {
        assertNull(H264Sps.parse(byteArrayOf(0x68, 1, 2)))
        assertNull(H264Sps.parse(byteArrayOf(0x67, 100)))
    }

    @Test
    fun parsesSdpAndExtractsH264Track() {
        val b64 = Base64.getEncoder()
        val sdpText = listOf(
            "v=0",
            "o=- 1 1 IN IP4 192.168.1.240",
            "s=Session streamed by \"VFLive\"",
            "t=0 0",
            "a=control:*",
            "a=range:npt=0-",
            "m=audio 0 RTP/AVP 97",
            "a=rtpmap:97 MPEG4-GENERIC/48000/2",
            "m=video 0 RTP/AVP 96",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=" +
                b64.encodeToString(TestSps.baseline720()) + "," + b64.encodeToString(TestSps.PPS),
            "a=framerate:30",
            "a=control:track1",
        ).joinToString("\r\n", postfix = "\r\n")
        val sdp = Sdp.parse(sdpText)
        assertEquals("Session streamed by \"VFLive\"", sdp.sessionName)
        assertEquals(listOf("control" to "*", "range" to "npt=0-"), sdp.sessionAttributes)
        assertEquals(2, sdp.media.size)
        val video = sdp.media[1]
        assertEquals("video", video.type)
        assertEquals("RTP/AVP", video.proto)
        assertEquals("H264/90000", video.rtpmap["96"])
        assertEquals("1", video.fmtp["96"]!!["packetization-mode"])

        assertNull(H264Track.from(sdp.media[0]))
        val track = H264Track.from(video)!!
        assertEquals("96", track.payloadType)
        assertEquals("42C01F", track.profileLevelId)
        assertEquals("track1", track.control)
        assertArrayEquals(TestSps.PPS, track.pps)
        assertNotNull(track.spsInfo)
        assertEquals(1280, track.spsInfo!!.width)
        assertEquals(listOf("a=framerate:30"), track.hints)
        assertTrue(track.describe().any { it.contains("1280x720") })
    }
}
