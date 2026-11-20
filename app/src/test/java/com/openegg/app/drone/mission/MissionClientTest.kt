package com.openegg.app.drone.mission

import com.openegg.app.drone.DroneCommands
import com.openegg.app.drone.PowerEggMode
import com.openegg.app.drone.Px4Mode
import com.openegg.app.link.SimulatedVehicle
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
import com.openegg.app.mavlink.gen.MavCmd
import com.openegg.app.mavlink.gen.MavFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MissionClientTest {

    private val dialect = PowerVisionDialect.dialect
    private lateinit var scope: CoroutineScope
    private lateinit var sim: SimulatedVehicle
    private lateinit var link: MavlinkLink
    private lateinit var client: MissionClient

    private val home = -27.4698 to 153.0251

    private fun route(n: Int = 3) = Mission(
        (0 until n).map { i ->
            MissionWaypoint(
                id = i + 1L, latitude = home.first + 0.0003 * (i + 1), longitude = home.second + 0.0002 * i,
                altitudeM = 20f + i * 5, holdS = i.toFloat(),
            )
        },
    )

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sim = SimulatedVehicle(dialect, telemetryPeriodMs = 20, scope = scope)
        link = MavlinkLink({ sim }, MavlinkCodec(dialect), reconnectDelayMs = 50, gcsHeartbeatPeriodMs = 50)
        client = MissionClient(link, DroneCommands(link, ackTimeoutMs = 500, modeTimeoutMs = 1000), requestTimeoutMs = 300, maxRetries = 4)
        link.start(scope)
        withTimeout(5000) { link.state.filterIsInstance<MavlinkLink.State.Connected>().first { it.vehicle != null } }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        link.stop()
        scope.cancel()
    }

    @Test
    fun uploadDownloadRoundTripUsesOriginalItemFormat() = runBlocking {
        val mission = route(3)
        assertEquals(MissionClient.Result.Ok, client.upload(mission, verify = true))

        val p = client.progress.value
        assertEquals(MissionProgress.Phase.UPLOADED, p.phase)
        assertTrue(p.verified)

        // Protocol order as in WaypointManager: CLEAR_ALL, COUNT, then items.
        val msgs = sim.receivedMissionMessages.toList()
        assertEquals(listOf("MISSION_CLEAR_ALL", "MISSION_COUNT"), msgs.take(2))

        val stored = sim.missionItems
        assertEquals(3, stored.size)
        stored.forEachIndexed { i, it ->
            assertEquals(i, it.seq)
            assertEquals(MavCmd.NAV_WAYPOINT, it.command)
            assertEquals(MavFrame.GLOBAL_RELATIVE_ALT, it.frame)
            assertEquals(1, it.autocontinue)
            assertEquals(mission.waypoints[i].holdS, it.param1, 0f)
            assertEquals(0f, it.param2, 0f); assertEquals(0f, it.param3, 0f); assertEquals(0f, it.param4, 0f)
            assertEquals(mission.waypoints[i].latitude.toFloat(), it.x, 0f)
            assertEquals(mission.waypoints[i].altitudeM, it.z, 0f)
        }

        val down = client.download()
        assertNotNull(down)
        assertTrue(MissionGate.sameRoute(mission.waypoints, down!!.waypoints))
        assertEquals("MISSION_ACK", sim.receivedMissionMessages.last())
    }

    @Test
    fun retriesWhenAnItemIsLost() = runBlocking {
        sim.dropIncomingMissionItems = 1
        assertEquals(MissionClient.Result.Ok, client.upload(route(4), verify = true))
        assertEquals(4, sim.missionItems.size)
        assertTrue(sim.receivedMissionMessages.count { it == "MISSION_ITEM" } >= 5)
    }

    @Test
    fun resendsCountWhenTheFirstRequestIsLost() = runBlocking {
        sim.dropOutgoingMissionRequests = 1
        assertEquals(MissionClient.Result.Ok, client.upload(route(2), verify = false))
        assertEquals(2, sim.missionItems.size)
        assertTrue(sim.receivedMissionMessages.count { it == "MISSION_COUNT" } >= 2)
    }

    @Test
    fun failsAfterRetriesWhenAircraftNeverAnswers() = runBlocking {
        sim.dropIncomingMissionItems = 1000
        val r = client.upload(route(2), verify = false)
        assertTrue(r is MissionClient.Result.Failed)
        assertEquals(MissionProgress.Phase.FAILED, client.progress.value.phase)
    }

    @Test
    fun invalidMissionIsNotSent() = runBlocking {
        val bad = Mission(route(2).waypoints.map { it.copy(altitudeM = 300f) })
        val r = client.upload(bad)
        assertTrue(r is MissionClient.Result.Invalid)
        assertTrue(sim.receivedMissionMessages.isEmpty())
    }

    @Test
    fun clearEmptiesTheAircraftMission() = runBlocking {
        client.upload(route(2), verify = false)
        assertTrue(client.clear())
        assertTrue(sim.missionItems.isEmpty())
    }

    @Test
    fun startFollowsProgressToFinish() = runBlocking {
        sim.missionTicksPerItem = 3
        client.monitor(scope)
        assertEquals(MissionClient.Result.Ok, client.upload(route(3)))
        assertEquals(DroneCommands.Result.Ok, client.start())

        val seenCurrent = mutableSetOf<Int>()
        val done = withTimeout(10_000) {
            client.progress.first { p -> p.currentWaypoint?.let { seenCurrent += it }; p.phase == MissionProgress.Phase.FINISHED }
        }
        assertEquals(setOf(0, 1, 2), done.reachedWaypoints)
        assertEquals(2, done.currentWaypoint)
        assertTrue("saw intermediate MISSION_CURRENT values", seenCurrent.size >= 2)
        // PX4 behaviour (sim): hovers after the last item.
        assertEquals(PowerEggMode.HOVER.reported, Px4Mode.fromCustomMode(sim.customMode))
    }

    @Test
    fun pauseHoversAndResumeContinues() = runBlocking {
        sim.missionTicksPerItem = 15
        client.monitor(scope)
        assertEquals(MissionClient.Result.Ok, client.upload(route(3)))
        assertEquals(DroneCommands.Result.Ok, client.start())

        assertEquals(DroneCommands.Result.Ok, client.pause())
        assertEquals(PowerEggMode.HOVER.reported, Px4Mode.fromCustomMode(sim.customMode))
        assertEquals(MissionProgress.Phase.PAUSED, client.progress.value.phase)
        val seqAtPause = sim.missionCurrentSeq
        delay(700) // > 2 items' worth of ticks
        assertEquals("no progress while paused", seqAtPause, sim.missionCurrentSeq)

        assertEquals(DroneCommands.Result.Ok, client.resume())
        assertEquals(PowerEggMode.WAYPOINTS.reported, Px4Mode.fromCustomMode(sim.customMode))
        withTimeout(10_000) { client.progress.first { it.phase == MissionProgress.Phase.FINISHED } }
        Unit
    }

    @Test
    fun abortReturnsHome() = runBlocking {
        client.monitor(scope)
        client.upload(route(2))
        client.start()
        assertEquals(DroneCommands.Result.Ok, client.abort())
        assertEquals(PowerEggMode.RETURN_HOME.reported, Px4Mode.fromCustomMode(sim.customMode))
        assertEquals(MissionProgress.Phase.ABORTED, client.progress.value.phase)
    }
}
