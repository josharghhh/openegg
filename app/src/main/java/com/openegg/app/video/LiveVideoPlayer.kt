package com.openegg.app.video

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * Low-latency live view of the PowerEgg camera: ExoPlayer + RtspMediaSource, video only,
 * UDP first (Media3 falls back to interleaved TCP by itself if no UDP packets arrive or the
 * server rejects UDP), with automatic backed-off reconnects driven by [VideoSupervisor].
 *
 * Networking: Media3 opens its RTP UDP sockets with no Network hook, so the process must be
 * bound to the base-station Wi-Fi (`ConnectivityManager.bindProcessToNetwork`, done by
 * DroneNetwork). [socketFactory] can additionally pin the RTSP TCP control socket.
 *
 * All methods must be called on the main thread. Create, [start], [stop], and finally [release].
 */
@OptIn(UnstableApi::class)
@MainThread
class LiveVideoPlayer(
    context: Context,
    source: VideoSource = VideoSource.POWEREGG,
    private val socketFactory: SocketFactory? = null,
    private val supervisor: VideoSupervisor = VideoSupervisor(),
    private val debugLogging: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val frames = AtomicLong()
    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    private var released = false
    private var surfaceView: SurfaceView? = null

    /** Current state; [VideoState.Playing] carries the decoded size from the SPS. */
    val state: StateFlow<VideoState> = _state.asStateFlow()

    var source: VideoSource = source
        private set

    /** Display aspect ratio (width/height incl. pixel aspect), or 0 if unknown. Read by [LiveVideoView]. */
    internal var displayAspect: Float = 0f
        private set

    internal val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLooper(Looper.getMainLooper())
        .setRenderersFactory(DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ MIN_BUFFER_MS,
                    /* maxBufferMs = */ MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            volume = 0f
            playWhenReady = true
            setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
            addListener(PlayerListener())
        }

    private val tick = object : Runnable {
        override fun run() {
            if (released || !supervisor.isRunning) return
            execute(supervisor.onTick(now(), frames.get()))
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val retryDue = Runnable { execute(supervisor.onRetryDue(now())) }

    /** Start (or keep) streaming; safe to call repeatedly. */
    fun start() {
        check(!released) { "LiveVideoPlayer released" }
        if (supervisor.isRunning) return
        execute(supervisor.start(now()))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    /** Stop streaming and go [VideoState.Idle]; [start] again to resume. */
    fun stop() {
        if (released) return
        execute(supervisor.stop())
    }

    /** Switch URL / transport; reconnects immediately if running. */
    fun setSource(newSource: VideoSource) {
        if (released || newSource == source) return
        source = newSource
        execute(supervisor.restart(now()))
    }

    /** Convenience for [setSource] with only the transport changed. */
    fun setForceTcp(forceTcp: Boolean) = setSource(source.copy(forceTcp = forceTcp))

    /** Releases the decoder and sockets. The instance cannot be used afterwards. */
    fun release() {
        if (released) return
        supervisor.stop()
        handler.removeCallbacksAndMessages(null)
        surfaceView = null
        player.release()
        released = true
        publish()
    }

    /**
     * Copies the frame currently on screen, or null if nothing is playing / no view is attached.
     * Uses PixelCopy on the SurfaceView, so it reflects exactly what the pilot sees.
     */
    suspend fun snapshot(): Bitmap? {
        val view = surfaceView ?: return null
        val playing = _state.value as? VideoState.Playing ?: return null
        val holder = view.holder
        if (!holder.surface.isValid) return null
        val w = if (playing.width > 0) playing.width else view.width
        val h = if (playing.height > 0) playing.height else view.height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(view, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bitmap) else {
                        bitmap.recycle()
                        cont.resume(null)
                    }
                }, handler)
            } catch (e: IllegalArgumentException) { // surface went away
                bitmap.recycle()
                cont.resume(null)
            }
        }
    }

    internal fun attachSurface(view: SurfaceView) {
        if (released) return
        surfaceView = view
        player.setVideoSurfaceView(view)
    }

    internal fun detachSurface(view: SurfaceView) {
        if (surfaceView === view) surfaceView = null
        if (!released) player.clearVideoSurfaceView(view)
    }

    private fun execute(command: VideoSupervisor.Command) {
        if (released) return
        when (command) {
            VideoSupervisor.Command.None -> Unit
            VideoSupervisor.Command.Connect -> connect()
            is VideoSupervisor.Command.ScheduleRetry -> {
                player.stop()
                handler.removeCallbacks(retryDue)
                handler.postDelayed(retryDue, command.delayMs)
            }
            VideoSupervisor.Command.Disconnect -> {
                handler.removeCallbacks(retryDue)
                handler.removeCallbacks(tick)
                player.stop()
                player.clearMediaItems()
            }
        }
        publish()
    }

    private fun connect() {
        handler.removeCallbacks(retryDue)
        frames.set(0)
        val factory = RtspMediaSource.Factory()
            .setUserAgent(VideoSource.USER_AGENT)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .setForceUseRtpTcp(source.forceTcp)
            .setDebugLoggingEnabled(debugLogging)
        socketFactory?.let(factory::setSocketFactory)
        player.stop()
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(source.url)))
        player.prepare()
        player.playWhenReady = true
        if (debugLogging) Log.d(TAG, "connect ${source.url} tcp=${source.forceTcp}")
    }

    private fun publish() {
        _state.value = if (released) VideoState.Idle else supervisor.state
    }

    private inner class PlayerListener : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            displayAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            supervisor.onVideoSize(videoSize.width, videoSize.height)
            publish()
        }

        override fun onRenderedFirstFrame() {
            supervisor.onFirstFrame(now())
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) execute(supervisor.onError("stream ended", now()))
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "video error ${error.errorCodeName}", error)
            execute(supervisor.onError(describe(error), now()))
        }
    }

    companion object {
        private const val TAG = "LiveVideo"
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 1_000
        const val BUFFER_FOR_PLAYBACK_MS = 150
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 250
        const val RTSP_TIMEOUT_MS = 5_000L
        private const val TICK_MS = 500L

        private fun now() = SystemClock.elapsedRealtime()

        /** Short, pilot-readable reason. */
        internal fun describe(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "can't reach camera"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> "camera timed out"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "camera refused stream"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "bad stream data"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "decoder error"
            else -> (error.cause?.message ?: error.message ?: error.errorCodeName).take(60)
        }
    }
}
