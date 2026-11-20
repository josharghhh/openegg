package com.openegg.app.ui.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openegg.app.camera.CameraSettingsRepository
import com.openegg.app.drone.DroneCommands
import com.openegg.app.vxfly.StationStatus
import com.openegg.app.drone.homeOrTakeoff
import com.openegg.app.drone.mission.Mission
import com.openegg.app.drone.mission.MissionClient
import com.openegg.app.drone.mission.MissionEdits
import com.openegg.app.drone.mission.MissionProgress
import com.openegg.app.drone.mission.MissionWaypoint
import com.openegg.app.drone.DroneSession
import com.openegg.app.drone.Telemetry
import com.openegg.app.link.DroneNetwork
import com.openegg.app.mavlink.MavlinkLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FlightViewModel(app: Application) : AndroidViewModel(app) {

    data class LinkUi(
        val label: String,
        val connected: Boolean,
        val stale: Boolean,
        val detail: String,
    )

    private var session: DroneSession? = null
    private var networkJob: Job? = null

    private val _source = MutableStateFlow<DroneSession.Source?>(null)
    val source: StateFlow<DroneSession.Source?> = _source.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _linkState = MutableStateFlow<MavlinkLink.State>(MavlinkLink.State.Stopped)

    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(500) } }

    val link: StateFlow<LinkUi> = combine(_linkState, ticker) { s, now -> describe(s, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), describe(MavlinkLink.State.Stopped, 0))

    private val _recordingSince = MutableStateFlow<Long?>(null)
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()

    private val _camera = MutableStateFlow<CameraSettingsRepository?>(null)
    /** Camera settings + gimbal for the current session (null when disconnected). */
    val camera: StateFlow<CameraSettingsRepository?> = _camera.asStateFlow()

    private val _station = MutableStateFlow<StationStatus?>(null)
    /** Base-station (VxFly ground unit) battery and radio signal. */
    val station: StateFlow<StationStatus?> = _station.asStateFlow()

    private val voice = VoiceAlerts(app)

    private var mission: MissionClient? = null
    private val _missionProgress = MutableStateFlow(MissionProgress())
    val missionProgress: StateFlow<MissionProgress> = _missionProgress.asStateFlow()

    private val _waypoints = MutableStateFlow<List<MissionWaypoint>>(emptyList())
    /** The route being edited on the map / in the mission panel. Kept across reconnects. */
    val waypoints: StateFlow<List<MissionWaypoint>> = _waypoints.asStateFlow()

    fun setWaypoints(list: List<MissionWaypoint>) { _waypoints.value = list }
    fun addWaypoint(lat: Double, lon: Double) { _waypoints.value = MissionEdits.add(_waypoints.value, lat, lon) }
    fun moveWaypoint(index: Int, lat: Double, lon: Double) {
        val wp = _waypoints.value.getOrNull(index) ?: return
        _waypoints.value = MissionEdits.move(_waypoints.value, wp.id, lat, lon)
    }

    fun uploadMission() = act("Upload route") {
        val m = mission ?: return@act "Not connected"
        val t = _telemetry.value
        when (val r = m.upload(Mission(_waypoints.value), homeLatitude = t.homeOrTakeoff?.first, homeLongitude = t.homeOrTakeoff?.second)) {
            MissionClient.Result.Ok -> "Route uploaded and verified (${_waypoints.value.size} points)"
            else -> "Upload failed: $r"
        }
    }

    fun startMission() = act("Start route") { (mission?.start() ?: DroneCommands.Result.NoVehicle).describe("Flying the route") }
    fun pauseMission() = act("Pause route") { (mission?.pause() ?: DroneCommands.Result.NoVehicle).describe("Route paused, hovering") }
    fun resumeMission() = act("Resume route") { (mission?.resume() ?: DroneCommands.Result.NoVehicle).describe("Route resumed") }
    fun abortMission() = act("Abort route") { (mission?.abort() ?: DroneCommands.Result.NoVehicle).describe("Route aborted, returning home") }

    private val _logFile = MutableStateFlow<String?>(null)
    /** Path of the current session log, for the UI and for sharing. */
    val logFile: StateFlow<String?> = _logFile.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off messages for the snackbar. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun connect(source: DroneSession.Source) {
        if (_source.value == source) return
        viewModelScope.launch {
            disconnectNow()
            if (source == DroneSession.Source.BASE_STATION) {
                // Bind the process to the base station Wi-Fi (it has no internet).
                networkJob = launch { DroneNetwork(getApplication()).observe().collect { } }
            }
            val logDir = getApplication<Application>().getExternalFilesDir("sessions")
            val s = DroneSession(source, logDir).also { session = it }
            _logFile.value = s.log?.file?.absolutePath
            _camera.value = CameraSettingsRepository(s.commands, s.link, viewModelScope)
            mission = MissionClient(s.link, s.commands).also { m ->
                m.monitor(viewModelScope)
                launch { m.progress.collect { _missionProgress.value = it } }
            }
            launch {
                val policy = AlertPolicy()
                combine(s.telemetry, link) { t, l -> t to l }.collect { (t, l) ->
                    val lines = policy.next(t, l.stale, l.connected, System.currentTimeMillis())
                    if (lines.isNotEmpty()) {
                        voice.speak(lines)
                        lines.forEach { s.log?.event("VOICE", it) }
                    }
                }
            }
            launch { s.station.status.collect { _station.value = it } }
            _source.value = source
            s.start()
            launch { s.link.state.collect { _linkState.value = it } }
            launch { s.telemetry.collect { _telemetry.value = it } }
        }
    }

    fun disconnect() {
        viewModelScope.launch { disconnectNow() }
    }

    private suspend fun disconnectNow() {
        val s = session ?: return
        session = null
        withContext(NonCancellable) { s.stop() }
        networkJob?.cancel()
        networkJob = null
        _source.value = null
        _logFile.value = null
        _camera.value = null
        _station.value = null
        mission = null
        _missionProgress.value = MissionProgress()
        _telemetry.value = Telemetry()
        _linkState.value = MavlinkLink.State.Stopped
        _recordingSince.value = null
    }

    fun returnToHome() = act("Return home") { it.returnToHome().describe("Returning home") }

    fun hover() = act("Hover") { it.hover().describe("Holding position") }

    fun takePhoto() = act("Photo") { c -> c.takePhoto().cameraResult("Photo taken") }

    fun toggleRecording() = act("Record") { c ->
        if (_recordingSince.value == null) {
            val r = c.startRecording()
            if (r == 0) _recordingSince.value = System.currentTimeMillis()
            r.cameraResult("Recording")
        } else {
            val r = c.stopRecording()
            if (r == 0) _recordingSince.value = null
            r.cameraResult("Recording saved to the drone's SD card")
        }
    }

    private fun act(what: String, block: suspend (DroneCommands) -> String) {
        val s = session ?: run { _events.tryEmit("$what: not connected"); return }
        if (_busy.value) return
        _busy.value = true
        s.log?.event("ACTION", what)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val outcome = block(s.commands)
                s.log?.event("RESULT", "$what -> $outcome")
                _events.tryEmit(outcome)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun DroneCommands.Result.describe(ok: String) = when (this) {
        DroneCommands.Result.Ok -> ok
        DroneCommands.Result.NoVehicle -> "No aircraft connected"
        DroneCommands.Result.Timeout -> "The aircraft did not confirm — check the link"
        is DroneCommands.Result.Rejected -> "Rejected by the aircraft (code $code)"
    }

    private fun Int?.cameraResult(ok: String) = when (this) {
        null -> "Camera did not respond"
        0 -> ok
        else -> "Camera error (code $this)"
    }

    private fun describe(s: MavlinkLink.State, now: Long): LinkUi = when (s) {
        MavlinkLink.State.Stopped -> LinkUi("Not connected", false, false, "")
        is MavlinkLink.State.Connecting -> LinkUi("Connecting…", false, false, s.lastError ?: s.target)
        is MavlinkLink.State.Connected -> {
            val v = s.vehicle
            if (v == null) LinkUi("Waiting for aircraft", false, false, s.target)
            else {
                val age = now - v.lastHeartbeatAt
                LinkUi(if (age > 3000) "Signal lost ${age / 1000}s" else "Connected", true, age > 3000, s.target)
            }
        }
    }

    override fun onCleared() {
        voice.close()
        val s = session ?: return
        session = null
        // viewModelScope is already cancelled here; stop the session off the main thread.
        CoroutineScope(Dispatchers.Default).launch { s.stop() }
    }
}
