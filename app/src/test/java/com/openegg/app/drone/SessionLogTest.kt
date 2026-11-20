package com.openegg.app.drone

import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.PowerVisionDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun heartbeat() = MavMessage(
        PowerVisionDialect.dialect["HEARTBEAT"],
        mapOf("custom_mode" to 0x05040000L, "type" to 2, "autopilot" to 12, "base_mode" to 81, "system_status" to 3, "mavlink_version" to 3),
        systemId = 1, componentId = 1,
    )

    @Test
    fun writesEventsMessagesAndSummary() {
        var now = 0L
        val log = SessionLog(tmp.root, "TEST", clock = { now }, fullDumpsPerId = 2, sampleEveryMs = 1000)
        log.event("LINK", "connected tcp://192.168.1.12:20002")
        repeat(12) { now += 100; log.received(heartbeat()) } // t = 0.1 … 1.2 s
        log.unknownFrame(222, byteArrayOf(1, 2, 3))
        log.close()

        val text = log.file.readText()
        assertTrue(text.contains("LINK    connected tcp://192.168.1.12:20002"))
        // 2 full dumps (t=0.1, 0.2) + 1 sample at t=1.2, once 1 s has passed since the last dump.
        assertEquals(3, text.lines().count { it.contains("RX ") && it.contains("HEARTBEAT") })
        assertTrue(text.contains("custom_mode=84148224"))
        assertTrue(text.contains("unknown id 222 len=3 01 02 03"))
        assertTrue(text.contains("received: 0×12 | unknown: 222×1"))
    }

    @Test
    fun ignoresWritesAfterClose() {
        val log = SessionLog(tmp.root, "x")
        log.close()
        log.event("LATE", "should not appear")
        assertTrue(!log.file.readText().contains("LATE"))
    }
}
