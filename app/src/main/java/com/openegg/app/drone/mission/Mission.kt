package com.openegg.app.drone.mission

import com.openegg.app.drone.FlightMath
import kotlin.math.abs

/**
 * One waypoint as the user edits it.
 *
 * [id] is a stable identity for the UI and the map (markers are keyed by it); it is NOT
 * the MAVLink seq, which is assigned from list order at upload time exactly as the
 * original app did (`BaseMapController.sendWaypoints`: `seq = i`).
 *
 * Map mapping (the map agent's `FlightMap` uses `GeoPoint(lat, lon)`):
 *   marker id    <-> [id]
 *   marker point <-> GeoPoint([latitude], [longitude])
 *   label        <-> index + 1
 */
data class MissionWaypoint(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres above home (MAV_FRAME_GLOBAL_RELATIVE_ALT), as the original's `z`. */
    val altitudeM: Float = DEFAULT_ALTITUDE_M,
    /** Hover time at the waypoint, seconds (original: MISSION_ITEM param1, "stay time"). */
    val holdS: Float = 0f,
    /**
     * Cruise speed towards this waypoint. The original app never sent a speed (param2-4 = 0);
     * null = firmware default. Only transmitted when [MissionEncoder.Options.sendSpeedCommands] is on (INFERRED).
     */
    val speedMs: Float? = null,
    /** Heading at the waypoint, degrees; null = what the original sent (param4 = 0). INFERRED meaning. */
    val yawDeg: Float? = null,
) {
    companion object {
        /** The original used max(current altitude, 5 m) for a new point; 20 m is our default when not airborne. */
        const val DEFAULT_ALTITUDE_M = 20f
    }
}

data class Mission(val waypoints: List<MissionWaypoint>) {
    val size get() = waypoints.size
    fun isEmpty() = waypoints.isEmpty()
}

/**
 * Limits. OBSERVED in the original Vision+ app unless marked:
 *  - max 50 waypoints (`GoogleMapDraw.drawLine`: "Maximum 50 waypoints reached");
 *  - altitude 5..100 m (`SetHeightWindow` seekbar max 100, minValue 5);
 *  - hold 0..100 s (stay seekbar max 100).
 * INFERRED (not enforced by the original, added as safety rails):
 *  - altitude also capped by the aircraft's GF_MAX_VER_DIST when known (simulator/default 120 m);
 *  - max distance from home, min spacing, speed range.
 */
data class MissionLimits(
    val maxWaypoints: Int = 50,
    val minAltitudeM: Float = 5f,
    val maxAltitudeM: Float = 100f,
    /** GF_MAX_VER_DIST read from the aircraft, if any; the effective cap is the lower of the two. */
    val geofenceMaxAltitudeM: Float? = null,
    val maxHoldS: Float = 100f,
    val maxDistanceFromHomeM: Double = 1000.0,
    val minSpacingM: Double = 2.0,
    val minSpeedMs: Float = 1f,
    val maxSpeedMs: Float = 10f,
) {
    val effectiveMaxAltitudeM: Float
        get() = geofenceMaxAltitudeM?.takeIf { it > 0f }?.let { minOf(it, maxAltitudeM) } ?: maxAltitudeM
}

/** A validation problem. [index] is the 0-based waypoint index, or null for mission-wide problems. */
data class MissionIssue(val index: Int?, val message: String) {
    override fun toString() = if (index == null) message else "Waypoint ${index + 1}: $message"
}

object MissionValidator {
    fun validate(
        mission: Mission,
        limits: MissionLimits = MissionLimits(),
        homeLatitude: Double? = null,
        homeLongitude: Double? = null,
    ): List<MissionIssue> {
        val issues = mutableListOf<MissionIssue>()
        val wps = mission.waypoints
        if (wps.isEmpty()) issues += MissionIssue(null, "Add at least one waypoint.")
        if (wps.size > limits.maxWaypoints) {
            issues += MissionIssue(null, "Too many waypoints: ${wps.size} (maximum ${limits.maxWaypoints}).")
        }
        val maxAlt = limits.effectiveMaxAltitudeM
        wps.forEachIndexed { i, w ->
            if (w.latitude.isNaN() || w.longitude.isNaN() || abs(w.latitude) > 90 || abs(w.longitude) > 180 ||
                (w.latitude == 0.0 && w.longitude == 0.0)
            ) {
                issues += MissionIssue(i, "invalid position.")
            }
            if (w.altitudeM.isNaN() || w.altitudeM < limits.minAltitudeM) {
                issues += MissionIssue(i, "altitude ${fmt(w.altitudeM)} m is below the minimum ${fmt(limits.minAltitudeM)} m.")
            } else if (w.altitudeM > maxAlt) {
                val why = if (maxAlt < limits.maxAltitudeM) " (aircraft altitude limit GF_MAX_VER_DIST)" else ""
                issues += MissionIssue(i, "altitude ${fmt(w.altitudeM)} m is above the maximum ${fmt(maxAlt)} m$why.")
            }
            if (w.holdS.isNaN() || w.holdS < 0f || w.holdS > limits.maxHoldS) {
                issues += MissionIssue(i, "hold time must be 0-${fmt(limits.maxHoldS)} s.")
            }
            w.speedMs?.let {
                if (it.isNaN() || it < limits.minSpeedMs || it > limits.maxSpeedMs) {
                    issues += MissionIssue(i, "speed must be ${fmt(limits.minSpeedMs)}-${fmt(limits.maxSpeedMs)} m/s.")
                }
            }
            w.yawDeg?.let {
                if (it.isNaN() || it < 0f || it >= 360f) issues += MissionIssue(i, "heading must be 0-359 degrees.")
            }
            if (homeLatitude != null && homeLongitude != null) {
                val d = FlightMath.distanceMeters(homeLatitude, homeLongitude, w.latitude, w.longitude)
                if (d > limits.maxDistanceFromHomeM) {
                    issues += MissionIssue(i, "${d.toInt()} m from home (maximum ${limits.maxDistanceFromHomeM.toInt()} m).")
                }
            }
            if (i > 0) {
                val p = wps[i - 1]
                val d = FlightMath.distanceMeters(p.latitude, p.longitude, w.latitude, w.longitude)
                if (d < limits.minSpacingM) {
                    issues += MissionIssue(i, "only ${fmt(d.toFloat())} m from waypoint $i (minimum ${fmt(limits.minSpacingM.toFloat())} m).")
                }
            }
        }
        return issues
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
}

/** Distance / time / battery estimate for the editor. Heuristic; INFERRED numbers. */
data class MissionStats(
    val distanceM: Double,
    val estimatedTimeS: Double,
    /** Rough share of a full battery the mission (plus the flight back home) needs, percent. */
    val estimatedBatteryPercent: Int,
) {
    companion object {
        /** PX4 MPC_XY_CRUISE default; the PowerEgg firmware value is unknown. INFERRED. */
        const val DEFAULT_SPEED_MS = 5f
        const val CLIMB_RATE_MS = 2f
        /** "23-minute flight endurance" (PowerEgg manual), derated to 80 % for real-world use. */
        const val USABLE_ENDURANCE_S = 23 * 60 * 0.8
        const val RESERVE_PERCENT = 30

        fun of(mission: Mission, homeLatitude: Double? = null, homeLongitude: Double? = null, defaultSpeedMs: Float = DEFAULT_SPEED_MS): MissionStats {
            var dist = 0.0
            var time = 0.0
            var prevLat = homeLatitude
            var prevLon = homeLongitude
            var prevAlt = 0f
            for (w in mission.waypoints) {
                val speed = (w.speedMs ?: defaultSpeedMs).coerceAtLeast(0.5f)
                if (prevLat != null && prevLon != null) {
                    val leg = FlightMath.distanceMeters(prevLat, prevLon, w.latitude, w.longitude)
                    dist += leg
                    time += leg / speed
                }
                time += abs(w.altitudeM - prevAlt) / CLIMB_RATE_MS + w.holdS
                prevLat = w.latitude; prevLon = w.longitude; prevAlt = w.altitudeM
            }
            var returnTime = 0.0
            if (homeLatitude != null && homeLongitude != null && prevLat != null && prevLon != null) {
                returnTime = FlightMath.distanceMeters(prevLat, prevLon, homeLatitude, homeLongitude) / defaultSpeedMs + prevAlt / CLIMB_RATE_MS
            }
            val pct = ((time + returnTime) / USABLE_ENDURANCE_S * 100).toInt()
            return MissionStats(dist, time, pct)
        }
    }

    /** Warning text, or null when the battery looks sufficient. Keeps a 30 % reserve. */
    fun batteryWarning(batteryPercent: Int?): String? {
        if (batteryPercent == null) return if (estimatedBatteryPercent > 70) "Mission needs about $estimatedBatteryPercent % of a full battery." else null
        val available = batteryPercent - RESERVE_PERCENT
        return when {
            estimatedBatteryPercent > available -> "Battery too low: mission needs ~$estimatedBatteryPercent %, $batteryPercent % left with a $RESERVE_PERCENT % reserve."
            estimatedBatteryPercent > available * 0.8 -> "Battery marginal: mission needs ~$estimatedBatteryPercent % of $batteryPercent %."
            else -> null
        }
    }
}

/** Pure list edits shared by the editor panel and the map callbacks. */
object MissionEdits {
    fun nextId(list: List<MissionWaypoint>) = (list.maxOfOrNull { it.id } ?: 0L) + 1

    fun add(
        list: List<MissionWaypoint>, latitude: Double, longitude: Double,
        altitudeM: Float = list.lastOrNull()?.altitudeM ?: MissionWaypoint.DEFAULT_ALTITUDE_M,
    ): List<MissionWaypoint> = list + MissionWaypoint(nextId(list), latitude, longitude, altitudeM)

    fun move(list: List<MissionWaypoint>, id: Long, latitude: Double, longitude: Double) =
        list.map { if (it.id == id) it.copy(latitude = latitude, longitude = longitude) else it }

    fun update(list: List<MissionWaypoint>, id: Long, change: (MissionWaypoint) -> MissionWaypoint) =
        list.map { if (it.id == id) change(it) else it }

    fun remove(list: List<MissionWaypoint>, id: Long) = list.filterNot { it.id == id }

    /** Moves waypoint [id] by [delta] places (-1 = earlier). */
    fun reorder(list: List<MissionWaypoint>, id: Long, delta: Int): List<MissionWaypoint> {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}
