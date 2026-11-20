package com.openegg.app.vxfly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VxMappingsTest {

    @Test
    fun batteryEdges() {
        assertEquals(0.0, VxBattery.percentFromMillivolts(0), 0.0)
        assertEquals(0.0, VxBattery.percentFromMillivolts(3399), 0.0)
        assertEquals(0.20, VxBattery.percentFromMillivolts(3400), 1e-9)
        assertEquals(100.0, VxBattery.percentFromMillivolts(4050), 0.0)
        assertEquals(100.0, VxBattery.percentFromMillivolts(4200), 0.0)
        val nearFull = VxBattery.percentFromMillivolts(4049)
        assertTrue(nearFull in 99.6..100.0)
    }

    @Test
    fun batteryTablePointsAreExact() {
        assertEquals(57.36, VxBattery.percentFromMillivolts(3700), 1e-9)
        assertEquals(89.78, VxBattery.percentFromMillivolts(3900), 1e-9)
        assertEquals(98.94, VxBattery.percentFromMillivolts(4000), 1e-9)
        assertEquals(20.93, VxBattery.percentFromMillivolts(3600), 1e-9)
    }

    @Test
    fun batteryWorkedExampleWithinOnePercentOfNative() {
        val mV = VxBattery.millivolts("3.912".toDouble())
        assertEquals(3912, mV)
        assertEquals(91.06, VxBattery.percentFromMillivolts(mV), 1.0) // doc: native 91.06 %
    }

    @Test
    fun batteryIsMonotonic() {
        var last = -1.0
        for (mV in 3300..4100) {
            val p = VxBattery.percentFromMillivolts(mV)
            assertTrue("non-monotonic at $mV", p >= last)
            last = p
        }
    }

    @Test
    fun millivoltsDoesNotLoseOneToFloatingPoint() {
        for (mV in 3400..4050) assertEquals(mV, VxBattery.millivolts(mV / 1000.0))
    }

    @Test
    fun oneMillivoltHysteresis() {
        assertEquals(3912, VxBattery.applyHysteresis(null, 3912))
        assertEquals(3912, VxBattery.applyHysteresis(3912, 3913))
        assertEquals(3914, VxBattery.applyHysteresis(3912, 3914))
        assertEquals(3911, VxBattery.applyHysteresis(3912, 3911))
    }

    @Test
    fun signalBarsFollowOriginalThresholds() {
        assertNull(VxSignal.barsFromSnr(-1))
        assertEquals(0, VxSignal.barsFromSnr(0))
        assertEquals(1, VxSignal.barsFromSnr(1))
        assertEquals(1, VxSignal.barsFromSnr(5))
        assertEquals(2, VxSignal.barsFromSnr(6))
        assertEquals(2, VxSignal.barsFromSnr(10))
        assertEquals(3, VxSignal.barsFromSnr(11))
        assertEquals(3, VxSignal.barsFromSnr(20))
        assertEquals(4, VxSignal.barsFromSnr(21))
        assertEquals(4, VxSignal.barsFromSnr(25))
        assertEquals(5, VxSignal.barsFromSnr(26))
    }

    @Test
    fun radioStatusNeedsExactlyThreeInts() {
        assertEquals(Triple(-55, 25, -90), VxSignal.parseRadioStatus("-55,25,-90"))
        assertEquals(Triple(-55, 25, -90), VxSignal.parseRadioStatus(" -55, 25 ,-90"))
        assertNull(VxSignal.parseRadioStatus("-55,25"))
        assertNull(VxSignal.parseRadioStatus("-55,25,-90,1"))
        assertNull(VxSignal.parseRadioStatus("FAILED"))
        assertNull(VxSignal.parseRadioStatus(""))
    }
}
