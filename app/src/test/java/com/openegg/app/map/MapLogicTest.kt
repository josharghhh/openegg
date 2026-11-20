package com.openegg.app.map

import com.openegg.app.drone.FlightMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightTrailTest {
    @Test fun keepsOnlyNewestPointsInOrder() {
        val t = FlightTrail(capacity = 3, minSpacingMeters = 0.0)
        for (i in 1..5) t.add(10.0 + i * 0.001, 20.0)
        assertEquals(3, t.size)
        assertEquals(listOf(10.003, 10.004, 10.005), t.points().map { Math.round(it.lat * 1000) / 1000.0 })
        assertEquals(10.005, t.last()!!.lat, 1e-9)
    }

    @Test fun dropsPointsCloserThanSpacing() {
        val t = FlightTrail(capacity = 10, minSpacingMeters = 5.0)
        assertTrue(t.add(-27.0, 153.0))
        assertFalse(t.add(-27.00001, 153.0)) // ~1.1 m
        assertTrue(t.add(-27.0001, 153.0)) // ~11 m
        assertEquals(2, t.size)
    }

    @Test fun rejectsNoFixAndNaN() {
        val t = FlightTrail()
        assertFalse(t.add(0.0, 0.0))
        assertFalse(t.add(Double.NaN, 1.0))
        assertEquals(0, t.size)
        assertNull(t.last())
    }

    @Test fun versionBumpsOnChangesOnly() {
        val t = FlightTrail(minSpacingMeters = 5.0)
        t.add(1.0, 1.0); val v = t.version
        t.add(1.0, 1.0)
        assertEquals(v, t.version)
        t.clear()
        assertTrue(t.version > v)
        assertEquals(0, t.points().size)
    }
}

class TileMathTest {
    @Test fun worldAtZoomZeroIsOneTile() {
        assertEquals(1L, TileMath.tileCount(GeoBounds(-85.0, -180.0, 85.0, 180.0), 0))
        assertEquals(4L, TileMath.tileCount(GeoBounds(-85.0, -180.0, 85.0, 180.0), 1))
    }

    @Test fun knownTileCoordinates() {
        // Brisbane CBD at z14 (independently computed with the slippy-map formula).
        assertEquals(15156, TileMath.lonToTileX(153.025, 14))
        assertEquals(9493, TileMath.latToTileY(-27.47, 14))
        assertEquals(0, TileMath.latToTileY(89.9, 3))
        assertEquals(7, TileMath.latToTileY(-89.9, 3))
    }

    @Test fun tileYToLatInvertsTopEdge() {
        val y = TileMath.latToTileY(-27.47, 14)
        assertTrue(TileMath.tileYToLat(y, 14) >= -27.47)
        assertTrue(TileMath.tileYToLat(y + 1, 14) <= -27.47)
    }

    @Test fun pyramidSumsLevels() {
        val b = GeoBounds.around(GeoPoint(-27.47, 153.02), 2_000.0)
        val sum = (0..14).sumOf { TileMath.tileCount(b, it) }
        assertEquals(sum, TileMath.tileCount(b, 0, 14))
        assertTrue(TileMath.tileCount(b, 14) in 4L..16L)
    }

    @Test fun boundsAroundHasRequestedRadius() {
        val c = GeoPoint(-27.47, 153.02)
        val b = GeoBounds.around(c, 1_000.0)
        assertEquals(1_000.0, FlightMath.distanceMeters(c.lat, c.lon, b.north, c.lon), 1.0)
        assertEquals(1_000.0, FlightMath.distanceMeters(c.lat, c.lon, c.lat, b.east), 1.0)
        assertEquals(c.lat, b.center.lat, 1e-9)
    }
}

class OfflineLimitsTest {
    @Test fun smallAreaGetsFullZoom() {
        val p = OfflineLimits.plan(GeoBounds.around(GeoPoint(-27.47, 153.02), 2_000.0), sourceMaxZoom = 14)
        assertNotNull(p)
        assertEquals(OfflineLimits.MAX_ZOOM, p!!.maxZoom)
        assertTrue(p.tiles <= OfflineLimits.MAX_TILES)
        assertEquals(p.tiles * OfflineLimits.AVG_TILE_BYTES, p.estimatedBytes)
    }

    @Test fun sourceMaxZoomLimitsCount() {
        val b = GeoBounds.around(GeoPoint(-27.47, 153.02), 2_000.0)
        val vector = OfflineLimits.plan(b, sourceMaxZoom = 14)!!
        val raster = OfflineLimits.plan(b, sourceMaxZoom = 16)!!
        assertEquals(TileMath.tileCount(b, 0, 14), vector.tiles)
        assertTrue(raster.tiles > vector.tiles)
    }

    @Test fun mediumAreaIsCappedToLowerZoom() {
        // 40 km radius: too many raster tiles at z16, so the zoom is lowered but stays useful.
        val b = GeoBounds.around(GeoPoint(-27.47, 153.02), 40_000.0)
        val p = OfflineLimits.plan(b, sourceMaxZoom = 16)!!
        assertTrue(p.maxZoom in OfflineLimits.MIN_USEFUL_ZOOM until OfflineLimits.MAX_ZOOM)
        assertTrue(p.tiles <= OfflineLimits.MAX_TILES)
        assertTrue(TileMath.tileCount(b, 0, p.maxZoom + 1) > OfflineLimits.MAX_TILES)
    }

    @Test fun hugeAreaIsRefused() {
        assertNull(OfflineLimits.plan(GeoBounds(-40.0, 140.0, -10.0, 155.0)))
    }

    @Test fun requestedZoomIsClamped() {
        val p = OfflineLimits.plan(GeoBounds.around(GeoPoint(0.0, 0.0), 500.0), requestedMaxZoom = 22)!!
        assertEquals(OfflineLimits.MAX_ZOOM, p.maxZoom)
    }
}

class MarkerMathTest {
    @Test fun normalizeWraps() {
        assertEquals(350.0, MarkerMath.normalize(-10.0), 1e-9)
        assertEquals(10.0, MarkerMath.normalize(370.0), 1e-9)
        assertEquals(0.0, MarkerMath.normalize(360.0), 1e-9)
    }

    @Test fun iconRotationSubtractsMapBearing() {
        assertEquals(90.0, MarkerMath.iconRotation(90.0), 1e-9)
        assertEquals(0.0, MarkerMath.iconRotation(90.0, 90.0), 1e-9) // heading-up: arrow points up
        assertEquals(340.0, MarkerMath.iconRotation(10.0, 30.0), 1e-9)
    }

    @Test fun shortestDeltaTakesShortWay() {
        assertEquals(20.0, MarkerMath.shortestDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, MarkerMath.shortestDelta(10.0, 350.0), 1e-9)
        assertEquals(180.0, MarkerMath.shortestDelta(0.0, 180.0), 1e-9)
    }

    @Test fun cameraBearingModes() {
        assertEquals(0.0, MarkerMath.cameraBearing(false, 123.0), 1e-9)
        assertEquals(123.0, MarkerMath.cameraBearing(true, 123.0), 1e-9)
        assertEquals(0.0, MarkerMath.cameraBearing(true, null), 1e-9)
    }
}

class OfflineRegionMetaTest {
    @Test fun roundTrips() {
        val m = OfflineRegionMeta("a1", "Park = field\nnorth", "liberty", 1234L)
        val d = OfflineRegionMeta.decode(m.encode(), 7)
        assertEquals("a1", d.areaId)
        assertEquals("Park = field north", d.name)
        assertEquals("liberty", d.styleId)
        assertEquals(1234L, d.createdAtMillis)
    }

    @Test fun foreignMetadataGetsFallbacks() {
        val d = OfflineRegionMeta.decode("{\"name\":1}".toByteArray(), 9)
        assertEquals("region-9", d.areaId)
        assertEquals("Area 9", d.name)
        assertEquals(OfflineRegionMeta.decode(null, 3).areaId, "region-3")
    }
}
