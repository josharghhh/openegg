package com.openegg.app.ui.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.openegg.app.drone.FlightMath
import com.openegg.app.map.FlightTrail
import com.openegg.app.map.GeoBounds
import com.openegg.app.map.GeoPoint
import com.openegg.app.map.MapStyle
import com.openegg.app.map.MapStyles
import com.openegg.app.map.MarkerMath
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot

/**
 * Imperative bridge between Compose state and a MapLibre [MapView]: owns our overlay sources/layers,
 * re-adds them after a style switch, drives the camera and handles waypoint tap/drag.
 * Everything runs on the main thread.
 */
@SuppressLint("ClickableViewAccessibility")
internal class MapController(private val mapView: MapView) {

    class Callbacks(
        var onMapLongPress: ((Double, Double) -> Unit)? = null,
        var onWaypointTap: ((Int) -> Unit)? = null,
        var onWaypointDrag: ((Int, Double, Double) -> Unit)? = null,
        var onUserPan: (() -> Unit)? = null,
        var onViewport: ((GeoBounds, Double) -> Unit)? = null,
    )

    val callbacks = Callbacks()

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = null
    private var pendingStyle: MapStyle? = null

    // Last data pushed from Compose; re-applied after style reloads.
    private var aircraft = MapAircraftState()
    private var trail: List<GeoPoint> = emptyList()
    private var waypoints: List<MapWaypoint> = emptyList()
    private var phone: GeoPoint? = null
    private var follow = true
    private var headingUp = false
    private var hasCentered = false

    private val density = mapView.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(mapView.context).scaledTouchSlop
    private var dragIndex = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragPos: LatLng? = null

    init {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnMapLongClickListener { p -> callbacks.onMapLongPress?.let { it(p.latitude, p.longitude); true } ?: false }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) { callbacks.onUserPan?.invoke() }
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })
            m.addOnCameraIdleListener {
                val cb = callbacks.onViewport ?: return@addOnCameraIdleListener
                val b = m.projection.visibleRegion.latLngBounds
                if (b.latitudeSouth <= b.latitudeNorth && b.longitudeWest <= b.longitudeEast) {
                    cb(GeoBounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast), m.cameraPosition.zoom)
                }
            }
            pendingStyle?.let { setStyle(it) }
        }
        mapView.setOnTouchListener { v, ev -> handleTouch(ev).also { if (it && ev.actionMasked == MotionEvent.ACTION_UP && !dragging) v.performClick() } }
    }

    fun setInteractive(enabled: Boolean) { map?.uiSettings?.setAllGesturesEnabled(enabled) ?: mapView.post { map?.uiSettings?.setAllGesturesEnabled(enabled) } }

    fun setStyle(s: MapStyle) {
        val m = map ?: run { pendingStyle = s; return }
        pendingStyle = null
        if (styleUrl == s.url) return
        styleUrl = s.url
        style = null
        m.setStyle(Style.Builder().fromUri(s.url)) { loaded ->
            style = loaded
            installOverlays(loaded)
            renderAll()
        }
    }

    fun update(a: MapAircraftState, follow: Boolean, headingUp: Boolean) {
        val moved = a.latitude != aircraft.latitude || a.longitude != aircraft.longitude || a.headingDeg != aircraft.headingDeg
        val modeChanged = follow != this.follow || headingUp != this.headingUp
        aircraft = a; this.follow = follow; this.headingUp = headingUp
        renderAircraft(); renderHome()
        if (moved || modeChanged) moveCamera(animate = !modeChanged)
    }

    private var trailVersion = -1L

    fun setTrail(t: FlightTrail) {
        if (t.version == trailVersion) return
        trailVersion = t.version
        trail = t.points(); renderTrail()
    }

    fun setWaypoints(w: List<MapWaypoint>) {
        if (w == waypoints) return
        waypoints = w; if (!dragging) renderWaypoints()
    }

    fun setPhone(p: GeoPoint?) {
        if (p == phone) return
        phone = p; renderPhone()
        if (!hasCentered && !aircraft.hasPosition && p != null) moveCamera(animate = false)
    }

    /** Jump to [p] (zoom 15 unless [keepZoom]). */
    fun centerOn(p: GeoPoint, keepZoom: Boolean = false) {
        if (mapView.isDestroyed) return
        val m = map ?: run { mapView.post { centerOn(p, keepZoom) }; return }
        hasCentered = true
        val b = CameraPosition.Builder().target(LatLng(p.lat, p.lon))
        if (!keepZoom) b.zoom(15.0)
        m.easeCamera(CameraUpdateFactory.newCameraPosition(b.build()), 300)
    }

    /** Recentre now (the follow button). */
    fun recenter() = moveCamera(animate = true, force = true)

    // ---- camera ----

    private fun moveCamera(animate: Boolean, force: Boolean = false) {
        val m = map ?: return
        val bearing = MarkerMath.cameraBearing(headingUp, aircraft.headingDeg?.toDouble())
        val target: LatLng? = when {
            aircraft.hasPosition -> LatLng(aircraft.latitude!!, aircraft.longitude!!)
            aircraft.homeLatitude != null && aircraft.homeLongitude != null -> LatLng(aircraft.homeLatitude!!, aircraft.homeLongitude!!)
            else -> phone?.let { LatLng(it.lat, it.lon) }
        }
        val builder = CameraPosition.Builder().bearing(bearing)
        when {
            target != null && !hasCentered -> { builder.target(target).zoom(16.0); hasCentered = true }
            target != null && (follow || force) -> builder.target(target)
            else -> builder.target(m.cameraPosition.target)
        }
        val update = CameraUpdateFactory.newCameraPosition(builder.build())
        if (animate) m.easeCamera(update, 300) else m.moveCamera(update)
    }

    // ---- touch: waypoint tap / drag ----

    private fun handleTouch(ev: MotionEvent): Boolean {
        val m = map ?: return false
        if (callbacks.onWaypointTap == null && callbacks.onWaypointDrag == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = TOUCH_RADIUS_DP * density
                val hit = m.queryRenderedFeatures(RectF(ev.x - r, ev.y - r, ev.x + r, ev.y + r), L_WP_CIRCLE).firstOrNull()
                    ?: return false
                dragIndex = hit.getNumberProperty("idx")?.toInt() ?: return false
                dragging = false; downX = ev.x; downY = ev.y; dragPos = null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex < 0) return false
                if (!dragging && callbacks.onWaypointDrag != null && hypot(ev.x - downX, ev.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    dragPos = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    renderWaypoints()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragIndex < 0) return false
                val i = dragIndex
                val pos = dragPos
                if (dragging && pos != null) callbacks.onWaypointDrag?.invoke(i, pos.latitude, pos.longitude)
                else if (!dragging) callbacks.onWaypointTap?.invoke(i)
                resetDrag()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragIndex < 0) return false
                resetDrag(); return ev.actionMasked == MotionEvent.ACTION_CANCEL
            }
        }
        return dragIndex >= 0
    }

    private fun resetDrag() { dragIndex = -1; dragging = false; dragPos = null; renderWaypoints() }

    // ---- style overlays ----

    private fun installOverlays(s: Style) {
        s.addImage(IMG_AIRCRAFT, aircraftBitmap(withHeading = true))
        s.addImage(IMG_AIRCRAFT_NOHDG, aircraftBitmap(withHeading = false))
        s.addImage(IMG_HOME, homeBitmap())
        s.addImage(IMG_TAKEOFF, takeoffBitmap())
        s.addImage(IMG_PHONE, phoneBitmap())
        listOf(S_TRAIL, S_HOMELINE, S_HOMELABEL, S_WPLINE, S_WP, S_TAKEOFF, S_HOME, S_PHONE, S_AIRCRAFT)
            .forEach { s.addSource(GeoJsonSource(it, empty())) }

        s.addLayer(LineLayer(L_TRAIL, S_TRAIL).withProperties(
            P.lineColor(TRAIL_COLOR), P.lineWidth(3f), P.lineOpacity(0.85f),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(LineLayer(L_WPLINE, S_WPLINE).withProperties(
            P.lineColor("#FFFFFF"), P.lineWidth(2.5f), P.lineOpacity(0.9f), P.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(LineLayer(L_HOMELINE, S_HOMELINE).withProperties(
            P.lineColor(WARN), P.lineWidth(2f), P.lineDasharray(arrayOf(2f, 2f)),
        ))
        s.addLayer(CircleLayer(L_WP_CIRCLE, S_WP).withProperties(
            P.circleRadius(12f),
            P.circleColor(Expression.switchCase(Expression.get("sel"), Expression.literal(WARN), Expression.literal("#2B6CB0"))),
            P.circleStrokeColor("#FFFFFF"), P.circleStrokeWidth(2f),
        ))
        s.addLayer(SymbolLayer(L_WP_NUM, S_WP).withProperties(
            P.textField(Expression.get("label")), P.textFont(MapStyles.LABEL_FONT), P.textSize(12f),
            P.textColor("#FFFFFF"), P.textAllowOverlap(true), P.textIgnorePlacement(true),
        ))
        s.addLayer(symbol(L_TAKEOFF, S_TAKEOFF, IMG_TAKEOFF))
        s.addLayer(symbol(L_HOME, S_HOME, IMG_HOME))
        s.addLayer(symbol(L_PHONE, S_PHONE, IMG_PHONE))
        s.addLayer(SymbolLayer(L_AIRCRAFT, S_AIRCRAFT).withProperties(
            P.iconImage(Expression.get("img")), P.iconRotate(Expression.get("hdg")),
            P.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
        ))
        s.addLayer(SymbolLayer(L_HOMELABEL, S_HOMELABEL).withProperties(
            P.textField(Expression.get("label")), P.textFont(MapStyles.LABEL_FONT), P.textSize(13f),
            P.textColor("#FFFFFF"), P.textHaloColor("#000000"), P.textHaloWidth(1.5f),
            P.textAllowOverlap(true), P.textIgnorePlacement(true), P.textOffset(arrayOf(0f, -1f)),
        ))
    }

    private fun symbol(id: String, src: String, img: String) = SymbolLayer(id, src).withProperties(
        P.iconImage(img), P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
    )

    private fun renderAll() { renderTrail(); renderWaypoints(); renderPhone(); renderHome(); renderAircraft(); moveCamera(animate = false) }

    private fun src(id: String): GeoJsonSource? = style?.takeIf { it.isFullyLoaded }?.getSourceAs(id)

    private fun empty(): FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

    private fun fc(f: Feature?): FeatureCollection = f?.let { FeatureCollection.fromFeature(it) } ?: empty()

    private fun pointFeature(lat: Double, lon: Double) = Feature.fromGeometry(Point.fromLngLat(lon, lat))

    private fun renderAircraft() {
        val s = src(S_AIRCRAFT) ?: return
        if (!aircraft.hasPosition) { s.setGeoJson(empty()); return }
        val f = pointFeature(aircraft.latitude!!, aircraft.longitude!!)
        val hdg = aircraft.headingDeg
        f.addStringProperty("img", if (hdg != null) IMG_AIRCRAFT else IMG_AIRCRAFT_NOHDG)
        f.addNumberProperty("hdg", MarkerMath.iconRotation(hdg?.toDouble() ?: 0.0))
        s.setGeoJson(f)
    }

    private fun renderHome() {
        val home = aircraft.homeLatitude?.let { la -> aircraft.homeLongitude?.let { lo -> GeoPoint(la, lo) } }
        val takeoff = aircraft.takeoffLatitude?.let { la -> aircraft.takeoffLongitude?.let { lo -> GeoPoint(la, lo) } }
            ?.takeIf { !(it.lat == 0.0 && it.lon == 0.0) }
        src(S_HOME)?.setGeoJson(fc(home?.let { pointFeature(it.lat, it.lon) }))
        // Take-off point only when it is meaningfully apart from home (they usually coincide).
        val showTakeoff = takeoff != null && (home == null || FlightMath.distanceMeters(home.lat, home.lon, takeoff.lat, takeoff.lon) > 3.0)
        src(S_TAKEOFF)?.setGeoJson(fc(if (showTakeoff) pointFeature(takeoff!!.lat, takeoff.lon) else null))

        val anchor = home ?: takeoff
        val lineSrc = src(S_HOMELINE); val labelSrc = src(S_HOMELABEL)
        if (anchor == null || !aircraft.hasPosition) { lineSrc?.setGeoJson(empty()); labelSrc?.setGeoJson(empty()); return }
        val la = aircraft.latitude!!; val lo = aircraft.longitude!!
        lineSrc?.setGeoJson(LineString.fromLngLats(listOf(Point.fromLngLat(lo, la), Point.fromLngLat(anchor.lon, anchor.lat))))
        val d = FlightMath.distanceMeters(la, lo, anchor.lat, anchor.lon)
        val label = pointFeature((la + anchor.lat) / 2, (lo + anchor.lon) / 2).apply { addStringProperty("label", formatDistance(d)) }
        labelSrc?.setGeoJson(if (d >= 5) FeatureCollection.fromFeature(label) else empty())
    }

    private fun renderTrail() {
        val s = src(S_TRAIL) ?: return
        if (trail.size < 2) { s.setGeoJson(empty()); return }
        s.setGeoJson(LineString.fromLngLats(trail.map { Point.fromLngLat(it.lon, it.lat) }))
    }

    private fun renderWaypoints() {
        val pts = waypoints.mapIndexed { i, w ->
            val p = dragPos?.takeIf { dragging && i == dragIndex }
            Triple(i, w, p?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(w.latitude, w.longitude))
        }
        src(S_WP)?.setGeoJson(FeatureCollection.fromFeatures(pts.map { (i, w, g) ->
            pointFeature(g.lat, g.lon).apply {
                addNumberProperty("idx", i)
                addStringProperty("label", w.label ?: "${i + 1}")
                addBooleanProperty("sel", w.selected || (dragging && i == dragIndex))
            }
        }))
        src(S_WPLINE)?.setGeoJson(
            fc(if (pts.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.third.lon, it.third.lat) })))
        )
    }

    private fun renderPhone() {
        src(S_PHONE)?.setGeoJson(fc(phone?.let { pointFeature(it.lat, it.lon) }))
    }

    // ---- bitmaps (drawn in code: no image assets, and they need no network) ----

    private fun px(dp: Float) = (dp * density).toInt().coerceAtLeast(1)

    private fun aircraftBitmap(withHeading: Boolean): Bitmap {
        val size = px(40f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(WARN) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = android.graphics.Color.BLACK; strokeJoin = Paint.Join.ROUND }
        val s = size.toFloat()
        if (withHeading) {
            // Arrow pointing up (north at rotation 0).
            val path = Path().apply {
                moveTo(s / 2, s * 0.06f); lineTo(s * 0.86f, s * 0.9f); lineTo(s / 2, s * 0.7f); lineTo(s * 0.14f, s * 0.9f); close()
            }
            c.drawPath(path, fill); c.drawPath(path, stroke)
        } else {
            c.drawCircle(s / 2, s / 2, s * 0.3f, fill); c.drawCircle(s / 2, s / 2, s * 0.3f, stroke)
        }
        return bmp
    }

    private fun letterDisc(letter: String, fillColor: String, sizeDp: Float): Bitmap {
        val size = px(sizeDp)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        c.drawCircle(s / 2, s / 2, s / 2 - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(fillColor) })
        c.drawCircle(s / 2, s / 2, s / 2 - density * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = android.graphics.Color.WHITE })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK; textSize = s * 0.58f; textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        c.drawText(letter, s / 2, s / 2 - (text.descent() + text.ascent()) / 2, text)
        return bmp
    }

    private fun homeBitmap() = letterDisc("H", OK, 28f)
    private fun takeoffBitmap() = letterDisc("T", "#B0B0B0", 22f)

    private fun phoneBitmap(): Bitmap {
        val size = px(20f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        c.drawCircle(s / 2, s / 2, s / 2 - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        c.drawCircle(s / 2, s / 2, s / 2 - 3.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#3B82F6") })
        return bmp
    }

    companion object {
        private const val TOUCH_RADIUS_DP = 22f
        private const val WARN = "#FBBF24" // FlightColors.warn
        private const val OK = "#4ADE80" // FlightColors.ok
        private const val TRAIL_COLOR = "#22D3EE"

        private const val IMG_AIRCRAFT = "oe-img-aircraft"
        private const val IMG_AIRCRAFT_NOHDG = "oe-img-aircraft-nohdg"
        private const val IMG_HOME = "oe-img-home"
        private const val IMG_TAKEOFF = "oe-img-takeoff"
        private const val IMG_PHONE = "oe-img-phone"

        private const val S_TRAIL = "oe-trail"
        private const val S_HOMELINE = "oe-homeline"
        private const val S_HOMELABEL = "oe-homelabel"
        private const val S_WPLINE = "oe-wpline"
        private const val S_WP = "oe-wp"
        private const val S_TAKEOFF = "oe-takeoff"
        private const val S_HOME = "oe-home"
        private const val S_PHONE = "oe-phone"
        private const val S_AIRCRAFT = "oe-aircraft"

        private const val L_TRAIL = "oe-l-trail"
        private const val L_WPLINE = "oe-l-wpline"
        private const val L_HOMELINE = "oe-l-homeline"
        private const val L_WP_CIRCLE = "oe-l-wp-circle"
        private const val L_WP_NUM = "oe-l-wp-num"
        private const val L_TAKEOFF = "oe-l-takeoff"
        private const val L_HOME = "oe-l-home"
        private const val L_PHONE = "oe-l-phone"
        private const val L_AIRCRAFT = "oe-l-aircraft"
        private const val L_HOMELABEL = "oe-l-homelabel"

        fun formatDistance(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000)
    }
}
