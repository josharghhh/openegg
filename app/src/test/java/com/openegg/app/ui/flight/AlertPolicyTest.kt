package com.openegg.app.ui.flight

import com.openegg.app.drone.Px4Mode
import com.openegg.app.drone.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    @Test
    fun announcesNewAlarmOnceUntilItClears() {
        val p = AlertPolicy()
        val compass = Telemetry(alarmWord = 1L shl 40)
        assertEquals(listOf("Compass abnormal, calibrate"), p.next(compass, false, true, 0))
        assertTrue(p.next(compass, false, true, 1000).isEmpty())
        p.next(Telemetry(), false, true, 2000)
        assertEquals(listOf("Compass abnormal, calibrate"), p.next(compass, false, true, 3000))
    }

    @Test
    fun batteryLowOnceCriticalRepeats() {
        val p = AlertPolicy(repeatAfterMs = 10_000)
        assertEquals(listOf("Battery low, 25 percent"), p.next(Telemetry(batteryPercent = 25), false, true, 0))
        assertTrue(p.next(Telemetry(batteryPercent = 24), false, true, 1000).isEmpty())
        assertEquals(listOf("Battery critical, land now"), p.next(Telemetry(batteryPercent = 12), false, true, 2000))
        assertTrue(p.next(Telemetry(batteryPercent = 12), false, true, 5000).isEmpty())
        assertEquals(listOf("Battery critical, land now"), p.next(Telemetry(batteryPercent = 11), false, true, 12_500))
    }

    @Test
    fun signalLostAndRestored() {
        val p = AlertPolicy()
        p.next(Telemetry(), false, true, 0)
        assertEquals(listOf("Signal lost"), p.next(Telemetry(), true, true, 1000))
        assertEquals(listOf("Signal restored"), p.next(Telemetry(), false, true, 2000))
    }

    @Test
    fun modeChangesAreAnnouncedButNotTheFirstMode() {
        val p = AlertPolicy()
        assertTrue(p.next(Telemetry(mode = Px4Mode(3, 0)), false, true, 0).isEmpty())
        assertEquals(listOf("Return home"), p.next(Telemetry(mode = Px4Mode(4, 5)), false, true, 100))
    }
}
