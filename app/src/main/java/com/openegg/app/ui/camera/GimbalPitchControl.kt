package com.openegg.app.ui.camera

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openegg.app.camera.CameraSettingsRepository.Nudge
import com.openegg.app.camera.GimbalNudges
import com.openegg.app.ui.flight.Caption
import com.openegg.app.ui.flight.FlightColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Spring-loaded vertical stick for gimbal pitch. Dragging the thumb away from the centre
 * reports a rate (the original gesture magnitudes, see [Nudge]); releasing springs back and
 * reports 0. [onRateChange] is called only when the rate changes, so it can go straight to
 * [com.openegg.app.camera.CameraSettingsRepository.holdPitchRate].
 *
 * Thumb up sends what an upward drag sent in the original app (negative pitch). Which way the
 * camera physically tilts for that sign is unverified.
 */
@Composable
fun GimbalPitchControl(
    pitchDeg: Float?,
    onRateChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 180.dp,
    trackWidth: Dp = 44.dp,
) {
    val rateCallback by rememberUpdatedState(onRateChange)
    val scope = rememberCoroutineScope()
    var deflection by remember { mutableFloatStateOf(0f) } // -1 (up) .. +1 (down)
    var lastRate by remember { mutableIntStateOf(0) }
    var springBack by remember { mutableStateOf<Job?>(null) }
    val halfTravelPx = with(LocalDensity.current) { ((trackHeight - trackWidth) / 2).toPx() }
    val thumbSize = trackWidth - 8.dp

    fun report(deflection: Float) {
        val rate = GimbalNudges.forDeflection(deflection).pitchRate
        if (rate != lastRate) { lastRate = rate; rateCallback(rate) }
    }

    fun release() {
        report(0f)
        springBack = scope.launch { animate(deflection, 0f, animationSpec = spring()) { v, _ -> deflection = v } }
    }

    fun tap(n: Nudge) = scope.launch {
        report(if (n.pitchRate < 0) -0.3f else 0.3f)
        delay(400)
        report(0f)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Caption(pitchDeg?.let { "%+.0f°".format(it) } ?: "--°", Modifier.padding(bottom = 4.dp))
        Box(
            Modifier
                .size(trackWidth, trackHeight)
                .clip(RoundedCornerShape(50))
                .background(FlightColors.panel)
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .semantics {
                    contentDescription = "Gimbal pitch" + (pitchDeg?.let { ", ${it.roundToInt()} degrees" } ?: "")
                    if (enabled) customActions = listOf(
                        CustomAccessibilityAction("Tilt up") { tap(Nudge.UP); true },
                        CustomAccessibilityAction("Tilt down") { tap(Nudge.DOWN); true },
                    )
                }
                .pointerInput(enabled, halfTravelPx) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                    ) { change, dy ->
                        change.consume()
                        springBack?.cancel()
                        deflection = (deflection + dy / halfTravelPx).coerceIn(-1f, 1f)
                        report(deflection)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("▲", color = FlightColors.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            Text("▼", color = FlightColors.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            val active = GimbalNudges.forDeflection(deflection) != Nudge.STOP
            Box(
                Modifier
                    .offset { IntOffset(0, (deflection * halfTravelPx).roundToInt()) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (!enabled) FlightColors.muted else if (active) FlightColors.warn else Color.White),
            )
        }
        Caption("GIMBAL", Modifier.padding(top = 4.dp))
    }
}
