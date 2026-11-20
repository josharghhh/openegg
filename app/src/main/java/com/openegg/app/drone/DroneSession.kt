package com.openegg.app.drone

import com.openegg.app.link.ByteTransport
import com.openegg.app.link.SimulatedVehicle
import com.openegg.app.link.TcpTransport
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.openegg.app.camera.CameraSettings
import com.openegg.app.vxfly.SimulatedVxStation
import com.openegg.app.vxfly.VxStationClient
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One connection to a PowerEgg (real or simulated): the MAVLink link, the folded
 * telemetry, the command helpers and the session log. Lives as long as the flight screen.
 */
class DroneSession(
    val source: Source,
    /** Where session logs are written; null disables logging (tests). */
    logDir: File? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    enum class Source(val label: String) {
        /** Base station TCP server, as used by the original app. */
        BASE_STATION("PowerEgg"),
        /** In-app simulator for trying the UI without the aircraft. */
        DEMO("Demo"),
    }

    val link = MavlinkLink(transportFactory(source), MavlinkCodec(PowerVisionDialect.dialect))
    val commands = DroneCommands(link)

    private val simulatedStation = if (source == Source.DEMO) SimulatedVxStation() else null

    /** VxFly ground unit: base-station battery and radio signal (docs/07 §2). */
    val station = VxStationClient(
        host = VxStationClient.GROUND_HOST,
        transportFactory = simulatedStation?.let { s -> { s.transport() } }
            ?: { TcpTransport(VxStationClient.GROUND_HOST, VxStationClient.PORT) },
        scope = scope,
    )
    val log: SessionLog? = logDir?.let { SessionLog(it, source.name) }

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    fun start() {
        link.start(scope)
        station.start()
        scope.launch {
            link.messages.collect { m ->
                _telemetry.value = TelemetryReducer.reduce(_telemetry.value, m)
                log?.received(m)
            }
        }
        log?.let { l ->
            // describe() omits the heartbeat timestamp, so this logs real changes, not every heartbeat.
            scope.launch { link.state.map { describe(it) }.distinctUntilChanged().collect { l.event("LINK", it) } }
            scope.launch { link.unknownFrames.collect { l.unknownFrame(it.messageId, it.payload) } }
            scope.launch {
                station.status
                    .map { it.copy(lastSeenAt = null) } // log changes, not every keepalive
                    .distinctUntilChanged()
                    .collect { l.event("STATION", it.toString()) }
            }
            scope.launch { while (isActive) { delay(2000); l.flush() } }
        }
    }

    suspend fun stop() {
        link.stop()
        station.stop()
        log?.close()
        scope.cancel()
    }

    private fun describe(s: MavlinkLink.State) = when (s) {
        MavlinkLink.State.Stopped -> "stopped"
        is MavlinkLink.State.Connecting -> "connecting ${s.target} attempt ${s.attempt}" + (s.lastError?.let { " (last error: $it)" } ?: "")
        is MavlinkLink.State.Connected -> s.vehicle?.let {
            "vehicle sys=${it.systemId} comp=${it.componentId} autopilot=${it.autopilot} type=${it.type} " +
                "base_mode=0x%02x custom_mode=0x%08x".format(it.baseMode, it.customMode)
        } ?: "socket open ${s.target}, waiting for vehicle heartbeat"
    }

    private fun transportFactory(source: Source): () -> ByteTransport = when (source) {
        Source.BASE_STATION -> { { TcpTransport(BASE_STATION_HOST, BASE_STATION_PORT) } }
        Source.DEMO -> { { SimulatedVehicle(scope = scope).also { it.params.putAll(CameraSettings.simulatorDefaults()) } } }
    }

    companion object {
        const val BASE_STATION_HOST = "192.168.1.12"
        const val BASE_STATION_PORT = 20002
    }
}
