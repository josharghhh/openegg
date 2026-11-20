package com.openegg.app.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightMathTest {

    @Test
    fun distanceOneDegreeLatitudeIsAbout111km() {
        assertEquals(111_195.0, FlightMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 5.0)
    }

    @Test
    fun shortDistanceIsAccurate() {
        // ~100 m east at Brisbane's latitude: 100 / (111320 * cos(-27.47°)) degrees of longitude.
        val dLon = 100.0 / (111_320.0 * Math.cos(Math.toRadians(-27.4698)))
        assertEquals(100.0, FlightMath.distanceMeters(-27.4698, 153.0251, -27.4698, 153.0251 + dLon), 0.5)
    }

    @Test
    fun bearingNorthAndEast() {
        assertEquals(0.0, FlightMath.bearingDegrees(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(90.0, FlightMath.bearingDegrees(0.0, 0.0, 0.0, 1.0), 1e-9)
    }

    @Test
    fun homeFallsBackToTakeoffPoint() {
        val t = Telemetry(latitude = -27.0, longitude = 153.0, takeoffLatitude = -27.0, takeoffLongitude = 153.0)
        assertEquals(0.0, t.distanceFromHome!!, 1e-6)
        assertNull(Telemetry(latitude = 1.0, longitude = 1.0).distanceFromHome)
    }

    @Test
    fun batteryThresholds() {
        assertEquals(BatteryLevel.CRITICAL, Telemetry(batteryPercent = 10).batteryLevel)
        assertEquals(BatteryLevel.LOW, Telemetry(batteryPercent = 25).batteryLevel)
        assertEquals(BatteryLevel.OK, Telemetry(batteryPercent = 80).batteryLevel)
        assertEquals(BatteryLevel.UNKNOWN, Telemetry().batteryLevel)
    }
}
