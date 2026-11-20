package com.openegg.app.video

import com.openegg.app.video.VideoSupervisor.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSupervisorTest {
    @Test fun backoffDoublesUpToCapAndResets() {
        val b = RetryBackoff(initialMs = 1_000, maxMs = 8_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), List(5) { b.nextDelayMs() })
        assertEquals(5, b.consecutiveFailures)
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }

    @Test fun backoffNeverOverflows() {
        val b = RetryBackoff(initialMs = 500, maxMs = 30_000, multiplier = 10.0)
        repeat(200) { assertTrue(b.nextDelayMs() in 500L..30_000L) }
    }

    @Test fun happyPath() {
        val s = VideoSupervisor()
        assertEquals(VideoState.Idle, s.state)
        assertEquals(Command.Connect, s.start(0))
        assertEquals(Command.None, s.start(10)) // idempotent
        assertEquals(VideoState.Connecting(1), s.state)
        s.onVideoSize(1280, 720)
        s.onFirstFrame(800)
        assertEquals(VideoState.Playing(1280, 720), s.state)
        s.onVideoSize(1920, 1080)
        assertEquals(VideoState.Playing(1920, 1080), s.state)
        assertEquals(Command.Disconnect, s.stop())
        assertEquals(VideoState.Idle, s.state)
        assertFalse(s.isRunning)
        assertEquals(Command.None, s.stop())
    }

    @Test fun errorsRetryWithBackoffUntilFirstFrame() {
        val s = VideoSupervisor(RetryBackoff(1_000, 8_000))
        s.start(0)
        assertEquals(Command.ScheduleRetry(1_000), s.onError("can't reach camera", 100))
        assertEquals(VideoState.Error("can't reach camera", 1_000), s.state)
        assertEquals(Command.None, s.onError("duplicate", 150)) // one retry per failure
        assertEquals(Command.Connect, s.onRetryDue(1_100))
        assertEquals(VideoState.Connecting(2), s.state)
        assertEquals(Command.ScheduleRetry(2_000), s.onError("e", 1_200))
        s.onRetryDue(3_200)
        assertEquals(Command.ScheduleRetry(4_000), s.onError("e", 3_300))
        s.onRetryDue(7_300)
        s.onFirstFrame(7_500) // success resets the backoff
        assertEquals(Command.ScheduleRetry(1_000), s.onError("stream ended", 9_000))
    }

    @Test fun connectWatchdogTimesOut() {
        val s = VideoSupervisor(RetryBackoff(1_000, 8_000), connectTimeoutMs = 15_000)
        s.start(0)
        assertEquals(Command.None, s.onTick(14_999, 0))
        assertEquals(Command.ScheduleRetry(1_000), s.onTick(15_000, 0))
        assertEquals(VideoState.Error("camera not responding", 1_000), s.state)
    }

    @Test fun stallWatchdogDetectsDroppedStream() {
        val s = VideoSupervisor(RetryBackoff(1_000, 8_000), stallTimeoutMs = 3_000)
        s.start(0)
        s.onVideoSize(1280, 720)
        s.onFirstFrame(1_000)
        assertEquals(Command.None, s.onTick(1_500, 10))
        assertEquals(Command.None, s.onTick(2_000, 25))
        assertEquals(Command.None, s.onTick(4_500, 25)) // 2.5 s without a frame
        assertEquals(Command.ScheduleRetry(1_000), s.onTick(5_000, 25))
        assertEquals("stream stalled", (s.state as VideoState.Error).message)
    }

    @Test fun eventsIgnoredWhenStopped() {
        val s = VideoSupervisor()
        assertEquals(Command.None, s.onError("x", 0))
        assertEquals(Command.None, s.onRetryDue(0))
        assertEquals(Command.None, s.onTick(100_000, 0))
        s.onFirstFrame(0)
        assertEquals(VideoState.Idle, s.state)

        s.start(0)
        s.onError("x", 10)
        s.stop()
        assertEquals(Command.None, s.onRetryDue(2_000)) // a late retry after stop does nothing
        assertEquals(VideoState.Idle, s.state)
    }

    @Test fun restartResetsAttemptsAndBackoff() {
        val s = VideoSupervisor(RetryBackoff(1_000, 8_000))
        assertEquals(Command.None, s.restart(0)) // not running
        s.start(0)
        s.onError("a", 1); s.onRetryDue(2); s.onError("b", 3)
        assertEquals(Command.Connect, s.restart(4))
        assertEquals(VideoState.Connecting(1), s.state)
        assertEquals(Command.ScheduleRetry(1_000), s.onError("c", 5))
    }
}
