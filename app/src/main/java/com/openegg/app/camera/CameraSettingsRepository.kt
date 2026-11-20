package com.openegg.app.camera

import com.openegg.app.drone.DroneCommands
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads and writes the PowerEgg's PV_CAM_* / PV_GIM_* settings and sends gimbal nudges.
 *
 * Settings are fetched one by one (PARAM_REQUEST_READ, then a short pause), like the original
 * EggAdvanceSettingFragment, which staggered its requests 200 ms apart. A setting that does not
 * answer is marked with an error and the rest carry on.
 *
 * The only destructive action exposed is [formatSd], and it refuses to run unless `confirmed = true`.
 * Camera factory reset, gimbal reset and calibration are intentionally not exposed.
 */
class CameraSettingsRepository(
    private val port: CameraParamPort,
    private val scope: CoroutineScope,
    /** Pause between consecutive reads in [readAll]. */
    private val readSpacingMs: Long = 120,
    /** Repeat period while a nudge is held; the original sent one per scroll event (~5 Hz assumed). */
    private val nudgePeriodMs: Long = 200,
    /** Who MOUNT_CONTROL is addressed to; see [GimbalTarget]. */
    var gimbalTarget: GimbalTarget = GimbalTarget.OriginalSdkIds,
    /** Send MOUNT_CONFIGURE once at the start of each held nudge, as the original did on touch-down. */
    private val configureMountOnHold: Boolean = true,
) {
    constructor(commands: DroneCommands, link: MavlinkLink, scope: CoroutineScope) :
        this(DroneCameraPort(commands, link), scope)

    /** Per-row UI state. */
    data class SettingState(val value: Int? = null, val loading: Boolean = false, val error: String? = null)

    sealed interface SetResult {
        data object Ok : SetResult
        /** The setting is read-only (SD, status) or the value is not one of its options/range. */
        data object NotAllowed : SetResult
        data object Timeout : SetResult
        /** The aircraft answered but kept a different value. */
        data class Mismatch(val actual: Int) : SetResult
    }

    sealed interface FormatResult {
        data object NotConfirmed : FormatResult
        data object Success : FormatResult
        data class Failed(val code: Int) : FormatResult
        data object Timeout : FormatResult
    }

    /** Who MOUNT_CONTROL targets. UNVERIFIED on hardware (doc 06 §2.2 / §5.4). */
    sealed interface GimbalTarget {
        /** 255/190: the SDK's own ids, which is what the original app's code resolves to (INFERRED). */
        data object OriginalSdkIds : GimbalTarget
        /** The vehicle sysid/compid from HEARTBEAT; the fallback doc 06 suggests if 255/190 is ignored. */
        data object Vehicle : GimbalTarget
    }

    private val _settings = MutableStateFlow(CameraSettings.all.associate { it.param to SettingState() })

    /** Full per-row state (value + loading + error), keyed by param name. */
    val settings: StateFlow<Map<String, SettingState>> = _settings.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Int?>>(CameraSettings.all.associate { it.param to null })

    /** Last known raw value per param name (null = unknown). */
    val values: StateFlow<Map<String, Int?>> = _values.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True while [readAll] runs. */
    val refreshing: StateFlow<Boolean> = _busy.asStateFlow()

    private val ioLock = Mutex()

    // --- Settings ---------------------------------------------------------------------------

    /** Reads every setting in [defs] sequentially. Timeouts mark just that row as failed. */
    suspend fun readAll(defs: List<CameraSettingDef> = CameraSettings.all) {
        _busy.value = true
        try {
            defs.forEach { update(it.param) { s -> s.copy(loading = true, error = null) } }
            defs.forEachIndexed { i, def ->
                if (i > 0 && readSpacingMs > 0) delay(readSpacingMs)
                readOne(def)
            }
        } finally {
            _busy.value = false
        }
    }

    /** Re-reads one setting (e.g. the "retry" on an error row). */
    suspend fun read(def: CameraSettingDef): Int? {
        update(def.param) { it.copy(loading = true, error = null) }
        return readOne(def)
    }

    private suspend fun readOne(def: CameraSettingDef): Int? {
        val v = ioLock.withLock { port.readInt(def.param) }
        if (v == null) update(def.param) { it.copy(loading = false, error = "No reply") }
        else update(def.param) { SettingState(value = v) }
        return v
    }

    /**
     * Writes [value] and confirms it: first from the PARAM_VALUE echo, then (if the echo
     * differs or is missing) with an explicit read-back.
     */
    suspend fun set(def: CameraSettingDef, value: Int): SetResult {
        if (!def.accepts(value)) return SetResult.NotAllowed
        update(def.param) { it.copy(loading = true, error = null) }
        val result = ioLock.withLock {
            val echo = port.writeInt(def.param, value)
            if (echo == value) return@withLock echo
            port.readInt(def.param) ?: echo
        }
        return when (result) {
            value -> { update(def.param) { SettingState(value = value) }; SetResult.Ok }
            null -> { update(def.param) { it.copy(loading = false, error = "Not confirmed") }; SetResult.Timeout }
            else -> {
                update(def.param) { SettingState(value = result, error = "Camera kept ${def.labelFor(result)}") }
                SetResult.Mismatch(result)
            }
        }
    }

    /** Fire-and-forget helpers for UI callbacks. */
    fun refresh(): Job = scope.launch { readAll() }
    fun select(def: CameraSettingDef, value: Int): Job = scope.launch { set(def, value) }
    fun retry(def: CameraSettingDef): Job = scope.launch { read(def) }

    /**
     * Formats the aircraft's SD card (PV_CAMERA_COMMAND cmd 6). Does nothing unless [confirmed]
     * is true — the caller must have shown the user an explicit confirmation.
     * Result 1 = FormatSDSuccess, 2 = FormatSDFailed (doc 06 §1.3).
     */
    suspend fun formatSd(confirmed: Boolean): FormatResult {
        if (!confirmed) return FormatResult.NotConfirmed
        val r = port.cameraCommand(PowerVisionDialect.CameraCmd.FORMAT_SD) ?: return FormatResult.Timeout
        if (r != 1) return FormatResult.Failed(r)
        readAll(listOf(CameraSettings.SD_CAPACITY, CameraSettings.SD_LEFT))
        return FormatResult.Success
    }

    // --- Gimbal nudges ----------------------------------------------------------------------

    /**
     * Sends one MOUNT_CONTROL (157) exactly like the original gesture view
     * (`CustomGestureView.java:133-174` → `PX4Gimbal::setGimbalOrientation`):
     * `input_a = pitch`, `input_b = roll (0)`, `input_c = yaw`, `save_position = 0`.
     *
     * UNVERIFIED (doc 06 §5.7): the target ids ([gimbalTarget]) and the units. The original magnitudes
     * (see [Nudge]) are believed to be rates, probably centi-degrees per second, sent repeatedly while
     * the finger is held; the original never sent an explicit stop.
     * Values are clamped to ±1000, the largest the original ever sent.
     */
    suspend fun nudge(pitchRate: Int, yawRate: Int = 0): Boolean {
        val (sys, comp) = when (gimbalTarget) {
            GimbalTarget.OriginalSdkIds -> SDK_SYSTEM_ID to SDK_COMPONENT_ID
            GimbalTarget.Vehicle -> port.vehicleIds ?: return false
        }
        return port.send(
            "MOUNT_CONTROL",
            mapOf(
                "target_system" to sys, "target_component" to comp,
                "input_a" to pitchRate.coerceIn(-MAX_RATE, MAX_RATE),
                "input_b" to 0,
                "input_c" to yawRate.coerceIn(-MAX_RATE, MAX_RATE),
                "save_position" to 0,
            ),
        )
    }

    private var holdJob: Job? = null
    private var heldRate = 0

    /**
     * Keeps sending [pitchRate] every [nudgePeriodMs] until called again with another rate;
     * 0 stops. Meant to be wired straight to [com.openegg.app.ui.camera.GimbalPitchControl].
     */
    fun holdPitchRate(pitchRate: Int) {
        if (pitchRate == heldRate) return
        val starting = heldRate == 0
        heldRate = pitchRate
        holdJob?.cancel()
        holdJob = null
        if (pitchRate == 0) return
        holdJob = scope.launch {
            if (starting && configureMountOnHold) configureMount()
            while (isActive) {
                nudge(pitchRate)
                delay(nudgePeriodMs)
            }
        }
    }

    /**
     * MOUNT_CONFIGURE mount_mode=2 (MAVLINK_TARGETING), stab_pitch=1, stab_roll=0, stab_yaw=1 — what
     * `CustomGestureView.onDown` sent for the Egg before any nudge. Target 1/1 per doc 06 §2.2.
     * A silent no-op until MOUNT_CONFIGURE ([MOUNT_CONFIGURE_DEF]) is in the link's dialect.
     */
    suspend fun configureMount(): Boolean = port.send(
        "MOUNT_CONFIGURE",
        mapOf(
            "target_system" to 1, "target_component" to 1,
            "mount_mode" to 2, "stab_roll" to 0, "stab_pitch" to 1, "stab_yaw" to 1,
        ),
    )

    /** The original gesture magnitudes. "Up/down" is the finger direction, not a verified camera direction. */
    enum class Nudge(val pitchRate: Int) {
        UP_FAST(-800), UP(-400), STOP(0), DOWN(500), DOWN_FAST(1000),
    }

    private fun update(param: String, f: (SettingState) -> SettingState) {
        val next = _settings.updateAndGet { m -> m + (param to f(m[param] ?: SettingState())) }.getValue(param)
        _values.update { it + (param to next.value) }
    }

    companion object {
        const val SDK_SYSTEM_ID = 255
        const val SDK_COMPONENT_ID = 190
        const val MAX_RATE = 1000
    }
}
