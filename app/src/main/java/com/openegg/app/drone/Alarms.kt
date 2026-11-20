package com.openegg.app.drone

/**
 * The PowerEgg's 64-bit alarm word (PV_INFOR.status → alarmNotify in the original SDK),
 * decoded the way gcs/model/AlarmToShow.java did (docs/01 §2f). Texts are condensed from
 * the original app's English strings. Bits 0-4, 6-7 and 59-60 were never shown by the
 * original app (6/7 are PV_INFOR location flags), so they are ignored here too.
 *
 * Unverified on hardware: that PV_INFOR.status is the full alarm word on this firmware.
 */
enum class AlarmSeverity { CRITICAL, WARNING, INFO }

data class AlarmDef(val bit: Int, val severity: AlarmSeverity, val text: String, val spoken: String = text) {
    val mask: Long get() = 1L shl bit
}

object Alarms {
    private val C = AlarmSeverity.CRITICAL
    private val W = AlarmSeverity.WARNING
    private val I = AlarmSeverity.INFO

    val all: List<AlarmDef> = listOf(
        AlarmDef(5, W, "Inside a protected zone — move outside it", "Protected zone"),
        AlarmDef(8, W, "Battery at return threshold — returning home", "Battery low, returning home"),
        AlarmDef(9, C, "Low battery — landing. Push throttle fully up to hover", "Low battery, landing"),
        AlarmDef(10, C, "Low battery — landing. Push throttle fully up to hover", "Low battery, landing"),
        AlarmDef(11, C, "Sensor temperature abnormal — land or return home", "Sensor temperature abnormal"),
        AlarmDef(12, C, "Sensor initialisation failed — cannot start", "Sensor initialisation failed"),
        AlarmDef(13, W, "Beginner mode: GPS too weak to start", "GPS too weak to start"),
        AlarmDef(14, W, "In a no-fly zone — cannot start", "No fly zone"),
        AlarmDef(15, C, "Battery too low to start — charge it first", "Battery too low to start"),
        AlarmDef(16, W, "No SD card in the camera — cannot start", "No SD card"),
        AlarmDef(17, W, "Easy Control: aircraft GPS too weak to start", "GPS too weak"),
        AlarmDef(18, W, "Easy Control: remote GPS too weak to start", "Remote GPS too weak"),
        AlarmDef(19, W, "Cannot position — Position mode unavailable", "Cannot position"),
        AlarmDef(20, W, "GPS weak — Follow Me unavailable"),
        AlarmDef(21, W, "GPS weak — Orbit unavailable"),
        AlarmDef(22, W, "GPS weak — Easy Control unavailable"),
        AlarmDef(23, C, "GPS weak — cannot return home", "GPS weak, cannot return home"),
        AlarmDef(24, W, "GPS weak — cannot hover in place"),
        AlarmDef(25, W, "GPS weak — waypoint mission unavailable"),
        AlarmDef(26, W, "Remote GPS weak — Follow Me unavailable"),
        AlarmDef(27, W, "Remote GPS weak — Orbit unavailable"),
        AlarmDef(28, W, "Remote GPS weak — Easy Control unavailable"),
        AlarmDef(29, C, "No home point — cannot return home automatically. Fly back manually", "No home point"),
        AlarmDef(30, W, "No take-off point — mission cannot run"),
        AlarmDef(31, W, "No altitude fix — auto take-off unavailable"),
        AlarmDef(32, W, "Cannot position — auto take-off unavailable"),
        AlarmDef(33, W, "Cannot position — auto land unavailable"),
        AlarmDef(34, C, "Cannot position — switched to MANUAL. Fly carefully", "Switched to manual, fly carefully"),
        AlarmDef(35, C, "Remote signal lost — adjust the antenna", "Remote signal lost"),
        AlarmDef(36, I, "Remote signal restored", "Signal restored"),
        AlarmDef(37, W, "Ultrasonic sensor abnormal — cannot start"),
        AlarmDef(38, W, "Optical-flow sensor abnormal — cannot start"),
        AlarmDef(39, C, "GPS abnormal — cannot start", "GPS abnormal"),
        AlarmDef(40, C, "Compass abnormal — calibrate the compass", "Compass abnormal, calibrate"),
        AlarmDef(41, C, "Accelerometer abnormal — cannot start", "Accelerometer abnormal"),
        AlarmDef(42, C, "Gyroscope abnormal — cannot start", "Gyroscope abnormal"),
        AlarmDef(43, W, "Remote controller battery low — recharge soon", "Remote battery low"),
        AlarmDef(44, W, "Leaving the safety zone — returning automatically", "Leaving safety zone"),
        AlarmDef(45, W, "Aircraft GPS lost — switched to optical-flow mode", "GPS lost"),
        AlarmDef(46, W, "Remote signal weak — adjust the antenna", "Signal weak"),
        AlarmDef(47, W, "Optical flow cannot position — Normal mode unavailable"),
        AlarmDef(48, W, "Too high for ultrasonic sensor — Normal mode unavailable"),
        AlarmDef(49, C, "Optical flow lost position — switched to MANUAL", "Switched to manual"),
        AlarmDef(50, C, "Too high for ultrasonic sensor — switched to MANUAL", "Switched to manual"),
        AlarmDef(51, W, "Optical-flow positioning cannot start here"),
        AlarmDef(52, W, "Too high for ultrasonic sensor — Normal mode unavailable"),
        AlarmDef(53, C, "Remote downlink interrupted", "Downlink interrupted"),
        AlarmDef(54, C, "Entering a no-fly zone — pull back now", "No fly zone, pull back"),
        AlarmDef(55, W, "Current remote mode not allowed — change the remote mode"),
        AlarmDef(56, W, "Cannot locate you — return to user failed"),
        AlarmDef(57, C, "Barometer abnormal — cannot start", "Barometer abnormal"),
        AlarmDef(58, C, "Battery not detected — cannot start", "Battery not detected"),
        AlarmDef(61, C, "Aircraft fault — returning home", "Aircraft fault, returning home"),
        AlarmDef(62, C, "Aircraft fault — landing", "Aircraft fault, landing"),
        AlarmDef(63, C, "Aircraft fault — hovering", "Aircraft fault, hovering"),
    )

    private val byBit = all.associateBy { it.bit }

    /** Active alarms, most severe first, then by bit. */
    fun decode(word: Long): List<AlarmDef> =
        (0..63).filter { (word ushr it) and 1L == 1L }.mapNotNull { byBit[it] }
            .sortedWith(compareBy({ it.severity.ordinal }, { it.bit }))

    /** Alarms that became active between two words (for one-shot voice/notification). */
    fun newlyRaised(previous: Long, current: Long): List<AlarmDef> = decode(current and previous.inv())
}

val Telemetry.activeAlarms: List<AlarmDef> get() = Alarms.decode(alarmWord)
