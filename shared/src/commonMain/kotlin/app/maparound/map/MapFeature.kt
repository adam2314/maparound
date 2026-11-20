package app.maparound.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Something the map draws, as a query finds it under a tap: the style's [layer] and its kind of
 * drawing ([type]: fill, line, symbol…), where its data comes from ([source], [sourceLayer]),
 * its [geometry] type and its properties.
 */
data class MapFeature(
    val layer: String,
    val type: String,
    val source: String,
    val sourceLayer: String,
    val geometry: String,
    val props: Map<String, String>,
) {
    companion object {
        /** [NativeMap.featuresAt]'s JSON; what isn't understood is left out. */
        fun parseList(json: String): List<MapFeature> = runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                    MapFeature(
                        s("layer"), s("type"), s("source"), s("sourceLayer"), s("geom"),
                        o["p"]?.jsonObject.orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() },
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
