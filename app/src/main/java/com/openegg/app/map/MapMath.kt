package com.openegg.app.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A WGS-84 point. Kept free of MapLibre types so the logic is unit-testable on the JVM. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Axis-aligned lat/lon box. Does not handle boxes crossing the antimeridian (irrelevant for a drone area). */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south <= north) { "south > north" }
        require(west <= east) { "west > east" }
    }

    val center get() = GeoPoint((south + north) / 2, (west + east) / 2)

    companion object {
        /** Box enclosing a circle of [radiusMeters] around [center]. */
        fun around(center: GeoPoint, radiusMeters: Double): GeoBounds {
            val dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_M)
            val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
            val dLon = Math.toDegrees(radiusMeters / (EARTH_RADIUS_M * cosLat))
            return GeoBounds(
                (center.lat - dLat).coerceAtLeast(-MAX_MERCATOR_LAT),
                (center.lon - dLon).coerceAtLeast(-180.0),
                (center.lat + dLat).coerceAtMost(MAX_MERCATOR_LAT),
                (center.lon + dLon).coerceAtMost(180.0),
            )
        }

        private const val EARTH_RADIUS_M = 6_371_008.8
    }
}

const val MAX_MERCATOR_LAT = 85.05112878

/** Slippy-map (XYZ / web-mercator) tile maths, used to size offline downloads before starting them. */
object TileMath {
    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val r = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        return floor((1.0 - ln(tan(r) + 1 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Tiles covering [b] at a single zoom level. */
    fun tileCount(b: GeoBounds, zoom: Int): Long {
        val xs = lonToTileX(b.east, zoom) - lonToTileX(b.west, zoom) + 1
        val ys = latToTileY(b.south, zoom) - latToTileY(b.north, zoom) + 1 // y grows southwards
        return xs.toLong() * ys.toLong()
    }

    /** Tiles covering [b] for every zoom in [minZoom]..[maxZoom] (one tile source). */
    fun tileCount(b: GeoBounds, minZoom: Int, maxZoom: Int): Long =
        (minZoom..maxZoom).sumOf { tileCount(b, it) }
}

/**
 * Caps that keep an offline download polite to the free tile servers and small on the phone.
 * MapLibre itself refuses regions above its own tile limit; ours is lower and checked up front.
 */
object OfflineLimits {
    const val MIN_ZOOM = 0
    /** z16 ≈ 2.4 m/px at the equator: enough detail to fly by. Higher zooms are over-zoomed from z14 vector data anyway. */
    const val MAX_ZOOM = 16
    /** Per region, per tile source. ~3000 vector tiles ≈ 100 MB worst case; a 2 km radius at z14 is ~25 tiles. */
    const val MAX_TILES = 3_000L
    /** Default download radius around a point. */
    const val DEFAULT_RADIUS_M = 2_000.0
    /** Rough average vector tile size, for the size estimate shown before downloading. */
    const val AVG_TILE_BYTES = 35_000L

    data class Plan(val bounds: GeoBounds, val minZoom: Int, val maxZoom: Int, val tiles: Long) {
        val estimatedBytes get() = tiles * AVG_TILE_BYTES
    }

    /**
     * Largest max-zoom <= [requestedMaxZoom] that keeps [b] under [maxTiles]. Tiles are counted only
     * up to [sourceMaxZoom] because MapLibre over-zooms beyond the source's own max and fetches nothing
     * deeper. Null when even [MIN_USEFUL_ZOOM] is too much (area far too large: ask the user to zoom in).
     */
    fun plan(
        b: GeoBounds,
        requestedMaxZoom: Int = MAX_ZOOM,
        sourceMaxZoom: Int = MAX_ZOOM,
        minZoom: Int = MIN_ZOOM,
        maxTiles: Long = MAX_TILES,
    ): Plan? {
        val top = requestedMaxZoom.coerceIn(minZoom, MAX_ZOOM)
        for (z in top downTo minOf(MIN_USEFUL_ZOOM, top)) {
            val n = TileMath.tileCount(b, minZoom, minOf(z, sourceMaxZoom))
            if (n <= maxTiles) return Plan(b, minZoom, z, n)
        }
        return null
    }

    /** Below this an offline region is useless for flying (city-level at best). */
    const val MIN_USEFUL_ZOOM = 12
}

/** Marker rotation for the aircraft icon. */
object MarkerMath {
    /** Normalise any angle to [0, 360). */
    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /**
     * Screen rotation (clockwise degrees) for an icon whose artwork points up/north, given the
     * aircraft heading and the map's camera bearing. With MapLibre's `iconRotationAlignment = "map"`
     * the map applies the bearing itself, so pass mapBearing = 0 there.
     */
    fun iconRotation(headingDeg: Double, mapBearingDeg: Double = 0.0): Double = normalize(headingDeg - mapBearingDeg)

    /** Shortest signed difference b - a in (-180, 180], for smooth animation. */
    fun shortestDelta(a: Double, b: Double): Double {
        val d = normalize(b - a)
        return if (d > 180.0) d - 360.0 else d
    }

    /** Camera bearing for heading-up mode; north-up is always 0. */
    fun cameraBearing(headingUp: Boolean, headingDeg: Double?): Double = if (headingUp && headingDeg != null) normalize(headingDeg) else 0.0

    fun approxEqual(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps
}
