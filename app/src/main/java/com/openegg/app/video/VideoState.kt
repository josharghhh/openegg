package com.openegg.app.video

/** What the live video is doing, for the UI. */
sealed interface VideoState {
    /** Not started, or stopped. */
    data object Idle : VideoState

    /** RTSP handshake in progress / waiting for the first frame. [attempt] counts from 1. */
    data class Connecting(val attempt: Int = 1) : VideoState

    /** Frames are being rendered. Size comes from the stream's SPS. */
    data class Playing(val width: Int, val height: Int) : VideoState

    /** The last attempt failed; another starts in [retryInMs] (measured from when this state was entered). */
    data class Error(val message: String, val retryInMs: Long) : VideoState
}

/** Short overlay text for a non-playing state, or null when video is showing. */
fun VideoState.overlayText(): String? = when (this) {
    VideoState.Idle -> "Camera off"
    is VideoState.Connecting -> if (attempt <= 1) "Connecting to camera…" else "Connecting to camera… (attempt $attempt)"
    is VideoState.Playing -> null
    is VideoState.Error -> "No video: $message — retrying in ${formatSeconds(retryInMs)}"
}

private fun formatSeconds(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    return if (tenths % 10 == 0L) "${tenths / 10} s" else "${tenths / 10}.${tenths % 10} s"
}
