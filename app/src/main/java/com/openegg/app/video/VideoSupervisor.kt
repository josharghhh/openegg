package com.openegg.app.video

import kotlin.math.pow

/** Exponential backoff: initial, initial*m, initial*m², … capped at [maxMs]. */
class RetryBackoff(
    val initialMs: Long = 1_000,
    val maxMs: Long = 8_000,
    val multiplier: Double = 2.0,
) {
    init {
        require(initialMs > 0) { "initialMs must be > 0" }
        require(maxMs >= initialMs) { "maxMs must be >= initialMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    var consecutiveFailures: Int = 0
        private set

    /** Delay before the next attempt; each call counts one more failure. */
    fun nextDelayMs(): Long {
        val raw = initialMs * multiplier.pow(consecutiveFailures.coerceAtMost(62))
        if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++
        return if (raw.isNaN() || raw >= maxMs) maxMs else raw.toLong().coerceAtLeast(initialMs)
    }

    fun reset() {
        consecutiveFailures = 0
    }
}

/**
 * Pure state machine behind [LiveVideoPlayer]: no Android, no clocks of its own (callers pass `nowMs`).
 *
 * The player feeds it events; each call returns the [Command] the player should carry out.
 * It also runs a watchdog from [onTick]: a connect that never yields a frame, or a playing stream
 * whose frame counter stops moving, is turned into an error and a backed-off retry. This matters
 * because the drone may not be streaming yet when we connect, and a live RTSP stream that dies
 * quietly just leaves ExoPlayer buffering forever.
 */
class VideoSupervisor(
    private val backoff: RetryBackoff = RetryBackoff(),
    /** Give up on an attempt that has produced no frame by then (covers Media3's UDP→TCP fallback, ~ a few s). */
    val connectTimeoutMs: Long = 15_000,
    /** A playing stream with no new frame for this long is treated as dropped. */
    val stallTimeoutMs: Long = 3_000,
) {
    sealed interface Command {
        /** Nothing to do. */
        data object None : Command

        /** (Re)build the media source and prepare/play it now. */
        data object Connect : Command

        /** Tear the current attempt down now; call [onRetryDue] after [delayMs]. */
        data class ScheduleRetry(val delayMs: Long) : Command

        /** Tear everything down; no retry. */
        data object Disconnect : Command
    }

    var state: VideoState = VideoState.Idle
        private set

    val isRunning: Boolean get() = running

    private var running = false
    private var attempt = 0
    private var phaseStartMs = 0L
    private var lastProgressMs = 0L
    private var lastProgressValue = Long.MIN_VALUE
    private var width = 0
    private var height = 0

    fun start(nowMs: Long): Command {
        if (running) return Command.None
        running = true
        backoff.reset()
        attempt = 0
        return connect(nowMs)
    }

    fun stop(): Command {
        val wasRunning = running
        running = false
        state = VideoState.Idle
        return if (wasRunning) Command.Disconnect else Command.None
    }

    /** Restart immediately (e.g. the source changed), resetting the backoff. */
    fun restart(nowMs: Long): Command {
        if (!running) return Command.None
        backoff.reset()
        attempt = 0
        return connect(nowMs)
    }

    fun onVideoSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        width = widthPx
        height = heightPx
        if (state is VideoState.Playing) state = VideoState.Playing(width, height)
    }

    /** First frame of an attempt reached the screen. */
    fun onFirstFrame(nowMs: Long) {
        if (!running || state !is VideoState.Connecting) return
        backoff.reset()
        state = VideoState.Playing(width, height)
        lastProgressMs = nowMs
    }

    /** Player error, or the stream ended. */
    fun onError(message: String, nowMs: Long): Command {
        if (!running || state is VideoState.Error) return Command.None
        val delay = backoff.nextDelayMs()
        state = VideoState.Error(message, delay)
        phaseStartMs = nowMs
        return Command.ScheduleRetry(delay)
    }

    fun onRetryDue(nowMs: Long): Command {
        if (!running || state !is VideoState.Error) return Command.None
        return connect(nowMs)
    }

    /**
     * Watchdog; call about every 500 ms while running.
     * @param progress any counter that increases while frames arrive (rendered frame count).
     */
    fun onTick(nowMs: Long, progress: Long): Command {
        if (!running) return Command.None
        return when (state) {
            is VideoState.Connecting ->
                if (nowMs - phaseStartMs >= connectTimeoutMs) onError("camera not responding", nowMs) else Command.None
            is VideoState.Playing -> {
                if (progress != lastProgressValue) {
                    lastProgressValue = progress
                    lastProgressMs = nowMs
                    Command.None
                } else if (nowMs - lastProgressMs >= stallTimeoutMs) {
                    onError("stream stalled", nowMs)
                } else {
                    Command.None
                }
            }
            else -> Command.None
        }
    }

    private fun connect(nowMs: Long): Command {
        attempt++
        state = VideoState.Connecting(attempt)
        phaseStartMs = nowMs
        lastProgressMs = nowMs
        lastProgressValue = Long.MIN_VALUE
        return Command.Connect
    }
}
