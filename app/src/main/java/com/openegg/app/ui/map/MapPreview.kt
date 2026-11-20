package com.openegg.app.ui.map

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openegg.app.drone.Telemetry
import com.openegg.app.map.FlightTrail

/** Default picture-in-picture size (landscape flight screen). */
val PipModifier = Modifier.size(width = 176.dp, height = 110.dp)

/**
 * Rounded, bordered corner frame that swallows all touches and calls [onClick]: tap to swap.
 * Use it for the small map *and* for the small video when the map is full screen; give the
 * content Modifier.fillMaxSize().
 */
@Composable
fun PipFrame(onClick: () -> Unit, modifier: Modifier = PipModifier, description: String = "Swap view", content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape).border(1.5.dp, Color.White.copy(alpha = 0.7f), shape)) {
        content()
        // On top of the content (incl. AndroidView), so the map/video never sees the gesture.
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = description }
                .clickable(role = Role.Button, onClick = onClick),
        )
    }
}

/** Small non-interactive map that follows the aircraft north-up; tap to make it full screen. */
@Composable
fun MapPreview(
    telemetry: Telemetry,
    onClick: () -> Unit,
    modifier: Modifier = PipModifier,
    trail: FlightTrail? = null,
    waypoints: List<MapWaypoint> = emptyList(),
) {
    PipFrame(onClick, modifier, description = "Map — tap to enlarge") {
        FlightMap(
            state = telemetry.toMapState(),
            modifier = Modifier.fillMaxSize(),
            trail = trail,
            waypoints = waypoints,
            showControls = false,
            interactive = false,
        )
    }
}
