package com.openegg.app.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmsTest {

    @Test
    fun ignoresBitsTheOriginalNeverShowed() {
        // 0x40/0x80 are PV_INFOR location flags; bits 0-4 and 59-60 were never displayed.
        val word = 0x1FL or 0x40L or 0x80L or (1L shl 59) or (1L shl 60)
        assertTrue(Alarms.decode(word).isEmpty())
    }

    @Test
    fun decodesAndSortsBySeverity() {
        val word = (1L shl 43) or (1L shl 40) or (1L shl 36) // remote batt (W), compass (C), restored (I)
        assertEquals(listOf(40, 43, 36), Alarms.decode(word).map { it.bit })
    }

    @Test
    fun highBitsWork() {
        assertEquals(listOf(63), Alarms.decode(Long.MIN_VALUE).map { it.bit })
    }

    @Test
    fun newlyRaisedOnlyReportsRisingEdges() {
        val before = 1L shl 40
        val after = (1L shl 40) or (1L shl 35)
        assertEquals(listOf(35), Alarms.newlyRaised(before, after).map { it.bit })
        assertTrue(Alarms.newlyRaised(after, before).isEmpty())
    }

    @Test
    fun everyShownBitHasExactlyOneDefinition() {
        val bits = Alarms.all.map { it.bit }
        assertEquals(bits.size, bits.toSet().size)
        assertEquals(55, bits.size)
    }
}
