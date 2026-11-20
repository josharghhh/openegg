package com.openegg.app.drone

import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.gen.MavModeFlag

/** Snapshot of everything the flight screen shows. Units are SI unless named otherwise. */
data class Telemetry(
    val armed: Boolean = false,
    val mode: Px4Mode? = null,
    val systemStatus: Int? = null,
    // Attitude (radians)
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    // Position
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMsl: Float? = null,
    val altitudeRelative: Float? = null,
    val headingDeg: Float? = null,
    // Speeds (m/s)
    val groundSpeed: Float = 0f,
    val climbRate: Float = 0f,
    // GPS
    val gpsFixType: Int = 0,
    val satellites: Int = 0,
    val hdop: Float? = null,
    // Battery
    val batteryVoltage: Float? = null,
    val batteryCurrent: Float? = null,
    val batteryPercent: Int? = null,
    // Home
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val homeAltitude: Float? = null,
    // PowerVision extras
    /** Raw PV_INFOR status/alarm bits; individual meanings are being mapped (docs/01 §2 alarmNotify). */
    val alarmWord: Long = 0,
    val takeoffLatitude: Double? = null,
    val takeoffLongitude: Double? = null,
    // Gimbal (degrees, MOUNT_STATUS centi-degrees as in ArduPilot; units to confirm on hardware)
    val gimbalPitchDeg: Float? = null,
    val gimbalRollDeg: Float? = null,
    val gimbalYawDeg: Float? = null,
    // Last status texts (newest last)
    val messages: List<StatusText> = emptyList(),
    /** Message ids seen but not yet interpreted — tells us what the PowerEgg actually sends. */
    val unhandledIds: Set<Int> = emptySet(),
) {
    data class StatusText(val severity: Int, val text: String, val at: Long)

    val hasPosition get() = latitude != null && longitude != null
    val hasHome get() = homeLatitude != null && homeLongitude != null
}

/** Pure reducer: folds one inbound MAVLink message into the telemetry snapshot. */
object TelemetryReducer {

    private const val MAX_TEXTS = 30

    fun reduce(t: Telemetry, m: MavMessage, now: Long = System.currentTimeMillis()): Telemetry = when (m.name) {
        "HEARTBEAT" -> if (m.int("type") == 6 /* GCS */) t else t.copy(
            armed = (m.int("base_mode") and MavModeFlag.SAFETY_ARMED) != 0,
            mode = if ((m.int("base_mode") and MavModeFlag.CUSTOM_MODE_ENABLED) != 0) Px4Mode.fromCustomMode(m.long("custom_mode")) else t.mode,
            systemStatus = m.int("system_status"),
        )
        "ATTITUDE" -> t.copy(roll = m.float("roll"), pitch = m.float("pitch"), yaw = m.float("yaw"))
        "GLOBAL_POSITION_INT" -> t.copy(
            latitude = m.int("lat") / 1e7,
            longitude = m.int("lon") / 1e7,
            altitudeMsl = m.int("alt") / 1000f,
            altitudeRelative = m.int("relative_alt") / 1000f,
            headingDeg = m.int("hdg").takeIf { it != 0xFFFF }?.let { it / 100f },
        )
        "VFR_HUD" -> t.copy(groundSpeed = m.float("groundspeed"), climbRate = m.float("climb"))
        "GPS_RAW_INT" -> t.copy(
            gpsFixType = m.int("fix_type"),
            satellites = m.int("satellites_visible").takeIf { it != 255 } ?: 0,
            hdop = m.int("eph").takeIf { it != 0xFFFF }?.let { it / 100f },
        )
        "SYS_STATUS" -> t.copy(
            batteryVoltage = m.int("voltage_battery").takeIf { it != 0xFFFF }?.let { it / 1000f },
            batteryCurrent = m.int("current_battery").takeIf { it != -1 }?.let { it / 100f },
            batteryPercent = m.int("battery_remaining").takeIf { it in 0..100 },
        )
        "BATTERY_STATUS" -> t.copy(
            batteryPercent = m.int("battery_remaining").takeIf { it in 0..100 } ?: t.batteryPercent,
        )
        "HOME_POSITION" -> t.copy(
            homeLatitude = m.int("latitude") / 1e7,
            homeLongitude = m.int("longitude") / 1e7,
            homeAltitude = m.int("altitude") / 1000f,
        )
        "STATUSTEXT" -> t.copy(messages = (t.messages + Telemetry.StatusText(m.int("severity"), m.string("text"), now)).takeLast(MAX_TEXTS))
        // PowerVision status: alarm word plus the take-off/landing point (docs/04 §5.1).
        "PV_INFOR" -> {
            val status = m.long("status")
            val withAlarm = t.copy(alarmWord = status)
            if ((status and 0x80L) != 0L) withAlarm.copy(
                takeoffLatitude = m.float("land_lat").toDouble(),
                takeoffLongitude = m.float("land_lon").toDouble(),
            ) else withAlarm
        }
        "MOUNT_STATUS" -> t.copy(
            gimbalPitchDeg = m.int("pointing_a") / 100f,
            gimbalRollDeg = m.int("pointing_b") / 100f,
            gimbalYawDeg = m.int("pointing_c") / 100f,
        )
        "COMMAND_ACK", "PARAM_VALUE", "MISSION_ACK", "MISSION_COUNT", "MISSION_REQUEST", "MISSION_REQUEST_INT",
        "MISSION_ITEM", "MISSION_ITEM_INT", "MISSION_CURRENT", "SYSTEM_TIME" -> t
        else -> if (m.id in t.unhandledIds) t else t.copy(unhandledIds = t.unhandledIds + m.id)
    }
}
