package com.openegg.app.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Offline map areas, wrapping MapLibre's [OfflineManager]. The user downloads an area at home
 * (the drone base station's Wi-Fi has no internet); in the field MapLibre serves everything from
 * its offline database automatically, no special mode needed.
 *
 * Politeness: only user-selected areas, capped by [OfflineLimits] (tile count and zoom), one
 * download at a time, generic User-Agent (see [MapInit]). All callbacks arrive on the main thread.
 */
class OfflineMapManager private constructor(private val context: Context) {

    /** One user-visible area (aggregated over its per-style MapLibre regions). */
    data class Area(
        val areaId: String,
        val name: String,
        val bounds: GeoBounds,
        val maxZoom: Int,
        val styleIds: List<String>,
        val regionIds: List<Long>,
        val bytes: Long,
        val complete: Boolean,
        val createdAtMillis: Long,
    )

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(
            val name: String,
            val styleIndex: Int,
            val styleCount: Int,
            val completed: Long,
            val required: Long,
            val bytes: Long,
            /** Last transient error; MapLibre keeps retrying connection/server errors by itself. */
            val lastError: String? = null,
        ) : DownloadState {
            val fraction: Float get() = if (required <= 0) 0f else ((styleIndex + completed.toFloat() / required) / styleCount).coerceIn(0f, 1f)
        }
        data class Done(val name: String, val bytes: Long) : DownloadState
        data class Failed(val name: String, val message: String) : DownloadState
    }

    private val main = Handler(Looper.getMainLooper())
    private val offline: OfflineManager

    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    val areas: StateFlow<List<Area>> = _areas.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** Bytes downloaded across all areas (from region statuses; the SQLite file may be a bit larger). */
    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private var active: OfflineRegion? = null
    private var cancelled = false

    init {
        MapInit.ensure(context)
        offline = OfflineManager.getInstance(context)
        refresh()
    }

    /** Checks the cap before anything is downloaded. Null means the area is too large: zoom in. */
    fun plan(bounds: GeoBounds, requestedMaxZoom: Int = OfflineLimits.MAX_ZOOM): OfflineLimits.Plan? =
        OfflineLimits.plan(bounds, requestedMaxZoom, sourceMaxZoom = MapStyles.ALL.maxOf { it.sourceMaxZoom })

    /**
     * Starts downloading [bounds] for every style in [styles]. Returns false (and does nothing)
     * if a download is already running or the area exceeds the cap.
     */
    fun downloadArea(
        bounds: GeoBounds,
        name: String,
        requestedMaxZoom: Int = OfflineLimits.MAX_ZOOM,
        styles: List<MapStyle> = MapStyles.ALL,
    ): Boolean {
        if (_download.value is DownloadState.Running) return false
        val plan = plan(bounds, requestedMaxZoom) ?: run {
            _download.value = DownloadState.Failed(name, "Area too large — zoom in and try again")
            return false
        }
        cancelled = false
        val areaId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        _download.value = DownloadState.Running(name, 0, styles.size, 0, 0, 0)
        downloadStyle(areaId, name, now, plan, styles, 0, bytesSoFar = 0)
        return true
    }

    private fun downloadStyle(areaId: String, name: String, created: Long, plan: OfflineLimits.Plan, styles: List<MapStyle>, index: Int, bytesSoFar: Long) {
        if (cancelled) return
        if (index >= styles.size) {
            _download.value = DownloadState.Done(name, bytesSoFar)
            refresh()
            return
        }
        val style = styles[index]
        val b = plan.bounds
        val definition = OfflineTilePyramidRegionDefinition(
            style.url,
            LatLngBounds.from(b.north, b.east, b.south, b.west),
            plan.minZoom.toDouble(),
            plan.maxZoom.toDouble(),
            context.resources.displayMetrics.density,
            false, // no CJK ideograph glyph ranges: keeps glyph downloads small
        )
        val meta = OfflineRegionMeta(areaId, name, style.id, created).encode()
        offline.createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                if (cancelled) { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE); refresh(); return }
                active = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (cancelled) return
                        val bytes = bytesSoFar + status.completedResourceSize
                        if (status.isComplete) {
                            offlineRegion.setObserver(null)
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            active = null
                            main.post { downloadStyle(areaId, name, created, plan, styles, index + 1, bytes) }
                        } else {
                            val prev = _download.value as? DownloadState.Running
                            _download.value = DownloadState.Running(
                                name, index, styles.size, status.completedResourceCount, status.requiredResourceCount, bytes,
                                lastError = prev?.lastError,
                            )
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        val prev = _download.value as? DownloadState.Running ?: return
                        val msg = when (error.reason) {
                            OfflineRegionError.REASON_CONNECTION -> "No internet — waiting to retry"
                            OfflineRegionError.REASON_SERVER -> "Map server busy — retrying"
                            else -> error.message
                        }
                        _download.value = prev.copy(lastError = msg)
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        active = null
                        _download.value = DownloadState.Failed(name, "Tile limit ($limit) exceeded")
                        refresh()
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _download.value = DownloadState.Failed(name, error)
                refresh()
            }
        })
    }

    /** Stops the running download. What was fetched stays (an incomplete area still helps); delete it to free space. */
    fun cancel() {
        cancelled = true
        active?.let { it.setObserver(null); it.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        active = null
        _download.value = DownloadState.Idle
        refresh()
    }

    fun clearResult() {
        if (_download.value !is DownloadState.Running) _download.value = DownloadState.Idle
    }

    fun delete(areaId: String, onDone: () -> Unit = {}) {
        offline.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val targets = offlineRegions.orEmpty().filter { OfflineRegionMeta.decode(it.metadata, it.id).areaId == areaId }
                if (targets.isEmpty()) { refresh(); onDone(); return }
                var left = targets.size
                val step = { if (--left == 0) { refresh(); onDone() } }
                targets.forEach { r ->
                    if (r === active) cancel()
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = step()
                        override fun onError(error: String) = step()
                    })
                }
            }

            override fun onError(error: String) { onDone() }
        })
    }

    /** Re-reads regions and their sizes from the offline database. */
    fun refresh() {
        offline.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty().toList()
                if (regions.isEmpty()) { _areas.value = emptyList(); _storageBytes.value = 0; return }
                val statuses = HashMap<Long, OfflineRegionStatus?>()
                regions.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) = collect(r.id, status)
                        override fun onError(error: String?) = collect(r.id, null)
                        private fun collect(id: Long, s: OfflineRegionStatus?) {
                            statuses[id] = s
                            if (statuses.size == regions.size) publish(regions, statuses)
                        }
                    })
                }
            }

            override fun onError(error: String) {}
        })
    }

    private fun publish(regions: List<OfflineRegion>, statuses: Map<Long, OfflineRegionStatus?>) {
        val areas = regions.groupBy { OfflineRegionMeta.decode(it.metadata, it.id).areaId }.map { (areaId, rs) ->
            val metas = rs.map { OfflineRegionMeta.decode(it.metadata, it.id) }
            val def = rs.first().definition
            val lb = def.bounds
            Area(
                areaId = areaId,
                name = metas.first().name,
                bounds = lb?.let { GeoBounds(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast) } ?: GeoBounds(0.0, 0.0, 0.0, 0.0),
                maxZoom = def.maxZoom.toInt(),
                styleIds = metas.map { it.styleId },
                regionIds = rs.map { it.id },
                bytes = rs.sumOf { statuses[it.id]?.completedResourceSize ?: 0L },
                complete = rs.all { statuses[it.id]?.isComplete == true },
                createdAtMillis = metas.first().createdAtMillis,
            )
        }.sortedByDescending { it.createdAtMillis }
        _areas.value = areas
        _storageBytes.value = areas.sumOf { it.bytes }
    }

    companion object {
        @Volatile private var instance: OfflineMapManager? = null

        fun get(context: Context): OfflineMapManager =
            instance ?: synchronized(this) { instance ?: OfflineMapManager(context.applicationContext).also { instance = it } }
    }
}
