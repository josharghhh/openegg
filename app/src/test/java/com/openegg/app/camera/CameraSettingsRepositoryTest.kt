package com.openegg.app.camera

import com.openegg.app.camera.CameraSettingsRepository.FormatResult
import com.openegg.app.camera.CameraSettingsRepository.GimbalTarget
import com.openegg.app.camera.CameraSettingsRepository.SetResult
import com.openegg.app.drone.DroneCommands
import com.openegg.app.link.SimulatedVehicle
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** In-memory aircraft: params by name, optional silence / stubbornness per param. */
private class FakePort : CameraParamPort {
    val params = CameraSettings.simulatorDefaults().mapValues { ParamBits.decode(it.value) }.toMutableMap()
    val silent = mutableSetOf<String>()
    /** Params whose writes are ignored (the aircraft echoes the old value). */
    val stubborn = mutableSetOf<String>()
    /** Params whose PARAM_SET echo is lost but the write still lands. */
    val lostEcho = mutableSetOf<String>()
    val reads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val writes: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
    val cameraCmds: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val sent: MutableList<Pair<String, Map<String, Any>>> = Collections.synchronizedList(mutableListOf())
    var cameraResult: Int? = 1
    override var vehicleIds: Pair<Int, Int>? = 1 to 1

    override suspend fun readInt(name: String): Int? {
        reads += name
        return if (name in silent) null else params[name]
    }

    override suspend fun writeInt(name: String, value: Int): Int? {
        writes += name to value
        if (name in silent) return null
        if (name !in stubborn) params[name] = value
        return if (name in lostEcho) null else params[name]
    }

    override suspend fun cameraCommand(cmd: Int): Int? { cameraCmds += cmd; return cameraResult }

    override suspend fun send(name: String, values: Map<String, Any>): Boolean {
        sent += name to values
        return name == "MOUNT_CONTROL"
    }
}

class CameraSettingsRepositoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val port = FakePort()
    private val repo = CameraSettingsRepository(port, scope, readSpacingMs = 0, nudgePeriodMs = 20)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun readAllFillsValuesSequentially() = runBlocking {
        repo.readAll()
        assertEquals(CameraSettings.all.map { it.param }, port.reads)
        assertEquals(7, repo.values.value["PV_CAM_EV"])
        assertEquals(30_436, repo.values.value["PV_CAM_SD_CAP"])
        assertTrue(repo.settings.value.values.none { it.loading || it.error != null })
        assertFalse(repo.refreshing.value)
    }

    @Test
    fun readAllToleratesPerSettingTimeouts() = runBlocking {
        port.silent += listOf("PV_CAM_ISO", "PV_GIM_P_SMT")
        repo.readAll()
        val iso = repo.settings.value.getValue("PV_CAM_ISO")
        assertNull(iso.value)
        assertEquals("No reply", iso.error)
        assertFalse(iso.loading)
        assertNull(repo.values.value["PV_CAM_ISO"])
        assertEquals(1, repo.values.value["PV_CAM_WB"])
        assertEquals(50, repo.values.value["PV_GIM_ANGLE"])
        assertEquals("No reply", repo.settings.value.getValue("PV_GIM_P_SMT").error)
        // Retry clears the error once the aircraft answers.
        port.silent.clear()
        assertEquals(1, repo.read(CameraSettings.ISO))
        assertNull(repo.settings.value.getValue("PV_CAM_ISO").error)
    }

    @Test
    fun setConfirmsFromEcho() = runBlocking {
        assertEquals(SetResult.Ok, repo.set(CameraSettings.ISO, 4))
        assertEquals(listOf("PV_CAM_ISO" to 4), port.writes)
        assertTrue("no extra read when the echo matches", port.reads.isEmpty())
        assertEquals(4, repo.values.value["PV_CAM_ISO"])
    }

    @Test
    fun setFallsBackToReadBackWhenEchoIsLost() = runBlocking {
        port.lostEcho += "PV_CAM_WB"
        assertEquals(SetResult.Ok, repo.set(CameraSettings.WHITE_BALANCE, 3))
        assertEquals(listOf("PV_CAM_WB"), port.reads)
        assertEquals(3, repo.values.value["PV_CAM_WB"])
    }

    @Test
    fun setReportsMismatchWhenAircraftKeepsOldValue() = runBlocking {
        port.stubborn += "PV_CAM_V_SIZE"
        val r = repo.set(CameraSettings.VIDEO_SIZE, 1)
        assertEquals(SetResult.Mismatch(4), r)
        val row = repo.settings.value.getValue("PV_CAM_V_SIZE")
        assertEquals(4, row.value)
        assertTrue(row.error!!.contains("1080p 30fps"))
    }

    @Test
    fun setTimesOut() = runBlocking {
        port.silent += "PV_CAM_EV"
        assertEquals(SetResult.Timeout, repo.set(CameraSettings.EV, 8))
        assertEquals("Not confirmed", repo.settings.value.getValue("PV_CAM_EV").error)
    }

    @Test
    fun invalidAndReadOnlyWritesNeverReachTheAircraft() = runBlocking {
        assertEquals(SetResult.NotAllowed, repo.set(CameraSettings.SD_LEFT, 0))
        assertEquals(SetResult.NotAllowed, repo.set(CameraSettings.STATUS, 3))
        assertEquals(SetResult.NotAllowed, repo.set(CameraSettings.ISO, 7))
        assertEquals(SetResult.NotAllowed, repo.set(CameraSettings.PHOTO_SIZE, 1))
        assertEquals(SetResult.NotAllowed, repo.set(CameraSettings.GIMBAL_PITCH_SMOOTHING, 0))
        assertTrue(port.writes.isEmpty())
        assertEquals(SetResult.Ok, repo.set(CameraSettings.GIMBAL_PITCH_SPEED, 100))
        assertEquals(listOf("PV_GIM_PITCH" to 100), port.writes)
    }

    @Test
    fun formatSdRequiresExplicitConfirmation() = runBlocking {
        assertEquals(FormatResult.NotConfirmed, repo.formatSd(confirmed = false))
        assertTrue(port.cameraCmds.isEmpty())

        assertEquals(FormatResult.Success, repo.formatSd(confirmed = true))
        assertEquals(listOf(PowerVisionDialect.CameraCmd.FORMAT_SD), port.cameraCmds)
        assertTrue("SD values refreshed", port.reads.containsAll(listOf("PV_CAM_SD_CAP", "PV_CAM_SD_LEFT")))

        port.cameraResult = 2
        assertEquals(FormatResult.Failed(2), repo.formatSd(confirmed = true))
        port.cameraResult = null
        assertEquals(FormatResult.Timeout, repo.formatSd(confirmed = true))
        assertTrue(PowerVisionDialect.CameraCmd.FACTORY_RESET !in port.cameraCmds)
    }

    @Test
    fun nudgeBuildsMountControlLikeTheOriginal() = runBlocking {
        assertTrue(repo.nudge(pitchRate = -400))
        val (name, v) = port.sent.single()
        assertEquals("MOUNT_CONTROL", name)
        assertEquals(255, v["target_system"])
        assertEquals(190, v["target_component"])
        assertEquals(-400, v["input_a"])
        assertEquals(0, v["input_b"])
        assertEquals(0, v["input_c"])
        assertEquals(0, v["save_position"])

        repo.nudge(pitchRate = 5000, yawRate = -9999)
        assertEquals(1000, port.sent.last().second["input_a"])
        assertEquals(-1000, port.sent.last().second["input_c"])
    }

    @Test
    fun nudgeCanTargetTheVehicleInstead() = runBlocking {
        repo.gimbalTarget = GimbalTarget.Vehicle
        port.vehicleIds = 1 to 154
        repo.nudge(500)
        assertEquals(1, port.sent.last().second["target_system"])
        assertEquals(154, port.sent.last().second["target_component"])
        port.vehicleIds = null
        assertFalse(repo.nudge(500))
    }

    @Test
    fun holdPitchRateRepeatsUntilReleased() = runBlocking {
        repo.holdPitchRate(1000)
        delay(150)
        repo.holdPitchRate(0)
        delay(60)
        val controls = port.sent.filter { it.first == "MOUNT_CONTROL" }
        assertTrue("expected repeats, got ${controls.size}", controls.size >= 3)
        assertTrue(controls.all { it.second["input_a"] == 1000 })
        assertEquals("MOUNT_CONFIGURE", port.sent.first().first)
        assertEquals(2, port.sent.first().second["mount_mode"])
        val count = port.sent.size
        delay(100)
        assertEquals("stopped after release", count, port.sent.size)
    }

    @Test
    fun changingHeldRateDoesNotReconfigure() = runBlocking {
        repo.holdPitchRate(-400)
        delay(50)
        repo.holdPitchRate(-800)
        delay(50)
        repo.holdPitchRate(0)
        assertEquals(1, port.sent.count { it.first == "MOUNT_CONFIGURE" })
        assertTrue(port.sent.any { it.second["input_a"] == -800 })
    }
}

/** End to end over the real MAVLink codec, link and DroneCommands against the simulator. */
class CameraSettingsSimulatorTest {

    private val dialect = PowerVisionDialect.dialect
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sim = SimulatedVehicle(dialect, telemetryPeriodMs = 20, scope = scope).apply {
        params.putAll(CameraSettings.simulatorDefaults())
    }
    private val link = MavlinkLink({ sim }, MavlinkCodec(dialect), reconnectDelayMs = 50, gcsHeartbeatPeriodMs = 50)

    @After
    fun tearDown() = runBlocking {
        link.stop()
        scope.cancel()
    }

    @Test
    fun readsAndWritesThroughTheSimulator() = runBlocking {
        link.start(scope)
        withTimeout(5000) { link.state.filterIsInstance<MavlinkLink.State.Connected>().first { it.vehicle != null } }
        val repo = CameraSettingsRepository(DroneCommands(link), link, scope)

        repo.readAll(listOf(CameraSettings.EV, CameraSettings.SD_LEFT, CameraSettings.GIMBAL_PITCH_SPEED))
        assertEquals(7, repo.values.value["PV_CAM_EV"])
        assertEquals(27_910, repo.values.value["PV_CAM_SD_LEFT"])
        assertEquals(50, repo.values.value["PV_GIM_PITCH"])

        assertEquals(SetResult.Ok, repo.set(CameraSettings.EV, 10))
        assertEquals(10, sim.params.getValue("PV_CAM_EV").toRawBits())

        assertTrue("MOUNT_CONTROL is in the dialect and goes out", repo.nudge(-400))
        assertFalse("MOUNT_CONFIGURE is not in the dialect yet", repo.configureMount())
    }
}
