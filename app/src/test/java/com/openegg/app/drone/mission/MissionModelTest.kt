package com.openegg.app.drone.mission

import com.openegg.app.mavlink.gen.MavCmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionModelTest {

    private val homeLat = -27.4698
    private val homeLon = 153.0251

    private fun wp(id: Long, dLat: Double, alt: Float = 20f) = MissionWaypoint(id, homeLat + dLat, homeLon, alt)

    private fun messages(m: Mission, limits: MissionLimits = MissionLimits()) =
        MissionValidator.validate(m, limits, homeLat, homeLon).map { it.toString() }

    @Test
    fun validMissionHasNoIssues() {
        assertTrue(messages(Mission(listOf(wp(1, 0.001), wp(2, 0.002)))).isEmpty())
    }

    @Test
    fun emptyMissionIsRejected() {
        assertEquals(listOf("Add at least one waypoint."), messages(Mission(emptyList())))
    }

    @Test
    fun altitudeLimitsIncludingGeofence() {
        val low = messages(Mission(listOf(wp(1, 0.001, alt = 3f))))
        assertEquals(listOf("Waypoint 1: altitude 3 m is below the minimum 5 m."), low)

        val high = messages(Mission(listOf(wp(1, 0.001, alt = 101f))))
        assertEquals(listOf("Waypoint 1: altitude 101 m is above the maximum 100 m."), high)

        val fenced = messages(Mission(listOf(wp(1, 0.001, alt = 70f))), MissionLimits(geofenceMaxAltitudeM = 60f))
        assertEquals(listOf("Waypoint 1: altitude 70 m is above the maximum 60 m (aircraft altitude limit GF_MAX_VER_DIST)."), fenced)
    }

    @Test
    fun tooManyWaypoints() {
        val m = Mission((1..51).map { wp(it.toLong(), 0.0001 * it) })
        assertTrue(messages(m).contains("Too many waypoints: 51 (maximum 50)."))
    }

    @Test
    fun distanceAndSpacing() {
        val far = messages(Mission(listOf(wp(1, 0.02)))) // ~2.2 km
        assertEquals(1, far.size)
        assertTrue(far[0], far[0].startsWith("Waypoint 1: 22") && far[0].endsWith("m from home (maximum 1000 m)."))

        val close = messages(Mission(listOf(wp(1, 0.001), wp(2, 0.001 + 0.000005))))
        assertEquals(1, close.size)
        assertTrue(close[0], close[0].startsWith("Waypoint 2: only") && close[0].contains("from waypoint 1"))
    }

    @Test
    fun holdAndSpeedRanges() {
        val m = Mission(listOf(wp(1, 0.001).copy(holdS = 101f, speedMs = 20f)))
        assertEquals(
            listOf("Waypoint 1: hold time must be 0-100 s.", "Waypoint 1: speed must be 1-10 m/s."),
            messages(m),
        )
    }

    @Test
    fun encoderMatchesOriginalAndSpeedIsOptIn() {
        val m = Mission(listOf(wp(1, 0.001).copy(holdS = 4f, speedMs = 3f), wp(2, 0.002).copy(speedMs = 3f)))
        val plain = MissionEncoder.encode(m)
        assertEquals(2, plain.size)
        assertTrue(plain.all { it.command == MavCmd.NAV_WAYPOINT && it.frame == 3 && it.param2 == 0f && it.param4 == 0f })
        assertEquals(4f, plain[0].param1)

        val withSpeed = MissionEncoder.encode(m, MissionEncoder.Options(sendSpeedCommands = true))
        assertEquals(listOf(MavCmd.DO_CHANGE_SPEED, MavCmd.NAV_WAYPOINT, MavCmd.NAV_WAYPOINT), withSpeed.map { it.command })
        assertEquals(listOf(0, 1, 2), withSpeed.map { it.seq })
        assertEquals(mapOf(1 to 0, 2 to 1), MissionEncoder.waypointIndexBySeq(withSpeed))
        val back = MissionEncoder.decode(withSpeed)
        assertEquals(listOf(3f, 3f), back.waypoints.map { it.speedMs })
    }

    @Test
    fun editsAddMoveReorderRemove() {
        var l = MissionEdits.add(emptyList(), 1.0, 2.0)
        l = MissionEdits.add(l, 3.0, 4.0)
        l = MissionEdits.add(l, 5.0, 6.0)
        assertEquals(listOf(1L, 2L, 3L), l.map { it.id })
        l = MissionEdits.move(l, 2, 7.0, 8.0)
        assertEquals(7.0, l[1].latitude, 0.0)
        l = MissionEdits.reorder(l, 3, -1)
        assertEquals(listOf(1L, 3L, 2L), l.map { it.id })
        l = MissionEdits.reorder(l, 1, -1) // already first
        assertEquals(listOf(1L, 3L, 2L), l.map { it.id })
        l = MissionEdits.remove(l, 3)
        assertEquals(listOf(1L, 2L), l.map { it.id })
        assertEquals(4L, MissionEdits.nextId(MissionEdits.add(l, 0.0, 0.0)))
    }

    @Test
    fun statsAndBatteryHeuristic() {
        val m = Mission(listOf(wp(1, 0.0009, alt = 20f))) // ~100 m from home
        val s = MissionStats.of(m, homeLat, homeLon)
        assertEquals(100.0, s.distanceM, 2.0)
        assertEquals(100.0 / 5 + 20 / 2, s.estimatedTimeS, 1.0)
        assertNull(s.batteryWarning(90))
        assertTrue(s.batteryWarning(30)!!.startsWith("Battery too low"))
    }

    private val readyAir = MissionReadiness(connected = true, gpsFix = true, armed = true, relativeAltitudeM = 10f)

    @Test
    fun startGate() {
        val wps = listOf(wp(1, 0.001))
        val uploaded = MissionProgress(MissionProgress.Phase.UPLOADED, onboard = Mission(wps), verified = true)

        assertTrue(MissionGate.startBlockers(wps, uploaded, readyAir).isEmpty())
        assertEquals(listOf("Upload the mission first."), MissionGate.startBlockers(wps, MissionProgress(), readyAir))
        assertEquals(listOf("Uploaded mission not verified."), MissionGate.startBlockers(wps, uploaded.copy(verified = false), readyAir))
        assertEquals(listOf("No GPS fix."), MissionGate.startBlockers(wps, uploaded, readyAir.copy(gpsFix = false)))
        assertEquals(
            listOf("Mission changed since upload: upload again."),
            MissionGate.startBlockers(MissionEdits.move(wps, 1, 0.5, 0.5), uploaded, readyAir),
        )
        assertEquals(listOf("Climb above 5 m before starting."), MissionGate.startBlockers(wps, uploaded, readyAir.copy(relativeAltitudeM = 2f)))
        assertFalse(MissionGate.startBlockers(wps, uploaded, readyAir.copy(armed = false)).isEmpty())
    }
}
