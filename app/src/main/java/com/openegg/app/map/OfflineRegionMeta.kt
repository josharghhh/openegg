package com.openegg.app.map

/**
 * Metadata stored with each MapLibre offline region. One user "area" is downloaded once per
 * style (tiles are shared in the database, only style/sprites/glyphs differ), all with the same [areaId].
 * Encoded as simple `key=value` lines so it is JVM-testable without org.json.
 */
data class OfflineRegionMeta(
    val areaId: String,
    val name: String,
    val styleId: String,
    val createdAtMillis: Long,
) {
    fun encode(): ByteArray = buildString {
        append("v=1\n")
        append("area=").append(clean(areaId)).append('\n')
        append("name=").append(clean(name)).append('\n')
        append("style=").append(clean(styleId)).append('\n')
        append("created=").append(createdAtMillis).append('\n')
    }.toByteArray(Charsets.UTF_8)

    companion object {
        private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ')

        /** Lenient: regions created by anything else get a fallback name instead of being hidden. */
        fun decode(bytes: ByteArray?, regionId: Long): OfflineRegionMeta {
            val map = bytes?.toString(Charsets.UTF_8).orEmpty().lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            return OfflineRegionMeta(
                areaId = map["area"] ?: "region-$regionId",
                name = map["name"]?.takeIf { it.isNotBlank() } ?: "Area $regionId",
                styleId = map["style"] ?: "",
                createdAtMillis = map["created"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}
