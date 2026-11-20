package com.openegg.app.drone

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FlightMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north in [0, 360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}

/** Where "home" is for distance readouts: the aircraft's HOME_POSITION, else the PowerEgg take-off point. */
val Telemetry.homeOrTakeoff: Pair<Double, Double>?
    get() = when {
        homeLatitude != null && homeLongitude != null -> homeLatitude to homeLongitude
        takeoffLatitude != null && takeoffLongitude != null -> takeoffLatitude to takeoffLongitude
        else -> null
    }

val Telemetry.distanceFromHome: Double?
    get() {
        val home = homeOrTakeoff ?: return null
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return FlightMath.distanceMeters(home.first, home.second, lat, lon)
    }

/** Battery level the UI should warn about; thresholds are conservative until the PowerEgg's own are read. */
enum class BatteryLevel { OK, LOW, CRITICAL, UNKNOWN }

val Telemetry.batteryLevel: BatteryLevel
    get() = when (val p = batteryPercent) {
        null -> BatteryLevel.UNKNOWN
        in 0..15 -> BatteryLevel.CRITICAL
        in 16..30 -> BatteryLevel.LOW
        else -> BatteryLevel.OK
    }

/** GPS_RAW_INT fix_type ≥ 3 is a 3D fix. */
val Telemetry.hasGpsFix: Boolean get() = gpsFixType >= 3
