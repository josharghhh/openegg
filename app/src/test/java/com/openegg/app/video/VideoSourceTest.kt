package com.openegg.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {
    @Test fun defaultUrlMatchesTheOriginalApp() {
        assertEquals(VideoSource.POWEREGG_URL, VideoSource.POWEREGG.url)
        assertEquals("rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264", VideoSource().url)
        assertFalse(VideoSource.POWEREGG.forceTcp)
    }

    @Test fun buildsUrlFromParts() {
        assertEquals("rtsp://10.0.0.5:554/live", VideoSource("10.0.0.5", 554, "live", null).url)
        assertEquals("rtsp://h:1/a?b=c", VideoSource("h", 1, "/a", "?b=c").url)
        assertEquals("rtsp://h:1/a", VideoSource("h", 1, "/a", "").url)
        assertEquals("rtsp://[fe80::1]:8557/x", VideoSource("fe80::1", 8557, "/x", null).url)
    }

    @Test fun parseRoundTrips() {
        val s = VideoSource.parse(VideoSource.POWEREGG_URL)
        assertEquals(VideoSource.POWEREGG, s)
        assertEquals(VideoSource.POWEREGG_URL, s.url)
    }

    @Test fun parseHandlesDefaultsAndOddForms() {
        val a = VideoSource.parse("rtsp://cam.local/stream", forceTcp = true)
        assertEquals("cam.local", a.host)
        assertEquals(554, a.port)
        assertEquals("/stream", a.path)
        assertNull(a.query)
        assertTrue(a.forceTcp)

        val b = VideoSource.parse("  RTSP://user:pw@1.2.3.4:9000  ")
        assertEquals("1.2.3.4", b.host)
        assertEquals(9000, b.port)
        assertEquals("/", b.path)

        val c = VideoSource.parse("rtsp://[::1]:8557/p?q=1")
        assertEquals("::1", c.host)
        assertEquals(8557, c.port)
        assertEquals("q=1", c.query)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonRtsp() {
        VideoSource.parse("http://192.168.1.240/")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadPort() {
        VideoSource("h", 70000)
    }

    @Test fun overlayText() {
        assertEquals("Connecting to camera…", VideoState.Connecting().overlayText())
        assertEquals("Connecting to camera… (attempt 3)", VideoState.Connecting(3).overlayText())
        assertNull(VideoState.Playing(1280, 720).overlayText())
        assertEquals("No video: can't reach camera — retrying in 2 s", VideoState.Error("can't reach camera", 2_000).overlayText())
        assertEquals("No video: x — retrying in 1.5 s", VideoState.Error("x", 1_500).overlayText())
    }
}
