package com.openegg.app.link

import com.openegg.app.link.probe.ProbeReport
import com.openegg.app.link.probe.ProbeSection
import com.openegg.app.link.probe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ProbeReportTest {

    private val time = ZonedDateTime.of(2026, 11, 20, 14, 3, 22, 0, ZoneOffset.ofHours(10))

    private val header = ProbeReport.Header(
        time = time,
        app = "OpenEgg 0.1.0 (1)",
        device = "samsung SM-S938B",
        android = "16 (API 36)",
        network = listOf("base-station Wi-Fi bound=yes local=192.168.1.105 gateway=192.168.1.1", "wifi rssi=-40 dBm"),
        runMs = 14_200,
    )

    @Test
    fun fileNameIsTimestamped() {
        assertEquals("openegg-linktest-20261120-140322.txt", ProbeReport.fileName(time))
    }

    @Test
    fun reportHasHeaderSummaryAndDetails() {
        val sections = listOf(
            ProbeSection("MAVLink", "Ground station", "192.168.1.12:20002", Verdict.FOUND, "MAVLink v1 · vehicle sys1/comp1",
                sent = listOf("t+0.10s HEARTBEAT ..."), details = listOf("Heartbeat sources:", "  sys=1 comp=1"), elapsedMs = 5600),
            ProbeSection("RTSP", "Camera RTSP 554", "192.168.1.10:554", Verdict.NO_ANSWER, "connect timed out", elapsedMs = 2000),
        )
        val text = ProbeReport.format(header, sections)
        assertTrue(text.startsWith("==== OpenEgg link test ===="))
        assertTrue(text.contains("Time:    2026-11-20 14:03:22 +10:00  (run took 14.2 s)"))
        assertTrue(text.contains("Device:  samsung SM-S938B"))
        assertTrue(text.contains("Android: 16 (API 36)"))
        assertTrue(text.contains("Network: base-station Wi-Fi bound=yes local=192.168.1.105 gateway=192.168.1.1"))
        assertTrue(text.contains("         wifi rssi=-40 dBm"))
        assertTrue(text.contains("SUMMARY  (1 of 2 endpoints answered with their protocol)"))
        assertTrue(text.contains("  [FOUND]     MAVLink 192.168.1.12:20002  MAVLink v1 · vehicle sys1/comp1"))
        assertTrue(text.contains("  [NO ANSWER] RTSP    192.168.1.10:554    connect timed out"))
        assertTrue(text.contains("--- MAVLink · Ground station · 192.168.1.12:20002 · FOUND · 5.6 s"))
        assertTrue(text.contains("Sent:\n  t+0.10s HEARTBEAT ..."))
        assertTrue(text.contains("Details:\n  Heartbeat sources:\n    sys=1 comp=1"))
        assertTrue(text.trimEnd().endsWith("==== end of report ===="))
    }

    @Test
    fun longDetailsAreCut() {
        val s = ProbeSection("UDP", "Video", "udp:60430", Verdict.OPEN, "lots", details = List(500) { "line $it" })
        val text = ProbeReport.format(header.copy(runMs = null), listOf(s))
        assertTrue(text.contains("line ${ProbeReport.MAX_DETAIL_LINES - 1}\n"))
        assertFalse(text.contains("line ${ProbeReport.MAX_DETAIL_LINES}\n"))
        assertTrue(text.contains("… ${500 - ProbeReport.MAX_DETAIL_LINES} more lines cut"))
        assertFalse(text.contains("run took"))
    }
}
