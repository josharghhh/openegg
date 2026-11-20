package com.openegg.app.drone

/**
 * PX4 custom_mode layout (px4_custom_mode.h): byte 2 = main mode, byte 3 = sub mode.
 *
 * The PowerEgg runs a PowerVision fork of PX4 with extra main modes 8-14 that the
 * app *sends*; the aircraft *reports* some of them back as standard AUTO sub-modes
 * (e.g. we send main 10 for return-home and the heartbeat then shows AUTO/RTL).
 * See [PowerEggMode] and docs/04-mavlink-px4-protocol.md §3.
 */
data class Px4Mode(val main: Int, val sub: Int) {

    val label: String
        get() = when (main) {
            MAIN_MANUAL -> "Manual"
            MAIN_ALTCTL -> "Altitude"
            MAIN_POSCTL -> "Position"
            MAIN_AUTO -> when (sub) {
                AUTO_READY -> "Auto ready"
                AUTO_TAKEOFF -> "Takeoff"
                AUTO_LOITER -> "Hover"
                AUTO_MISSION -> "Waypoints"
                AUTO_RTL -> "Return home"
                AUTO_LAND -> "Landing"
                AUTO_FOLLOW_TARGET -> "Follow me"
                AUTO_PRECLAND -> "Precision land"
                else -> "Auto ($sub)"
            }
            MAIN_ACRO -> "Acro"
            MAIN_OFFBOARD -> "Offboard"
            MAIN_STABILIZED -> "Stabilized"
            // PowerVision main modes (stock PX4 main 8 would be RATTITUDE; not used on the PowerEgg).
            PV_TAKEOFF -> "Takeoff"
            PV_LAND -> "Landing"
            PV_RTL -> "Return home"
            PV_SUPER_SIMPLE -> "Headless"
            PV_CIRCLE -> "Orbit"
            PV_FOLLOW -> "Follow me"
            PV_LOITER -> "Hover"
            else -> "Mode $main/$sub"
        }

    fun toCustomMode(): Long = ((sub.toLong() and 0xFF) shl 24) or ((main.toLong() and 0xFF) shl 16)

    companion object {
        const val MAIN_MANUAL = 1
        const val MAIN_ALTCTL = 2
        const val MAIN_POSCTL = 3
        const val MAIN_AUTO = 4
        const val MAIN_ACRO = 5
        const val MAIN_OFFBOARD = 6
        const val MAIN_STABILIZED = 7

        const val PV_TAKEOFF = 8
        const val PV_LAND = 9
        const val PV_RTL = 10
        const val PV_SUPER_SIMPLE = 11
        const val PV_CIRCLE = 12
        const val PV_FOLLOW = 13
        const val PV_LOITER = 14

        const val AUTO_READY = 1
        const val AUTO_TAKEOFF = 2
        const val AUTO_LOITER = 3
        const val AUTO_MISSION = 4
        const val AUTO_RTL = 5
        const val AUTO_LAND = 6
        const val AUTO_RTGS = 7
        const val AUTO_FOLLOW_TARGET = 8
        const val AUTO_PRECLAND = 9

        fun fromCustomMode(customMode: Long) = Px4Mode(((customMode shr 16) and 0xFF).toInt(), ((customMode shr 24) and 0xFF).toInt())
    }
}

/**
 * Mode requests the PowerEgg accepts via SET_MODE, with the custom_mode the app
 * sends and the custom_mode the heartbeat shows once the aircraft has switched
 * (OBSERVED in PX4Mode::changeMode and the verify table in libpvsdk_api-release.so).
 */
enum class PowerEggMode(val sent: Px4Mode, val reported: Px4Mode) {
    MANUAL(Px4Mode(Px4Mode.MAIN_MANUAL, 0), Px4Mode(Px4Mode.MAIN_MANUAL, 0)),
    POSITION(Px4Mode(Px4Mode.MAIN_POSCTL, 0), Px4Mode(Px4Mode.MAIN_POSCTL, 0)),
    WAYPOINTS(Px4Mode(Px4Mode.MAIN_AUTO, 0), Px4Mode(Px4Mode.MAIN_AUTO, Px4Mode.AUTO_MISSION)),
    TAKEOFF(Px4Mode(Px4Mode.PV_TAKEOFF, 0), Px4Mode(Px4Mode.PV_TAKEOFF, 0)),
    LAND(Px4Mode(Px4Mode.PV_LAND, 0), Px4Mode(Px4Mode.PV_LAND, 0)),
    RETURN_HOME(Px4Mode(Px4Mode.PV_RTL, 0), Px4Mode(Px4Mode.MAIN_AUTO, Px4Mode.AUTO_RTL)),
    HEADLESS(Px4Mode(Px4Mode.PV_SUPER_SIMPLE, 0), Px4Mode(Px4Mode.PV_SUPER_SIMPLE, 0)),
    ORBIT(Px4Mode(Px4Mode.PV_CIRCLE, 0), Px4Mode(Px4Mode.PV_CIRCLE, 0)),
    FOLLOW_ME(Px4Mode(Px4Mode.PV_FOLLOW, 0), Px4Mode(Px4Mode.PV_FOLLOW, 0)),
    HOVER(Px4Mode(Px4Mode.PV_LOITER, 0), Px4Mode(Px4Mode.MAIN_AUTO, Px4Mode.AUTO_LOITER)),
}
