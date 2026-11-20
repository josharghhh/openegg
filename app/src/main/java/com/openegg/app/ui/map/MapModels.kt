package com.openegg.app.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openegg.app.drone.Telemetry
import com.openegg.app.map.MapStyles

/** The narrow slice of [Telemetry] the map draws. */
data class MapAircraftState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val headingDeg: Float? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val takeoffLatitude: Double? = null,
    val takeoffLongitude: Double? = null,
) {
    val hasPosition get() = latitude != null && longitude != null && !(latitude == 0.0 && longitude == 0.0)
}

fun Telemetry.toMapState() = MapAircraftState(
    latitude = latitude, longitude = longitude, headingDeg = headingDeg,
    homeLatitude = homeLatitude, homeLongitude = homeLongitude,
    takeoffLatitude = takeoffLatitude, takeoffLongitude = takeoffLongitude,
)

/**
 * A mission waypoint as the map shows it. Its index in the list is what callbacks report;
 * [label] defaults to the 1-based number.
 */
data class MapWaypoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    val selected: Boolean = false,
)

/** In-memory UI prefs shared by every map instance, so swapping PiP <-> full screen keeps them. */
object MapUiPrefs {
    var styleId by mutableStateOf(MapStyles.DEFAULT.id)
    var headingUp by mutableStateOf(false)
    var follow by mutableStateOf(true)
}
