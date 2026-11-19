package app.maparound.map.maplibre

import app.maparound.shared.net.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * An online vector map drawn from someone else's MapLibre style (OpenFreeMap's): its sources and
 * layers under MapAround's own, its sprite for its icons, and its labels in the language asked for.
 * The web draws it (MapLibre GL JS); Android's renderer doesn't take others' styles
 * ([app.maparound.map.TileSources.vectorMaps]).
 */
object OnlineVectorStyle {
    /** The style's sources and layers get this before their ids, apart from MapAround's own. */
    const val PREFIX = "v-"

    /** What goes into MapAround's style: the sources (by id), the layers in order, and the sprite. */
    class Parts(val sources: Map<String, JsonElement>, val layers: List<JsonObject>, val sprite: JsonElement?)

    /** The style last read from its JSON (the map is drawn again and again from the same one). */
    private var parsed: Pair<String, JsonObject>? = null

    /** [parts] of a style's JSON; none when it isn't one. */
    fun parts(style: String, language: String): Parts? {
        val obj = parsed?.takeIf { it.first === style || it.first == style }?.second
            ?: runCatching { Json.parseToJsonElement(style).jsonObject }.getOrNull()?.also { parsed = style to it }
            ?: return null
        return parts(obj, language)
    }

    /**
     * [style]'s sources and layers, their ids prefixed; the labels that show a name show it in
     * [language] ("he": `name:he`, else the name there) except in English, where the style's own
     * (the name in Latin letters, and in its own script beside it) stays.
     */
    fun parts(style: JsonObject, language: String): Parts {
        val sources = (style["sources"] as? JsonObject).orEmpty().mapKeys { (id, _) -> PREFIX + id }
        val lang = language.trim().lowercase().substringBefore('-').substringBefore('_').let { if (it == "iw") "he" else it }
        val layers = (style["layers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val l = e as? JsonObject ?: return@mapNotNull null
            val out = l.toMutableMap()
            (l["id"] as? JsonPrimitive)?.content?.let { out["id"] = JsonPrimitive(PREFIX + it) }
            (l["source"] as? JsonPrimitive)?.content?.let { out["source"] = JsonPrimitive(PREFIX + it) }
            val layout = l["layout"] as? JsonObject
            val field = layout?.get("text-field")
            if (lang.isNotEmpty() && lang != "en" && field != null && showsName(field)) {
                out["layout"] = JsonObject(layout + ("text-field" to nameIn(lang)))
            }
            JsonObject(out)
        }
        return Parts(sources, layers, style["sprite"])
    }

    /** Whether a label's text is a name (not a road's number, a house number). */
    private fun showsName(field: JsonElement): Boolean =
        Regex("\"name([:_][a-z]+)?\"|\\{name").containsMatchIn(field.toString())

    /** The name in [lang] where there is one, else the place's own. */
    private fun nameIn(lang: String): JsonElement = JsonArray(listOf(
        JsonPrimitive("coalesce"),
        JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name:$lang"))),
        JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name"))),
    ))

    private val styles = HashMap<String, String>()
    private val lock = Mutex()

    /** The style at [url] (its JSON), read once (kept for the visit); null when it can't be read. */
    suspend fun load(url: String): String? {
        lock.withLock { styles[url] }?.let { return it }
        val style = try {
            withContext(Dispatchers.Default) {
                httpGetText(url, timeoutMs = 20_000).takeIf { runCatching { Json.parseToJsonElement(it).jsonObject }.isSuccess }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        lock.withLock { styles[url] = style }
        return style
    }
}
