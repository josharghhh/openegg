package com.openegg.app.map

import com.openegg.app.drone.FlightMath

/**
 * Bounded ring buffer of aircraft positions for the flight-path trail. Points closer than
 * [minSpacingMeters] to the last one are dropped so a hovering aircraft doesn't fill the buffer.
 * Not thread-safe; feed it from one thread (the UI collects telemetry on main).
 */
class FlightTrail(val capacity: Int = 2_000, private val minSpacingMeters: Double = 1.0) {
    init { require(capacity > 0) }

    private val lats = DoubleArray(capacity)
    private val lons = DoubleArray(capacity)
    private var start = 0
    var size = 0
        private set

    /** Bumped on every accepted change, so Compose can key redraws on it. */
    var version = 0L
        private set

    /** @return true when the point was accepted. */
    fun add(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return false // no-fix sentinel
        if (size > 0) {
            val last = (start + size - 1) % capacity
            if (FlightMath.distanceMeters(lats[last], lons[last], lat, lon) < minSpacingMeters) return false
        }
        if (size < capacity) {
            val i = (start + size) % capacity
            lats[i] = lat; lons[i] = lon; size++
        } else {
            lats[start] = lat; lons[start] = lon
            start = (start + 1) % capacity
        }
        version++
        return true
    }

    fun clear() { start = 0; size = 0; version++ }

    /** Oldest first. */
    fun points(): List<GeoPoint> = List(size) { k -> val i = (start + k) % capacity; GeoPoint(lats[i], lons[i]) }

    fun last(): GeoPoint? = if (size == 0) null else ((start + size - 1) % capacity).let { GeoPoint(lats[it], lons[it]) }
}
