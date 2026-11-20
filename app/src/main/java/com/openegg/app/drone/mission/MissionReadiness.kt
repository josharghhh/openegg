package com.openegg.app.drone.mission

import com.openegg.app.drone.Telemetry
import com.openegg.app.drone.homeOrTakeoff

/** Aircraft state the editor needs to decide what's allowed. Build it with [from]. */
data class MissionReadiness(
    val connected: Boolean = false,
    val gpsFix: Boolean = false,
    val armed: Boolean = false,
    val relativeAltitudeM: Float? = null,
    val batteryPercent: Int? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
) {
    companion object {
        /** 3D fix (GPS_RAW_INT fix_type >= 3). */
        fun from(t: Telemetry, connected: Boolean) = MissionReadiness(
            connected = connected,
            gpsFix = t.gpsFixType >= 3,
            armed = t.armed,
            relativeAltitudeM = t.altitudeRelative,
            batteryPercent = t.batteryPercent,
            homeLatitude = t.homeOrTakeoff?.first,
            homeLongitude = t.homeOrTakeoff?.second,
        )

        /** OBSERVED: the original refused to send/start waypoints below 5 m ("tip_send_waypoint"). */
        const val MIN_START_ALTITUDE_M = 5f
    }
}

object MissionGate {
    /**
     * Reasons Start is not allowed; empty = allowed. Start requires the mission currently in the
     * editor to be the one on the aircraft and verified, a GPS fix, and (as in the original
     * `sendWayLine`) the aircraft armed and at least 5 m up.
     */
    fun startBlockers(editor: List<MissionWaypoint>, progress: MissionProgress, r: MissionReadiness): List<String> = buildList {
        if (!r.connected) add("Not connected.")
        val onboard = progress.onboard
        when {
            onboard == null || progress.phase == MissionProgress.Phase.FAILED -> add("Upload the mission first.")
            !sameRoute(onboard.waypoints, editor) -> add("Mission changed since upload: upload again.")
            !progress.verified -> add("Uploaded mission not verified.")
        }
        if (!r.gpsFix) add("No GPS fix.")
        if (!r.armed) add("Aircraft not armed (take off with the remote first).")
        else if ((r.relativeAltitudeM ?: 0f) < MissionReadiness.MIN_START_ALTITUDE_M) {
            add("Climb above ${MissionReadiness.MIN_START_ALTITUDE_M.toInt()} m before starting.")
        }
        if (progress.isFlying) add("Mission already running.")
    }

    /** Same waypoints, ignoring ids (a downloaded mission has fresh ids). */
    fun sameRoute(a: List<MissionWaypoint>, b: List<MissionWaypoint>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]; val y = b[i]
            kotlin.math.abs(x.latitude - y.latitude) < FLOAT_DEG_TOLERANCE && kotlin.math.abs(x.longitude - y.longitude) < FLOAT_DEG_TOLERANCE &&
                kotlin.math.abs(x.altitudeM - y.altitudeM) < 0.01f && kotlin.math.abs(x.holdS - y.holdS) < 0.01f &&
                x.speedMs == y.speedMs && x.yawDeg == y.yawDeg
        }
}

/**
 * MISSION_ITEM carries lat/lon as 32-bit floats (as the original did), so positions are only kept to
 * about 1e-5 degrees (~1-2 m at the PowerEgg's typical longitudes).
 */
const val FLOAT_DEG_TOLERANCE = 3e-5
