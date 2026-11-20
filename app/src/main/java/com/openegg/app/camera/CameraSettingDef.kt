package com.openegg.app.camera

/**
 * PowerEgg camera and gimbal settings that travel as ordinary MAVLink parameters
 * (docs/06-poweregg-camera-gimbal.md §1.4 and §2.1).
 *
 * Every value is an int32 whose raw bits are carried in PARAM_VALUE's float field
 * ([ParamBits]). Option codes and labels are the ones the original Egg* fragments wrote
 * (`gcs/utils/DataController.java`, `gcs/model/CameraModel.java`,
 * `EggAdvanceSettingFragment.java`, `EggCloudModeFragment.java`).
 *
 * Camera actions (photo, record, format, factory reset) are NOT parameters; they go through
 * PV_CAMERA_COMMAND (msg 160). Factory reset, gimbal reset and calibration are deliberately not
 * in this table.
 */
data class CameraSettingDef(
    /** MAVLink param_id, e.g. `PV_CAM_ISO`. */
    val param: String,
    val label: String,
    val group: Group,
    /** Allowed codes and their labels, in display order. Empty for range or read-only settings. */
    val options: List<Option> = emptyList(),
    /** Allowed raw range for slider-style settings (gimbal tuning). */
    val range: IntRange? = null,
    /** Read-only settings are shown but never written. */
    val readOnly: Boolean = false,
    /** Formats a read-only or range value for display; defaults to the option label or the number. */
    private val formatter: ((Int) -> String)? = null,
) {
    data class Option(val value: Int, val label: String)

    enum class Group(val title: String) {
        STATUS("Camera status"),
        EXPOSURE("Exposure"),
        IMAGE("Image"),
        PHOTO("Photo"),
        VIDEO("Video"),
        GIMBAL("Gimbal"),
    }

    val isOptionList get() = options.isNotEmpty()

    /** True when [value] may be written to this setting. */
    fun accepts(value: Int): Boolean = when {
        readOnly -> false
        options.isNotEmpty() -> options.any { it.value == value }
        range != null -> value in range
        else -> false
    }

    /** Human label for a raw value; unknown codes are shown as "Code n" so nothing is hidden. */
    fun labelFor(value: Int?): String {
        if (value == null) return "—"
        formatter?.let { return it(value) }
        return options.firstOrNull { it.value == value }?.label ?: if (range != null) value.toString() else "Code $value"
    }
}

/** PowerVision int params are the int's bit pattern inside the MAVLink float. */
object ParamBits {
    fun encode(value: Int): Float = Float.fromBits(value)
    fun decode(raw: Float): Int = raw.toRawBits()
}

/** SD card values: 0..204799 is a capacity (INFERRED MB); anything else means "no card" (doc 06 §1.4). */
object SdCard {
    val VALID = 0..204_799

    fun isPresent(value: Int?): Boolean = value != null && value in VALID

    fun format(value: Int?): String = when {
        value == null -> "—"
        value !in VALID -> "No SD card"
        value >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / 1024.0)
        else -> "$value MB"
    }
}

object CameraSettings {

    private fun opts(vararg pairs: Pair<Int, String>) = pairs.map { CameraSettingDef.Option(it.first, it.second) }

    // --- Status (read-only) ------------------------------------------------------------------

    val STATUS = CameraSettingDef(
        "PV_CAM_STAT", "Camera state", CameraSettingDef.Group.STATUS, readOnly = true,
        formatter = { v -> when (v) { 1 -> "Photo mode"; 3 -> "Recording"; else -> "Code $v" } },
    )
    val SD_CAPACITY = CameraSettingDef(
        "PV_CAM_SD_CAP", "SD capacity", CameraSettingDef.Group.STATUS, readOnly = true, formatter = SdCard::format,
    )
    val SD_LEFT = CameraSettingDef(
        "PV_CAM_SD_LEFT", "SD free", CameraSettingDef.Group.STATUS, readOnly = true, formatter = SdCard::format,
    )

    // --- Exposure ----------------------------------------------------------------------------

    val ISO = CameraSettingDef(
        "PV_CAM_ISO", "ISO", CameraSettingDef.Group.EXPOSURE,
        opts(1 to "Auto", 2 to "100", 3 to "200", 4 to "400", 5 to "800", 6 to "1600"),
    )
    val EV = CameraSettingDef(
        "PV_CAM_EV", "Exposure (EV)", CameraSettingDef.Group.EXPOSURE,
        opts(
            1 to "-2.0", 2 to "-1.7", 3 to "-1.3", 4 to "-1.0", 5 to "-0.7", 6 to "-0.3", 7 to "0.0",
            8 to "+0.3", 9 to "+0.7", 10 to "+1.0", 11 to "+1.3", 12 to "+1.7", 13 to "+2.0",
        ),
    )
    val METERING = CameraSettingDef(
        "PV_CAM_LT", "Metering", CameraSettingDef.Group.EXPOSURE,
        opts(1 to "Average", 2 to "Multi-point", 3 to "Center-weighted"),
    )
    val LONG_EXPOSURE = CameraSettingDef(
        "PV_CAM_LONG_EXP", "Long exposure", CameraSettingDef.Group.EXPOSURE,
        opts(1 to "Off", 2 to "1/30 s", 3 to "1 s", 4 to "2 s", 5 to "5 s", 6 to "10 s", 7 to "20 s", 8 to "30 s", 9 to "60 s"),
    )

    // --- Image -------------------------------------------------------------------------------

    val WHITE_BALANCE = CameraSettingDef(
        "PV_CAM_WB", "White balance", CameraSettingDef.Group.IMAGE,
        opts(1 to "Auto", 2 to "Sunny", 3 to "Cloudy", 4 to "Incandescent", 5 to "Fluorescent", 6 to "Underwater", 7 to "Outdoor"),
    )
    val STYLE = CameraSettingDef(
        "PV_CAM_STYLE", "Style", CameraSettingDef.Group.IMAGE,
        opts(1 to "Normal", 2 to "Painting", 3 to "Retro", 4 to "Negative", 5 to "B&W", 6 to "Color enhance", 7 to "70 years"),
    )
    val SHARPNESS = CameraSettingDef(
        "PV_CAM_ACUT", "Sharpness", CameraSettingDef.Group.IMAGE,
        opts(1 to "Sharp", 2 to "Normal", 3 to "Soft"),
    )
    val SCENE = CameraSettingDef(
        "PV_CAM_SCENE_M", "Scene", CameraSettingDef.Group.IMAGE,
        opts(1 to "Normal", 2 to "Flash", 3 to "Night", 4 to "Sport", 5 to "Landscape", 6 to "Portrait", 7 to "Sunset"),
    )

    // --- Photo / video -----------------------------------------------------------------------

    /** Note: there is no code 1 in the original table. */
    val PHOTO_SIZE = CameraSettingDef(
        "PV_CAM_P_SIZE", "Photo size", CameraSettingDef.Group.PHOTO,
        opts(2 to "4254×3264", 3 to "4000×3000", 4 to "3840×2160", 5 to "2560×1920"),
    )
    val VIDEO_SIZE = CameraSettingDef(
        "PV_CAM_V_SIZE", "Video resolution", CameraSettingDef.Group.VIDEO,
        opts(1 to "4K 30fps", 2 to "1080p 120fps", 3 to "1080p 60fps", 4 to "1080p 30fps", 5 to "720p 240fps"),
    )
    val TV_SYSTEM = CameraSettingDef(
        "PV_CAM_TV_MODE", "TV system", CameraSettingDef.Group.VIDEO,
        opts(1 to "NTSC", 2 to "PAL"),
    )
    val ANTI_FLICKER = CameraSettingDef(
        "PV_CAM_L_FREQ", "Anti-flicker", CameraSettingDef.Group.VIDEO,
        opts(1 to "Auto", 2 to "50 Hz", 3 to "60 Hz"),
    )

    /** All PV_CAM_* settings, in panel order. */
    val camera: List<CameraSettingDef> = listOf(
        STATUS, SD_CAPACITY, SD_LEFT,
        ISO, EV, METERING, LONG_EXPOSURE,
        WHITE_BALANCE, STYLE, SHARPNESS, SCENE,
        PHOTO_SIZE,
        VIDEO_SIZE, TV_SYSTEM, ANTI_FLICKER,
    )

    // --- Gimbal tuning (PV_GIM_*, doc 06 §2.1) -----------------------------------------------
    // Ranges are the original seek bars: PITCH/ANGLE = progress(0..94) + 6, smoothing = progress(0..29) + 1
    // (layout fragment_egg_cloud_advance_setting.xml).

    val GIMBAL_MODE = CameraSettingDef(
        "PV_GIM_MODE", "Gimbal mode", CameraSettingDef.Group.GIMBAL,
        opts(1 to "Follow aircraft", 2 to "FPV"),
    )
    val GIMBAL_PITCH_SPEED = CameraSettingDef(
        "PV_GIM_PITCH", "Pitch speed", CameraSettingDef.Group.GIMBAL, range = 6..100,
    )
    val GIMBAL_YAW_SPEED = CameraSettingDef(
        "PV_GIM_ANGLE", "Yaw speed", CameraSettingDef.Group.GIMBAL, range = 6..100,
    )
    val GIMBAL_PITCH_SMOOTHING = CameraSettingDef(
        "PV_GIM_P_SMT", "Pitch smoothing", CameraSettingDef.Group.GIMBAL, range = 1..30,
    )
    val GIMBAL_YAW_SMOOTHING = CameraSettingDef(
        "PV_GIM_Y_SMT", "Yaw smoothing", CameraSettingDef.Group.GIMBAL, range = 1..30,
    )

    val gimbal: List<CameraSettingDef> = listOf(
        GIMBAL_MODE, GIMBAL_PITCH_SPEED, GIMBAL_PITCH_SMOOTHING, GIMBAL_YAW_SPEED, GIMBAL_YAW_SMOOTHING,
    )

    val all: List<CameraSettingDef> = camera + gimbal

    fun byParam(param: String): CameraSettingDef? = all.firstOrNull { it.param == param }

    /**
     * Plausible values for the in-app simulator, already bit-encoded. The lead can do
     * `sim.params.putAll(CameraSettings.simulatorDefaults())` so the panel has something to show.
     */
    fun simulatorDefaults(): Map<String, Float> = mapOf(
        STATUS.param to 1,
        SD_CAPACITY.param to 30_436,
        SD_LEFT.param to 27_910,
        ISO.param to 1,
        EV.param to 7,
        METERING.param to 1,
        LONG_EXPOSURE.param to 1,
        WHITE_BALANCE.param to 1,
        STYLE.param to 1,
        SHARPNESS.param to 2,
        SCENE.param to 1,
        PHOTO_SIZE.param to 3,
        VIDEO_SIZE.param to 4,
        TV_SYSTEM.param to 2,
        ANTI_FLICKER.param to 1,
        GIMBAL_MODE.param to 1,
        GIMBAL_PITCH_SPEED.param to 50,
        GIMBAL_PITCH_SMOOTHING.param to 10,
        GIMBAL_YAW_SPEED.param to 50,
        GIMBAL_YAW_SMOOTHING.param to 10,
    ).mapValues { ParamBits.encode(it.value) }
}
