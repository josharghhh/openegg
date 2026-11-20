package com.openegg.app.ui.flight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI

object FlightColors {
    val panel = Color(0x99000000)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val danger = Color(0xFFF87171)
    val muted = Color(0xFFB0B0B0)
    val sky = Color(0xFF2B6CB0)
    val ground = Color(0xFF7B5A36)
}

/** Small translucent pill used across the status bar. */
@Composable
fun StatusPill(text: String, color: Color = Color.White, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(FlightColors.panel)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Label + big monospace value, e.g. "H  12.4 m". */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = FlightColors.muted, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Artificial horizon with heading: sky/ground disc rotated by roll and shifted by
 * pitch, a fixed aircraft symbol, and the heading in degrees underneath.
 */
@Composable
fun AttitudeIndicator(rollRad: Float, pitchRad: Float, headingDeg: Float?, size: Dp = 112.dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .semantics { contentDescription = "Attitude: roll ${deg(rollRad)}°, pitch ${deg(pitchRad)}°" },
        ) {
            val r = this.size.minDimension / 2
            val c = Offset(r, r)
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(circle) {
                // 45° of pitch moves the horizon by one radius.
                val shift = (pitchRad / (PI.toFloat() / 4)) * r
                rotate(-Math.toDegrees(rollRad.toDouble()).toFloat(), c) {
                    translate(top = shift) {
                        drawRect(FlightColors.sky, topLeft = Offset(-r, -2 * r), size = Size(4 * r, 3 * r))
                        drawRect(FlightColors.ground, topLeft = Offset(-r, r), size = Size(4 * r, 3 * r))
                        drawLine(Color.White, Offset(-r, r), Offset(3 * r, r), strokeWidth = 2f)
                    }
                }
            }
            drawCircle(Color.White.copy(alpha = 0.6f), r, c, style = Stroke(2f))
            // Fixed aircraft symbol.
            drawLine(FlightColors.warn, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.15f, c.y), strokeWidth = 5f)
            drawLine(FlightColors.warn, Offset(c.x + r * 0.15f, c.y), Offset(c.x + r * 0.55f, c.y), strokeWidth = 5f)
            drawCircle(FlightColors.warn, 4f, c)
        }
        Text(
            headingDeg?.let { "HDG %03.0f°".format(it) } ?: "HDG ---",
            color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

private fun deg(rad: Float) = Math.round(Math.toDegrees(rad.toDouble()))

/**
 * A button that only fires after being held for [holdMillis]; a ring fills while held.
 * Used for flight commands so an accidental tap mid-flight does nothing.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    sublabel: String,
    color: Color,
    enabled: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1200,
    diameter: Dp = 76.dp,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val tint = if (enabled) color else FlightColors.muted
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(FlightColors.panel)
                .border(2.dp, tint.copy(alpha = 0.5f), CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "$label. Press and hold to confirm."
                    if (enabled) onClick(label = "Confirm $label") { onConfirmed(); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            onConfirmed()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(diameter)) {
                drawArc(tint, -90f, 360f * progress.value, useCenter = false, style = Stroke(8f))
            }
            Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(sublabel, color = FlightColors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Round camera button (shutter / record). */
@Composable
fun CameraButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
) {
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(if (active) activeColor else FlightColors.panel)
            .border(3.dp, if (enabled) Color.White else FlightColors.muted, CircleShape)
            .semantics { role = Role.Button; contentDescription = label }
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else FlightColors.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WarningBanner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = FlightColors.muted)
