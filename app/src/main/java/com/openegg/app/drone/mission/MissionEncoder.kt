package com.openegg.app.drone.mission

import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.gen.MavCmd
import com.openegg.app.mavlink.gen.MavFrame

/**
 * One MAVLink mission item (the fields of MISSION_ITEM, id 39).
 * [waypointIndex] links it back to the editor's waypoint (null for helper items).
 */
data class MissionItem(
    val seq: Int,
    val command: Int,
    val frame: Int,
    val param1: Float,
    val param2: Float,
    val param3: Float,
    val param4: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val autocontinue: Int = 1,
    val waypointIndex: Int? = null,
) {
    fun toMissionItemValues(targetSystem: Int, targetComponent: Int): Map<String, Any> = mapOf(
        "target_system" to targetSystem, "target_component" to targetComponent,
        "seq" to seq, "frame" to frame, "command" to command,
        // OBSERVED: `current = (seq == 0)` set at send time (WaypointManager::sendWaypoint).
        "current" to if (seq == 0) 1 else 0, "autocontinue" to autocontinue,
        "param1" to param1, "param2" to param2, "param3" to param3, "param4" to param4,
        "x" to x, "y" to y, "z" to z,
    )

    /** MISSION_ITEM_INT form, only used if the firmware asks with MISSION_REQUEST_INT (not seen on the Egg). */
    fun toMissionItemIntValues(targetSystem: Int, targetComponent: Int): Map<String, Any> =
        toMissionItemValues(targetSystem, targetComponent) + mapOf(
            "frame" to if (frame == MavFrame.GLOBAL_RELATIVE_ALT) MavFrame.GLOBAL_RELATIVE_ALT_INT else frame,
            "x" to Math.round(x.toDouble() * 1e7).toInt(), "y" to Math.round(y.toDouble() * 1e7).toInt(),
        )

    /** Same item, as stored by the aircraft (float precision). */
    fun sameAs(o: MissionItem): Boolean =
        seq == o.seq && command == o.command && frame == o.frame &&
            close(param1, o.param1) && close(param2, o.param2) && close(param3, o.param3) && close(param4, o.param4) &&
            close(x, o.x, 1e-6f) && close(y, o.y, 1e-6f) && close(z, o.z, 0.01f)

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = (a.isNaN() && b.isNaN()) || kotlin.math.abs(a - b) <= eps

    companion object {
        fun fromMessage(m: MavMessage): MissionItem {
            val isInt = m.name == "MISSION_ITEM_INT"
            val frame = m.int("frame")
            return MissionItem(
                seq = m.int("seq"), command = m.int("command"),
                frame = if (isInt && frame == MavFrame.GLOBAL_RELATIVE_ALT_INT) MavFrame.GLOBAL_RELATIVE_ALT else frame,
                param1 = m.float("param1"), param2 = m.float("param2"), param3 = m.float("param3"), param4 = m.float("param4"),
                x = if (isInt) (m.long("x") / 1e7).toFloat() else m.float("x"),
                y = if (isInt) (m.long("y") / 1e7).toFloat() else m.float("y"),
                z = m.float("z"), autocontinue = m.int("autocontinue"),
            )
        }
    }
}

/**
 * Waypoints <-> MAVLink items, exactly as the original SDK built them
 * (`MavlinkDrone::setWayPoints@000dca58`, docs/04 §3.4):
 *  - one MISSION_ITEM per waypoint, command 16 (NAV_WAYPOINT), frame 3 (GLOBAL_RELATIVE_ALT), autocontinue 1;
 *  - param1 = hold ("stay") seconds; param2/3/4 = 0 (`GoogleController.onMarkerAdd`);
 *  - x = lat, y = lon as float degrees; z = relative altitude metres.
 */
object MissionEncoder {
    data class Options(
        /**
         * INFERRED, default off: put a MAV_CMD_DO_CHANGE_SPEED (178) item before each waypoint whose speed
         * differs from the previous one. The original app never sent speed; verify the Egg accepts it first.
         */
        val sendSpeedCommands: Boolean = false,
    )

    fun encode(mission: Mission, options: Options = Options()): List<MissionItem> {
        val items = mutableListOf<MissionItem>()
        var lastSpeed: Float? = null
        mission.waypoints.forEachIndexed { i, w ->
            if (options.sendSpeedCommands && w.speedMs != null && w.speedMs != lastSpeed) {
                // param1 = 1 (ground speed), param2 = m/s, param3 = -1 (no throttle change).
                items += MissionItem(items.size, MavCmd.DO_CHANGE_SPEED, MavFrame.MISSION, 1f, w.speedMs, -1f, 0f, 0f, 0f, 0f)
                lastSpeed = w.speedMs
            }
            items += MissionItem(
                seq = items.size, command = MavCmd.NAV_WAYPOINT, frame = MavFrame.GLOBAL_RELATIVE_ALT,
                param1 = w.holdS, param2 = 0f, param3 = 0f, param4 = w.yawDeg ?: 0f,
                x = w.latitude.toFloat(), y = w.longitude.toFloat(), z = w.altitudeM,
                waypointIndex = i,
            )
        }
        return items
    }

    /** Rebuilds editable waypoints from downloaded items (non-waypoint items are folded in or skipped). */
    fun decode(items: List<MissionItem>): Mission {
        var speed: Float? = null
        var id = 1L
        val wps = mutableListOf<MissionWaypoint>()
        for (it in items.sortedBy { it.seq }) {
            when (it.command) {
                MavCmd.DO_CHANGE_SPEED -> speed = it.param2.takeIf { s -> s > 0f }
                MavCmd.NAV_WAYPOINT -> wps += MissionWaypoint(
                    id = id++, latitude = it.x.toDouble(), longitude = it.y.toDouble(), altitudeM = it.z,
                    holdS = it.param1, speedMs = speed, yawDeg = it.param4.takeIf { y -> y != 0f && !y.isNaN() },
                )
            }
        }
        return Mission(wps)
    }

    /** item seq -> waypoint index, for progress reporting. */
    fun waypointIndexBySeq(items: List<MissionItem>): Map<Int, Int> =
        items.mapNotNull { i -> i.waypointIndex?.let { i.seq to it } }.toMap()
}
