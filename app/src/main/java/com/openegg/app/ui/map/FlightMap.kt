package com.openegg.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openegg.app.drone.Telemetry
import com.openegg.app.map.FlightTrail
import com.openegg.app.map.GeoBounds
import com.openegg.app.map.GeoPoint
import com.openegg.app.map.MapInit
import com.openegg.app.map.MapStyles
import com.openegg.app.ui.flight.FlightColors
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Remembers the flight-path trail and appends the aircraft position on every change.
 * Hoist this above any map swap (PiP <-> full screen) so the trail survives it.
 */
@Composable
fun rememberFlightTrail(telemetry: Telemetry, capacity: Int = 2_000): FlightTrail {
    val trail = remember { FlightTrail(capacity) }
    val lat = telemetry.latitude
    val lon = telemetry.longitude
    SideEffect { if (lat != null && lon != null) trail.add(lat, lon) }
    return trail
}

/** Convenience overload taking the full [Telemetry]. */
@Composable
fun FlightMap(
    telemetry: Telemetry,
    modifier: Modifier = Modifier,
    trail: FlightTrail? = null,
    waypoints: List<MapWaypoint> = emptyList(),
    onMapLongPress: ((lat: Double, lon: Double) -> Unit)? = null,
    onWaypointTap: ((index: Int) -> Unit)? = null,
    onWaypointDrag: ((index: Int, lat: Double, lon: Double) -> Unit)? = null,
    showControls: Boolean = true,
) = FlightMap(
    state = telemetry.toMapState(), modifier = modifier, trail = trail, waypoints = waypoints,
    onMapLongPress = onMapLongPress, onWaypointTap = onWaypointTap, onWaypointDrag = onWaypointDrag,
    showControls = showControls,
)

/**
 * The moving map: aircraft (rotated by heading), home "H", take-off point, phone location,
 * flight trail, dashed aircraft->home line with distance, mission waypoints (numbered, joined).
 *
 * Works offline from areas downloaded in [OfflineMapsScreen]; in the field the base-station
 * Wi-Fi has no internet, so anything not downloaded just shows the background colour.
 *
 * @param interactive false for the picture-in-picture preview (no gestures).
 * @param onViewportChanged called when the camera settles, with the visible bounds and zoom.
 * @param centerOn when it changes, the camera jumps there (used by the offline-maps screen).
 */
@Composable
fun FlightMap(
    state: MapAircraftState,
    modifier: Modifier = Modifier,
    trail: FlightTrail? = null,
    waypoints: List<MapWaypoint> = emptyList(),
    onMapLongPress: ((lat: Double, lon: Double) -> Unit)? = null,
    onWaypointTap: ((index: Int) -> Unit)? = null,
    onWaypointDrag: ((index: Int, lat: Double, lon: Double) -> Unit)? = null,
    showControls: Boolean = true,
    interactive: Boolean = true,
    showFlightControls: Boolean = true,
    onViewportChanged: ((GeoBounds, Double) -> Unit)? = null,
    centerOn: GeoPoint? = null,
    phoneLocation: PhoneLocationState? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phone = phoneLocation ?: rememberPhoneLocation()

    val mapView = remember {
        MapInit.ensure(context)
        // TextureView mode: composites correctly under/over other views (video SurfaceView, Compose overlays, PiP clipping).
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .compassEnabled(showControls)
            .attributionEnabled(showControls)
        MapView(context, options).apply { onCreate(null) }
    }
    val controller = remember(mapView) { MapController(mapView) }

    MapViewLifecycle(mapView, lifecycle)

    // Callbacks are read at event time so they can change without recreating the view.
    val longPress = rememberUpdatedState(onMapLongPress)
    val tap = rememberUpdatedState(onWaypointTap)
    val drag = rememberUpdatedState(onWaypointDrag)
    val viewport = rememberUpdatedState(onViewportChanged)
    SideEffect {
        controller.callbacks.onMapLongPress = longPress.value?.let { { la, lo -> longPress.value?.invoke(la, lo) } }
        controller.callbacks.onWaypointTap = tap.value?.let { { i -> tap.value?.invoke(i) } }
        controller.callbacks.onWaypointDrag = drag.value?.let { { i, la, lo -> drag.value?.invoke(i, la, lo) } }
        controller.callbacks.onViewport = viewport.value?.let { { b, z -> viewport.value?.invoke(b, z) } }
        controller.callbacks.onUserPan = { if (showFlightControls && interactive) MapUiPrefs.follow = false }
    }

    val style = MapStyles.byId(MapUiPrefs.styleId)
    // The non-interactive preview always follows the aircraft, north-up.
    val follow = if (!interactive) showFlightControls else showFlightControls && MapUiPrefs.follow
    val headingUp = interactive && showFlightControls && MapUiPrefs.headingUp
    LaunchedEffect(style) { controller.setStyle(style) }
    LaunchedEffect(interactive) { controller.setInteractive(interactive) }
    LaunchedEffect(centerOn) { centerOn?.let { controller.centerOn(it) } }
    SideEffect {
        controller.update(state, follow, headingUp)
        trail?.let { controller.setTrail(it) }
        controller.setWaypoints(waypoints)
        controller.setPhone(phone.location)
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())

        if (showControls) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (showFlightControls) {
                    MapButton(if (follow) "Following" else "Recenter", active = follow) {
                        MapUiPrefs.follow = true
                        controller.recenter()
                    }
                    MapButton(if (headingUp) "Heading up" else "North up", active = headingUp) {
                        MapUiPrefs.headingUp = !MapUiPrefs.headingUp
                    }
                }
                if (MapStyles.ALL.size > 1) {
                    MapButton("Map: ${style.label}") {
                        val i = MapStyles.ALL.indexOf(style)
                        MapUiPrefs.styleId = MapStyles.ALL[(i + 1) % MapStyles.ALL.size].id
                    }
                }
                MapButton(if (phone.granted) "Me" else "Show me", active = false) {
                    if (!phone.granted) phone.request()
                    else phone.location?.let { controller.centerOn(it, keepZoom = true); MapUiPrefs.follow = false }
                }
            }
        }
    }
}

/** Forwards the host lifecycle to MapView and tears it down when it leaves composition. */
@Composable
private fun MapViewLifecycle(mapView: MapView, lifecycle: Lifecycle) {
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(observer) // replays ON_START/ON_RESUME up to the current state
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
}

@Composable
internal fun MapButton(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(FlightColors.panel)
            .border(1.dp, if (active) FlightColors.ok else Color.Transparent, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (active) FlightColors.ok else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
