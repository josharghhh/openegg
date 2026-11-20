package com.openegg.app.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openegg.app.ui.camera.CameraSettingsPanel
import com.openegg.app.ui.camera.GimbalPitchControl
import com.openegg.app.vxfly.StationStatus
import com.openegg.app.drone.AlarmSeverity
import com.openegg.app.drone.activeAlarms
import com.openegg.app.drone.mission.MissionReadiness
import com.openegg.app.ui.map.FlightMap
import com.openegg.app.ui.map.MapPreview
import com.openegg.app.ui.map.MapWaypoint
import com.openegg.app.ui.map.PipFrame
import com.openegg.app.ui.map.rememberFlightTrail
import com.openegg.app.ui.mission.MissionEditorPanel
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openegg.app.drone.BatteryLevel
import com.openegg.app.drone.DroneSession
import com.openegg.app.drone.Telemetry
import com.openegg.app.drone.batteryLevel
import com.openegg.app.drone.distanceFromHome
import com.openegg.app.drone.hasGpsFix
import kotlinx.coroutines.delay

@Composable
fun FlightScreen(vm: FlightViewModel, onExit: () -> Unit) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val recordingSince by vm.recordingSince.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val station by vm.station.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val missionProgress by vm.missionProgress.collectAsStateWithLifecycle()
    var showCameraSettings by remember { mutableStateOf(false) }
    var showRoute by remember { mutableStateOf(false) }
    var mapIsMain by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val trail = rememberFlightTrail(t)
    val mapWaypoints = waypoints.mapIndexed { i, w -> MapWaypoint(w.latitude, w.longitude, label = "${i + 1}") }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val canCommand = link.connected && !link.stale && !busy

    val video: @Composable (Modifier) -> Unit = { m ->
        if (source == DroneSession.Source.BASE_STATION) {
            val player = com.openegg.app.video.rememberLiveVideoPlayer()
            com.openegg.app.video.LiveVideoView(player, m)
        } else {
            VideoArea(source, m)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mapIsMain) {
            FlightMap(
                t, Modifier.fillMaxSize(), trail = trail, waypoints = mapWaypoints,
                onMapLongPress = { lat, lon -> if (showRoute) vm.addWaypoint(lat, lon) },
                onWaypointDrag = { i, lat, lon -> vm.moveWaypoint(i, lat, lon) },
            )
        } else {
            video(Modifier.fillMaxSize())
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            StatusBar(t, link, source, recordingSince, station, onExit)
            Spacer(Modifier.height(8.dp))
            Warnings(t, link, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
            // Picture-in-picture: whichever of map/video is not main. Tap to swap.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mapIsMain) {
                    PipFrame(onClick = { mapIsMain = false }, description = "Video — tap to enlarge") { video(Modifier.fillMaxSize()) }
                } else {
                    MapPreview(t, onClick = { mapIsMain = true }, trail = trail, waypoints = mapWaypoints)
                }
                TextButton(onClick = { showRoute = !showRoute; if (showRoute) mapIsMain = true }) {
                    Text(if (showRoute) "Close route" else "ROUTE", color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                FlightActions(canCommand, vm)
                Spacer(Modifier.width(16.dp))
                FlightReadouts(t)
                Spacer(Modifier.weight(1f))
                AttitudeIndicator(t.roll, t.pitch, t.headingDeg)
                Spacer(Modifier.width(16.dp))
                CameraControls(canCommand, recordingSince != null, vm, onSettings = { showCameraSettings = true })
            }
        }

        camera?.let { repo ->
            GimbalPitchControl(
                t.gimbalPitchDeg, repo::holdPitchRate,
                Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 108.dp),
                enabled = canCommand,
            )
            if (showCameraSettings) {
                CameraSettingsPanel(repo, onClose = { showCameraSettings = false }, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }

        if (showRoute) {
            MissionEditorPanel(
                waypoints = waypoints,
                onWaypointsChange = vm::setWaypoints,
                progress = missionProgress,
                readiness = MissionReadiness.from(t, link.connected && !link.stale),
                onUpload = vm::uploadMission,
                onStart = vm::startMission,
                onPause = vm::pauseMission,
                onResume = vm::resumeMission,
                onAbort = vm::abortMission,
                onAddAtAircraft = { t.latitude?.let { lat -> t.longitude?.let { lon -> vm.addWaypoint(lat, lon) } } },
                onClose = { showRoute = false },
                modifier = Modifier.align(Alignment.CenterEnd).safeDrawingPadding(),
            )
        }

        // Top-centre, below the status bar: never cover readouts, the horizon or controls in flight.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopCenter).padding(top = 56.dp).widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun VideoArea(source: DroneSession.Source?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            if (source == DroneSession.Source.DEMO) "Demo mode — simulated aircraft, no video"
            else "Live video not connected yet",
            color = Color(0x66FFFFFF), fontSize = 14.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusBar(
    t: Telemetry,
    link: FlightViewModel.LinkUi,
    source: DroneSession.Source?,
    recordingSince: Long?,
    station: StationStatus?,
    onExit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onExit) { Text("‹ Back", color = Color.White) }
        StatusPill(
            "${source?.label ?: ""} · ${link.label}",
            color = when {
                link.stale -> FlightColors.danger
                link.connected -> FlightColors.ok
                else -> FlightColors.warn
            },
        )
        t.mode?.let { StatusPill(it.label) }
        StatusPill(if (t.armed) "Motors on" else "Motors off", color = if (t.armed) FlightColors.warn else FlightColors.muted)
        Spacer(Modifier.weight(1f))
        if (recordingSince != null) RecordingTimer(recordingSince)
        station?.let { s ->
            StatusPill(
                if (!s.linked) "Station –"
                else buildString {
                    append("Station ")
                    append(s.groundBatteryPercent?.let { "%.0f%%".format(it) } ?: "--")
                    s.signalBars?.let { append(" · " + "▮".repeat(it) + "▯".repeat((5 - it).coerceAtLeast(0))) }
                },
                color = if (s.linked) Color.White else FlightColors.muted,
            )
        }
        StatusPill(
            "GPS ${t.satellites}" + if (t.hasGpsFix) "" else " · no fix",
            color = if (t.hasGpsFix) Color.White else FlightColors.warn,
        )
        StatusPill(
            buildString {
                append("Battery ")
                append(t.batteryPercent?.let { "$it%" } ?: "--")
                t.batteryVoltage?.let { append(" · %.1f V".format(it)) }
            },
            color = when (t.batteryLevel) {
                BatteryLevel.CRITICAL -> FlightColors.danger
                BatteryLevel.LOW -> FlightColors.warn
                else -> Color.White
            },
        )
    }
}

@Composable
private fun RecordingTimer(since: Long) {
    val elapsed by produceState(0L, since) {
        while (true) { value = (System.currentTimeMillis() - since) / 1000; delay(250) }
    }
    StatusPill("● REC %d:%02d".format(elapsed / 60, elapsed % 60), color = FlightColors.danger)
}

@Composable
private fun Warnings(t: Telemetry, link: FlightViewModel.LinkUi, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (link.stale) WarningBanner("Signal lost — the aircraft will act on its own failsafe", FlightColors.danger)
        t.activeAlarms.take(2).forEach { a ->
            WarningBanner(a.text, if (a.severity == AlarmSeverity.CRITICAL) FlightColors.danger else FlightColors.warn)
        }
        when (t.batteryLevel) {
            BatteryLevel.CRITICAL -> WarningBanner("Battery critical — land now", FlightColors.danger)
            BatteryLevel.LOW -> WarningBanner("Battery low — return home soon", FlightColors.warn)
            else -> {}
        }
        t.messages.lastOrNull()?.takeIf { System.currentTimeMillis() - it.at < 8000 }?.let {
            StatusPill(it.text, color = if (it.severity <= 3) FlightColors.danger else Color.White)
        }
    }
}

@Composable
private fun FlightReadouts(t: Telemetry) {
    Row(
        Modifier.background(FlightColors.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Readout("HEIGHT", t.altitudeRelative?.let { "%.1f m".format(it) } ?: "--")
        Readout("DISTANCE", t.distanceFromHome?.let { "%.0f m".format(it) } ?: "--")
        Readout("SPEED", "%.1f m/s".format(t.groundSpeed))
        Readout("CLIMB", "%+.1f m/s".format(t.climbRate))
    }
}

@Composable
private fun FlightActions(enabled: Boolean, vm: FlightViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HoldToConfirmButton("HOME", "hold to return", FlightColors.warn, enabled, vm::returnToHome)
        HoldToConfirmButton("HOVER", "hold to stop", FlightColors.ok, enabled, vm::hover, diameter = 64.dp)
    }
}

@Composable
private fun CameraControls(enabled: Boolean, recording: Boolean, vm: FlightViewModel, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CameraButton("SET", active = false, enabled = enabled, activeColor = Color.White, onClick = onSettings, diameter = 48.dp)
        CameraButton("PHOTO", active = false, enabled = enabled && !recording, activeColor = Color.White, onClick = vm::takePhoto, diameter = 72.dp)
        CameraButton(if (recording) "STOP" else "REC", active = recording, enabled = enabled, activeColor = FlightColors.danger, onClick = vm::toggleRecording)
    }
}
