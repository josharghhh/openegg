package com.openegg.app.ui.mission

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openegg.app.drone.mission.MissionEdits
import com.openegg.app.drone.mission.MissionGate
import com.openegg.app.drone.mission.MissionLimits
import com.openegg.app.drone.mission.MissionProgress
import com.openegg.app.drone.mission.MissionReadiness
import com.openegg.app.drone.mission.MissionStats
import com.openegg.app.drone.mission.MissionValidator
import com.openegg.app.drone.mission.MissionWaypoint
import com.openegg.app.drone.mission.Mission
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Near-opaque: the panel sits over a busy map, and text must stay readable.
private val PanelBg = Color(0xF7101418)
private val RowBg = Color(0xFF1C2228)
private val Accent = Color(0xFF4FC3F7)
private val Warn = Color(0xFFFFB74D)
private val Danger = Color(0xFFE57373)
private val Ok = Color(0xFF81C784)
private val Dim = Color(0xFF9AA4AE)

/**
 * Waypoint mission editor. Stateless: [waypoints] are owned by the caller (shared with the map),
 * every edit goes through [onWaypointsChange]. See [MissionEdits] for the same edits from map gestures.
 *
 * Integration (FlightScreen, by the lead):
 * ```
 * var waypoints by rememberSaveable(stateSaver = ...) { mutableStateOf(emptyList<MissionWaypoint>()) }
 * val missionProgress by vm.missionClient.progress.collectAsState()
 * FlightMap(
 *     waypoints = waypoints.mapIndexed { i, w -> MapWaypoint(w.id, GeoPoint(w.latitude, w.longitude), label = "${i + 1}",
 *                    current = missionProgress.currentWaypoint == i, reached = i in missionProgress.reachedWaypoints) },
 *     onMapLongPress = { p -> waypoints = MissionEdits.add(waypoints, p.lat, p.lon) },
 *     onWaypointDrag = { id, p -> waypoints = MissionEdits.move(waypoints, id, p.lat, p.lon) },
 * )
 * MissionEditorPanel(
 *     waypoints, { waypoints = it }, missionProgress,
 *     readiness = MissionReadiness.from(telemetry, connected = vm.isConnected),
 *     limits = MissionLimits(geofenceMaxAltitudeM = vm.gfMaxVerDist),
 *     onUpload = { scope.launch { vm.missionClient.upload(Mission(waypoints), verify = true, limits, homeLat, homeLon) } },
 *     onStart = { scope.launch { vm.missionClient.start() } }, onPause = { ... pause() }, onResume = { ... resume() },
 *     onAbort = { ... abort() },   // return home
 * )
 * // once: vm.missionClient.monitor(viewModelScope)
 * ```
 */
@Composable
fun MissionEditorPanel(
    waypoints: List<MissionWaypoint>,
    onWaypointsChange: (List<MissionWaypoint>) -> Unit,
    progress: MissionProgress,
    readiness: MissionReadiness,
    onUpload: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
    limits: MissionLimits = MissionLimits(),
    /** Optional: "add a waypoint at the aircraft's position". */
    onAddAtAircraft: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val mission = Mission(waypoints)
    val issues = remember(waypoints, limits, readiness.homeLatitude, readiness.homeLongitude) {
        MissionValidator.validate(mission, limits, readiness.homeLatitude, readiness.homeLongitude)
    }
    val stats = remember(waypoints, readiness.homeLatitude, readiness.homeLongitude) {
        MissionStats.of(mission, readiness.homeLatitude, readiness.homeLongitude)
    }
    val startBlockers = MissionGate.startBlockers(waypoints, progress, readiness)
    val busy = progress.phase in setOf(
        MissionProgress.Phase.CLEARING, MissionProgress.Phase.UPLOADING,
        MissionProgress.Phase.VERIFYING, MissionProgress.Phase.DOWNLOADING,
    )
    var selected by rememberSaveable { mutableLongStateOf(-1L) }

    Column(
        modifier
            .width(340.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title + totals on one line, and "Add at aircraft" in the button row: in landscape the panel
        // is short, and every fixed line it saves is another waypoint row visible in the list.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Waypoint mission", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "${waypoints.size}/${limits.maxWaypoints} pts · ${formatDistance(stats.distanceM)} · ~${formatDuration(stats.estimatedTimeS)}",
                    color = Dim, fontSize = 12.sp,
                )
            }
            if (onClose != null) SmallButton("Close", onClick = onClose)
        }
        stats.batteryWarning(readiness.batteryPercent)?.let { Text(it, color = Warn, fontSize = 13.sp) }
        if (waypoints.isEmpty()) {
            Text("Long-press the map to add waypoints.", color = Dim, fontSize = 13.sp)
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(waypoints, key = { _, w -> w.id }) { i, w ->
                WaypointRow(
                    index = i, wp = w, count = waypoints.size, limits = limits,
                    expanded = selected == w.id,
                    state = when {
                        i in progress.reachedWaypoints -> RowState.REACHED
                        progress.currentWaypoint == i && progress.isFlying -> RowState.CURRENT
                        else -> RowState.NORMAL
                    },
                    hasIssue = issues.any { it.index == i },
                    editable = !progress.isFlying && !busy,
                    onToggle = { selected = if (selected == w.id) -1L else w.id },
                    onChange = { c -> onWaypointsChange(MissionEdits.update(waypoints, w.id) { c }) },
                    onDelete = { onWaypointsChange(MissionEdits.remove(waypoints, w.id)) },
                    onMove = { d -> onWaypointsChange(MissionEdits.reorder(waypoints, w.id, d)) },
                )
            }
        }

        issues.take(3).forEach { Text(it.toString(), color = Danger, fontSize = 12.sp) }
        if (issues.size > 3) Text("+${issues.size - 3} more problems", color = Danger, fontSize = 12.sp)

        StatusLine(progress)
        if (!progress.isFlying && startBlockers.isNotEmpty()) {
            Text(startBlockers.first(), color = Dim, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onAddAtAircraft != null && !progress.isFlying) {
                SmallButton("+ Here", enabled = !busy && waypoints.size < limits.maxWaypoints, onClick = onAddAtAircraft)
            }
            when (progress.phase) {
                MissionProgress.Phase.RUNNING -> {
                    BigButton("Pause", Warn, Modifier.weight(1f), onClick = onPause)
                    BigButton("Return home", Danger, Modifier.weight(1f), onClick = onAbort)
                }
                MissionProgress.Phase.PAUSED -> {
                    BigButton("Resume", Accent, Modifier.weight(1f), onClick = onResume)
                    BigButton("Return home", Danger, Modifier.weight(1f), onClick = onAbort)
                }
                else -> {
                    BigButton(
                        if (progress.onboard != null && MissionGate.sameRoute(progress.onboard.waypoints, waypoints) && progress.verified) "Re-upload" else "Upload",
                        Accent, Modifier.weight(1f),
                        enabled = issues.isEmpty() && !busy && readiness.connected,
                        onClick = onUpload,
                    )
                    HoldToConfirmButton(
                        "Hold to start", Ok, Modifier.weight(1f),
                        enabled = startBlockers.isEmpty() && !busy,
                        onConfirmed = onStart,
                    )
                }
            }
        }
    }
}

private enum class RowState { NORMAL, CURRENT, REACHED }

@Composable
private fun WaypointRow(
    index: Int,
    wp: MissionWaypoint,
    count: Int,
    limits: MissionLimits,
    expanded: Boolean,
    state: RowState,
    hasIssue: Boolean,
    editable: Boolean,
    onToggle: () -> Unit,
    onChange: (MissionWaypoint) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val border = when {
        hasIssue -> Danger
        state == RowState.CURRENT -> Accent
        state == RowState.REACHED -> Ok
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RowBg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Text(
                buildString {
                    append("${wp.altitudeM.roundToInt()} m")
                    wp.speedMs?.let { append(" · ${"%.1f".format(it)} m/s") }
                    if (wp.holdS > 0f) append(" · hold ${wp.holdS.roundToInt()} s")
                },
                color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f),
            )
            if (state == RowState.REACHED) Text("done", color = Ok, fontSize = 12.sp)
            if (state == RowState.CURRENT) Text("next", color = Accent, fontSize = 12.sp)
        }
        if (expanded && editable) {
            LabeledSlider("Altitude", "${wp.altitudeM.roundToInt()} m", wp.altitudeM, limits.minAltitudeM..limits.effectiveMaxAltitudeM) {
                onChange(wp.copy(altitudeM = it.roundToInt().toFloat()))
            }
            LabeledSlider("Hold", "${wp.holdS.roundToInt()} s", wp.holdS, 0f..limits.maxHoldS) {
                onChange(wp.copy(holdS = it.roundToInt().toFloat()))
            }
            val speed = wp.speedMs ?: MissionStats.DEFAULT_SPEED_MS
            LabeledSlider("Speed", wp.speedMs?.let { "${"%.1f".format(it)} m/s" } ?: "default", speed, limits.minSpeedMs..limits.maxSpeedMs) {
                onChange(wp.copy(speedMs = (it * 2).roundToInt() / 2f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                SmallButton("Up", enabled = index > 0) { onMove(-1) }
                SmallButton("Down", enabled = index < count - 1) { onMove(1) }
                if (wp.speedMs != null) SmallButton("Default speed") { onChange(wp.copy(speedMs = null)) }
                SmallButton("Delete", color = Danger, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dim, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Slider(value = v.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
    }
}

@Composable
private fun StatusLine(p: MissionProgress) {
    val (text, color) = when (p.phase) {
        MissionProgress.Phase.IDLE -> "Not uploaded" to Dim
        MissionProgress.Phase.CLEARING -> "Clearing aircraft mission..." to Accent
        MissionProgress.Phase.UPLOADING -> "Uploading ${p.transferDone}/${p.transferTotal}" to Accent
        MissionProgress.Phase.VERIFYING -> "Verifying ${p.transferDone}/${p.transferTotal}" to Accent
        MissionProgress.Phase.DOWNLOADING -> "Downloading ${p.transferDone}/${p.transferTotal}" to Accent
        MissionProgress.Phase.UPLOADED -> (if (p.verified) "Uploaded and verified (${p.waypointCount} pts)" else "Uploaded, not verified") to Ok
        MissionProgress.Phase.STARTING -> "Starting mission..." to Accent
        MissionProgress.Phase.RUNNING -> "Flying to waypoint ${(p.currentWaypoint ?: 0) + 1}/${p.waypointCount}" to Accent
        MissionProgress.Phase.PAUSED -> "Paused (hovering) at waypoint ${(p.currentWaypoint ?: 0) + 1}" to Warn
        MissionProgress.Phase.FINISHED -> "Mission complete: hovering at the last waypoint" to Ok
        MissionProgress.Phase.ABORTED -> (p.error ?: "Mission stopped: returning home") to Warn
        MissionProgress.Phase.FAILED -> (p.error ?: "Failed") to Danger
    }
    Text(text, color = color, fontSize = 13.sp)
    if (p.phase in setOf(MissionProgress.Phase.UPLOADING, MissionProgress.Phase.VERIFYING, MissionProgress.Phase.DOWNLOADING) && p.transferTotal > 0) {
        LinearProgressIndicator(progress = { p.transferDone / p.transferTotal.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
    if (p.isFlying && p.waypointCount > 0) {
        LinearProgressIndicator(progress = { p.reachedWaypoints.size / p.waypointCount.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, color: Color = Accent, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) color else Dim.copy(alpha = 0.5f),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22FFFFFF))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(alpha = 0.25f) else Color(0x22FFFFFF))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else Dim.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
    }
}

/** Must be held for [holdMs]; releasing early cancels. */
@Composable
fun HoldToConfirmButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMs: Int = 1500,
    onConfirmed: () -> Unit,
) {
    val fill = remember { Animatable(0f) }
    val confirm by rememberUpdatedState(onConfirmed)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(alpha = 0.15f) else Color(0x22FFFFFF))
            .semantics { contentDescription = "$text (press and hold)" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        fill.snapTo(0f)
                        fill.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        confirm()
                    }
                    tryAwaitRelease()
                    if (job.isActive) job.cancel()
                    scope.launch { fill.animateTo(0f, tween(150)) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fill.value.coerceIn(0f, 1f))
                .heightIn(min = 48.dp)
                .background(color.copy(alpha = 0.45f)),
        )
        Text(text, color = if (enabled) Color.White else Dim.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
    }
}

private fun formatDistance(m: Double) = if (m >= 1000) "%.2f km".format(m / 1000) else "${m.roundToInt()} m"

private fun formatDuration(s: Double): String {
    val t = s.roundToInt()
    return if (t >= 60) "${t / 60} min ${t % 60} s" else "$t s"
}
