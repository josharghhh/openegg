package com.openegg.app.drone

import com.openegg.app.link.SimulatedVehicle
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
import com.openegg.app.mavlink.gen.MavAutopilot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DroneLinkTest {

    private val dialect = PowerVisionDialect.dialect
    private lateinit var scope: CoroutineScope
    private lateinit var sim: SimulatedVehicle
    private lateinit var link: MavlinkLink

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sim = SimulatedVehicle(dialect, telemetryPeriodMs = 20, scope = scope)
        link = MavlinkLink({ sim }, MavlinkCodec(dialect), reconnectDelayMs = 50, gcsHeartbeatPeriodMs = 50)
    }

    @After
    fun tearDown() = runBlocking {
        link.stop()
        scope.cancel()
    }

    private suspend fun awaitVehicle(): MavlinkLink.Vehicle = withTimeout(5000) {
        link.state.filterIsInstance<MavlinkLink.State.Connected>().first { it.vehicle != null }.vehicle!!
    }

    @Test
    fun connectsAndIdentifiesPx4Vehicle() = runBlocking {
        link.start(scope)
        val v = awaitVehicle()
        assertEquals(1, v.systemId)
        assertEquals(MavAutopilot.PX4, v.autopilot)
    }

    @Test
    fun telemetryReducerBuildsSnapshot() = runBlocking {
        link.start(scope)
        awaitVehicle()
        val snapshots = withTimeout(5000) {
            link.messages.runningFold(Telemetry()) { t, m -> TelemetryReducer.reduce(t, m) }.take(60).toList()
        }
        val t = snapshots.last()
        assertTrue(t.hasPosition)
        assertEquals(-27.4698, t.latitude!!, 1e-6)
        assertEquals(14, t.satellites)
        assertEquals(87, t.batteryPercent)
        assertEquals(16.8f, t.batteryVoltage!!, 1e-3f)
        assertEquals("Position", t.mode?.label)
        assertEquals(-27.4698, t.takeoffLatitude!!, 1e-4)
        assertTrue("PowerVision messages must be understood", 159 !in t.unhandledIds)
    }

    @Test
    fun returnToHomeUsesPowerVisionModeAndIsConfirmedByHeartbeat() = runBlocking {
        link.start(scope)
        awaitVehicle()
        assertEquals(DroneCommands.Result.Ok, DroneCommands(link).returnToHome())
        // Aircraft reports AUTO/RTL after being sent PowerVision main mode 10.
        assertEquals(PowerEggMode.RETURN_HOME.reported, Px4Mode.fromCustomMode(sim.customMode))
        assertTrue(sim.receivedCommands.isEmpty()) // no COMMAND_LONG involved
    }

    @Test
    fun takePhotoRoundTrip() = runBlocking {
        link.start(scope)
        awaitVehicle()
        assertEquals(0, DroneCommands(link).takePhoto())
        assertEquals(listOf(PowerVisionDialect.CameraCmd.TAKE_PHOTO), sim.receivedCameraCommands)
    }

    @Test
    fun activationWritesIntBitsAndConfirms() = runBlocking {
        link.start(scope)
        awaitVehicle()
        assertTrue(DroneCommands(link).activate())
        assertEquals(1, sim.params.getValue("PV_V_KEY_FLAG").toRawBits())
    }

    @Test
    fun readsParameter() = runBlocking {
        link.start(scope)
        awaitVehicle()
        val v = DroneCommands(link).readParameter("RTL_RETURN_ALT")
        assertNotNull(v)
        assertEquals(30f, v!!, 0f)
    }

    @Test
    fun commandsWithoutVehicleFailFast() = runBlocking {
        assertEquals(DroneCommands.Result.NoVehicle, DroneCommands(link).returnToHome())
    }

    @Test
    fun gcsHeartbeatWaitsForVehicle() = runBlocking {
        sim.silent = true
        link.start(scope)
        withTimeout(5000) { link.state.first { it is MavlinkLink.State.Connected } }
        kotlinx.coroutines.delay(300)
        // No vehicle heartbeat yet, so we must not have announced ourselves.
        assertEquals(null, link.vehicle)
        sim.silent = false
        awaitVehicle()
        Unit
    }
}
