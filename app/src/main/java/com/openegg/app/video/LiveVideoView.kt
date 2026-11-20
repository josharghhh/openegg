package com.openegg.app.video

import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Full-bleed live camera view: black background, video letterboxed with the correct aspect,
 * and a small status line while not playing. Draw HUD content on top of it in the same Box.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveVideoView(player: LiveVideoPlayer, modifier: Modifier = Modifier) {
    val state by player.state.collectAsStateWithLifecycle()

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val surface = SurfaceView(ctx)
                val frame = AspectRatioFrameLayout(ctx).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                player.attachSurface(surface)
                FrameLayout(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                    tag = surface
                }
            },
            update = { root ->
                // Reading `state` here re-runs update when the video size changes.
                val playing = state as? VideoState.Playing
                val aspect = player.displayAspect.takeIf { it > 0f }
                    ?: playing?.let { if (it.height > 0) it.width.toFloat() / it.height else null }
                    ?: (16f / 9f)
                (root.getChildAt(0) as AspectRatioFrameLayout).setAspectRatio(aspect)
            },
            onRelease = { root -> player.detachSurface(root.tag as SurfaceView) },
        )

        state.overlayText()?.let { text ->
            Text(
                text,
                color = Color(0xCCFFFFFF),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Creates a [LiveVideoPlayer] tied to this composition: streams while the screen is STARTED
 * and [active] is true, stops in the background, and is released when it leaves composition.
 */
@Composable
fun rememberLiveVideoPlayer(
    source: VideoSource = VideoSource.POWEREGG,
    active: Boolean = true,
): LiveVideoPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember { LiveVideoPlayer(context, source) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleStartEffect(player, active, source) {
        player.setSource(source)
        if (active) player.start() else player.stop()
        onStopOrDispose { player.stop() }
    }
    return player
}
