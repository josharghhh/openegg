package com.openegg.app.map

/**
 * Base-map styles. All free, no API key, OpenStreetMap data via OpenFreeMap
 * (https://openfreemap.org — "no limits, no registration"; attribution shown by MapLibre's (i) button).
 *
 * Satellite: deliberately not offered. No free, key-less imagery source we found permits
 * bulk offline caching in a third-party app (Esri World Imagery needs an ArcGIS account/terms;
 * EOX Sentinel-2 cloudless is 10 m/px — useless at drone scale — and its recent years are non-commercial).
 * To add one later, append a [MapStyle] whose url points at a style JSON with a raster source.
 */
data class MapStyle(
    val id: String,
    val label: String,
    val url: String,
    /** Max zoom of the style's tile source; offline downloads never fetch deeper than this. */
    val sourceMaxZoom: Int,
)

object MapStyles {
    const val LIBERTY_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val POSITRON_URL = "https://tiles.openfreemap.org/styles/positron"

    val LIBERTY = MapStyle("liberty", "Streets", LIBERTY_URL, sourceMaxZoom = 14)
    val POSITRON = MapStyle("positron", "Light", POSITRON_URL, sourceMaxZoom = 14)

    /** First entry is the default. Both share OpenFreeMap's planet tiles, so offline tiles are stored once. */
    val ALL: List<MapStyle> = listOf(LIBERTY, POSITRON)
    val DEFAULT = ALL.first()

    /** Font present in OpenFreeMap styles' glyph sets, so our labels render offline too. */
    val LABEL_FONT = arrayOf("Noto Sans Regular")

    fun byId(id: String?): MapStyle = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
