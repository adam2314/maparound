package app.maparound.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lists of online tile sources, to export and import (see docs/map-styles.md):
 *
 * ```json
 * { "type": "maparound-tile-sources", "version": 1, "sources": [
 *   { "name": "My tiles", "url": "https://{s}.example.com/{z}/{x}/{y}.png", "maxZoom": 18,
 *     "attribution": "© Example", "subdomains": ["a", "b"] } ] }
 * ```
 *
 * Importing also takes a plain array of such entries (`url` may be called `urlTemplate` or
 * `template`), or text with one URL template per line, optionally after a name
 * (`My tiles  https://…`, `My tiles | https://…`); lines starting with `#` are skipped.
 */
object SourceList {
    const val TYPE = "maparound-tile-sources"
    const val FILE_NAME = "maparound-tile-sources.json"
    private val pretty = Json { prettyPrint = true }

    fun encode(sources: List<OnlineSource>): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("type", TYPE)
            put("version", 1)
            putJsonArray("sources") {
                for (s in sources) add(
                    buildJsonObject {
                        put("name", s.name)
                        put("url", s.urlTemplate)
                        put("maxZoom", s.maxZoom)
                        if (s.attribution != s.name) put("attribution", s.attribution)
                        if ("{s}" in s.urlTemplate) put("subdomains", buildJsonArray { s.subdomains.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        },
    )

    /** The usable sources in [text] (ids not set yet), or why there are none. */
    fun decode(text: String): Result<List<OnlineSource>> = runCatching {
        val trimmed = text.trim().removePrefix("﻿")
        val json = if (trimmed.startsWith("{") || trimmed.startsWith("[")) runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() else null
        val found = when (json) {
            is JsonObject -> (json["sources"] as? JsonArray)?.mapNotNull(::entry) ?: listOfNotNull(entry(json))
            is JsonArray -> json.mapNotNull(::entry)
            else -> trimmed.lines().mapNotNull(::line)
        }
        if (found.isEmpty()) error("No tile sources found. Expected URL templates with {z}, {x} and {y}.")
        found
    }

    private fun entry(e: JsonElement): OnlineSource? {
        val o = e as? JsonObject ?: return null
        fun str(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val url = str("url", "urlTemplate", "template")?.trim() ?: return null
        if (TileSources.validateTemplate(url) != null) return null
        val name = str("name", "title")?.trim()?.takeIf { it.isNotEmpty() } ?: hostName(url)
        val zoom = listOf("maxZoom", "max_zoom", "maxzoom").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.intOrNull }
        val subs = (o["subdomains"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }
        return source(name, url, zoom, str("attribution")?.trim(), subs)
    }

    private fun line(raw: String): OnlineSource? {
        val l = raw.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val at = Regex("https?://").find(l)?.range?.first ?: return null
        val url = l.substring(at).trim().split(Regex("\\s")).first()
        if (TileSources.validateTemplate(url) != null) return null
        val name = l.substring(0, at).trim().trimEnd('|', '=', ',', ':', '-', ';').trim()
        return source(name.ifEmpty { hostName(url) }, url, null, null, null)
    }

    private fun source(name: String, url: String, maxZoom: Int?, attribution: String?, subdomains: List<String>?) = OnlineSource(
        id = "",
        name = name,
        urlTemplate = url,
        maxZoom = (maxZoom ?: 19).coerceIn(1, 22),
        attribution = attribution ?: name,
        subdomains = subdomains ?: listOf("a", "b", "c"),
        custom = true,
    )

    private fun hostName(url: String) = TileSources.hostOf(url, listOf("a")).removePrefix("www.")
}
