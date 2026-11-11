package app.maparound.map.maplibre

import app.maparound.map.maplibre.MapStyle.Companion.arr
import app.maparound.map.maplibre.MapStyle.Companion.obj
import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A mapsforge render theme (the Android map's topo.xml) as MapLibre style layers, so both
 * platforms draw from one theme file. Every render instruction becomes a layer, in theme order
 * (mapsforge draws in that order too); rule conditions become layer filters and zoom ranges.
 *
 * Sources: the offline map's vector tiles ([MapStyle.OSM_SOURCE]: poi, line, area, sea), and
 * for rules on `contour…` and `trail…` keys the contour and marked-trail tiles ([CONTOURS],
 * [TRAILS]). Symbols are images named [SYMBOL_PREFIX] + their theme path (the platform draws
 * them: iOS from PNGs made of the theme's SVGs). Not drawn yet: area patterns, line symbols.
 */
object ThemeStyle {
    const val CONTOURS = "contours"
    const val TRAILS = "trails"

    /** Image names of theme symbols: "sym:" + the path in the theme, e.g. `sym:symbols/peak.svg`. */
    const val SYMBOL_PREFIX = "sym:"

    /** Layer metadata key marking land cover (fills and their outlines), dropped over imagery. */
    const val AREA_FLAG = "maparound:area"

    class Result(
        val layers: List<JsonObject>,
        /** Where hill shading goes (index into [layers]) and its zoom range, if the theme has it. */
        val hillshade: Hillshade?,
    )

    class Hillshade(val index: Int, val minZoom: Int, val maxZoom: Int, val magnitude: Int)

    fun convert(xml: String): Result {
        val root = parse(xml)
        val out = Converter()
        root.background?.let { out.layers += layer("background", "background", AREA_FLAG) { put("paint", obj("background-color" to color(it))) } }
        out.walk(root.children, Ctx.ROOT)
        return Result(out.layers, out.hillshade)
    }

    // ---- the theme as a tree ----------------------------------------------------------------------

    private sealed class Node
    private class Rule(val attrs: Map<String, String>) : Node() {
        val children = ArrayList<Node>()
    }
    private class Instruction(val name: String, val attrs: Map<String, String>) : Node()
    private class Theme(val background: String?) {
        val children = ArrayList<Node>()
    }

    private fun parse(xml: String): Theme {
        var theme: Theme? = null
        val stack = ArrayList<MutableList<Node>>()
        Xml.parse(xml, object : XmlHandler {
            override fun start(name: String, attrs: Map<String, String>) {
                when (name) {
                    "rendertheme" -> Theme(attrs["map-background"]).also { theme = it; stack += it.children }
                    "rule" -> Rule(attrs).also { stack.lastOrNull()?.add(it); stack += it.children }
                    else -> stack.lastOrNull()?.add(Instruction(name, attrs))
                }
            }

            override fun end(name: String) {
                if (name == "rule" || name == "rendertheme") stack.removeLastOrNull()
            }
        })
        return theme ?: error("Not a render theme")
    }

    // ---- conditions -------------------------------------------------------------------------------

    /** One rule's key/value test, as mapsforge reads it. */
    private class Matcher(val keys: List<String>, values: List<String>) {
        /** `~` among the values: also matches when none of the keys is there. */
        val negative = "~" in values
        val values = values.filter { it != "~" }
        val always = keys == listOf("*") && this.values == listOf("*")

        fun matches(tags: Map<String, String>): Boolean {
            if (always) return true
            val present = keys.filter { it == "*" || it in tags }
            if (negative && present.isEmpty()) return true
            return present.any { k ->
                if (k == "*") tags.values.any { it in values } else values == listOf("*") || tags[k] in values
            }
        }

        fun expression(): JsonElement? {
            if (always) return null
            val byValue = keys.filter { it != "*" }.map { k ->
                if (values == listOf("*")) arr("has", k) else arr("match", arr("get", k), JsonArray(values.map(::JsonPrimitive)), true, false)
            }
            val positive = if (byValue.size == 1) byValue[0] else JsonArray(listOf(JsonPrimitive("any")) + byValue)
            if (!negative) return positive
            val absent = keys.filter { it != "*" }.map { arr("!", arr("has", it)) }
            val none = if (absent.size == 1) absent[0] else JsonArray(listOf(JsonPrimitive("all")) + absent)
            return if (values.isEmpty()) none else arr("any", none, positive)
        }
    }

    /** What a rule chain adds up to. */
    private data class Ctx(
        /** node, way or any; null when the chain can match nothing. */
        val element: String?,
        val matchers: List<Matcher>,
        val closed: String?,
        val zoomMin: Int,
        val zoomMax: Int,
    ) {
        val keys: Set<String> get() = matchers.flatMap { it.keys }.toSet()

        fun matches(tags: Map<String, String>) = matchers.all { it.matches(tags) }

        fun plus(attrs: Map<String, String>): Ctx {
            val e = attrs["e"] ?: "any"
            val element = when {
                element == null -> null
                e == "any" -> element
                element == "any" || element == e -> e
                else -> null
            }
            return Ctx(
                element,
                matchers + Matcher(attrs["k"].orEmpty().split('|'), attrs["v"].orEmpty().split('|')),
                attrs["closed"] ?: closed,
                maxOf(zoomMin, attrs["zoom-min"]?.toIntOrNull() ?: 0),
                minOf(zoomMax, attrs["zoom-max"]?.toIntOrNull() ?: 99),
            )
        }

        companion object {
            val ROOT = Ctx("any", emptyList(), null, 0, 99)
        }
    }

    // ---- instructions -----------------------------------------------------------------------------

    private class Converter {
        val layers = ArrayList<JsonObject>()
        var hillshade: Hillshade? = null

        fun walk(nodes: List<Node>, ctx: Ctx) {
            for (n in nodes) {
                when (n) {
                    is Rule -> {
                        val c = ctx.plus(n.attrs)
                        if (c.element != null && c.zoomMin <= c.zoomMax) walk(n.children, c)
                    }
                    is Instruction -> instruction(n, ctx)
                }
            }
        }

        private fun instruction(i: Instruction, ctx: Ctx) {
            val a = i.attrs
            when (i.name) {
                "hillshading" -> hillshade = Hillshade(
                    layers.size, a["zoom-min"]?.toIntOrNull() ?: 0, a["zoom-max"]?.toIntOrNull() ?: 99, a["magnitude"]?.toIntOrNull() ?: 64,
                )
                "area" -> if (ctx.element != "node") area(a, ctx)
                "line" -> if (ctx.element != "node") line(a, ctx)
                "caption" -> caption(a, ctx)
                "pathText" -> if (ctx.element != "node") pathText(a, ctx)
                "circle" -> if (ctx.element != "way") circle(a, ctx)
                "symbol" -> symbol(a, ctx)
                // lineSymbol (one-way arrows) and area patterns: later.
            }
        }

        private fun area(a: Map<String, String>, ctx: Ctx) {
            val fill = a["fill"]
            val stroke = a["stroke"]
            val sources = buildList {
                add(sourceFor(ctx, "area"))
                // Whole-water tiles come as their own layer.
                if (sourceFor(ctx, "area").first == MapStyle.OSM_SOURCE && ctx.matches(mapOf("natural" to "sea"))) add(MapStyle.OSM_SOURCE to "sea")
            }
            for ((source, sourceLayer) in sources) {
                if (fill != null) {
                    add(ctx, "fill", source, sourceLayer, area = true) { put("paint", obj("fill-color" to color(fill), "fill-antialias" to false)) }
                }
                val width = a["stroke-width"]?.toDoubleOrNull() ?: 0.0
                if (stroke != null && width > 0) {
                    add(ctx, "line", source, sourceLayer, area = true) {
                        put("paint", obj("line-color" to color(stroke), "line-width" to scaled(width, true)))
                    }
                }
            }
        }

        private fun line(a: Map<String, String>, ctx: Ctx) {
            val stroke = a["stroke"] ?: return
            val width = a["stroke-width"]?.toDoubleOrNull() ?: 1.0
            val scales = a["scale"] != "none"
            val paint = LinkedHashMap<String, Any>()
            paint["line-color"] = color(stroke)
            paint["line-width"] = scaled(width, scales)
            a["dy"]?.toDoubleOrNull()?.let { paint["line-offset"] = scaled(it, scales) }
            a["stroke-dasharray"]?.let { d ->
                // mapsforge dashes are in points; MapLibre's in line widths.
                val dashes = d.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (dashes.size >= 2) paint["line-dasharray"] = JsonArray(dashes.map { JsonPrimitive(round(it / width)) })
            }
            val layout = obj("line-join" to "round", "line-cap" to (a["stroke-linecap"] ?: "round"))
            for ((source, sourceLayer) in lineSources(ctx)) {
                add(ctx, "line", source, sourceLayer) {
                    put("layout", layout)
                    put("paint", JsonObject(paint.mapValues { (_, v) -> json(v) }))
                }
            }
        }

        /** Lines along ways; along area outlines too when the rule may be about areas. */
        private fun lineSources(ctx: Ctx): List<Pair<String, String>> {
            val (source, sourceLayer) = sourceFor(ctx, "line")
            if (source != MapStyle.OSM_SOURCE) return listOf(source to sourceLayer)
            return when {
                ctx.closed == "yes" -> listOf(source to "area")
                ctx.closed == "no" -> listOf(source to "line")
                ctx.keys.any { it in AREA_KEYS } -> listOf(source to "line", source to "area")
                else -> listOf(source to "line")
            }
        }

        private fun caption(a: Map<String, String>, ctx: Ctx) {
            if (a["display"] == "never") return
            val key = a["k"] ?: return
            val size = a["font-size"]?.toDoubleOrNull() ?: 12.0
            val dy = a["dy"]?.toDoubleOrNull() ?: 0.0
            val position = a["position"] ?: if (a["symbol-id"] != null) "below" else "center"
            val (anchor, offset) = when (position) {
                "above", "above_left", "above_right" -> "bottom" to -(SYMBOL_GAP_EM - dy / size)
                "below", "below_left", "below_right" -> "top" to SYMBOL_GAP_EM + dy / size
                else -> "center" to dy / size
            }
            val (source, _) = sourceFor(ctx, "poi")
            add(ctx, "symbol", source, "poi", labelFilter = labelFilter(ctx)) {
                put("layout", text(a, key, size) + obj(
                    "text-anchor" to anchor,
                    "text-offset" to arr(0, round(offset)),
                    "text-allow-overlap" to (a["display"] == "always"),
                ))
                put("paint", textPaint(a))
            }
        }

        private fun pathText(a: Map<String, String>, ctx: Ctx) {
            if (a["display"] == "never") return
            val key = a["k"] ?: return
            val size = a["font-size"]?.toDoubleOrNull() ?: 12.0
            val dy = a["dy"]?.toDoubleOrNull() ?: 0.0
            val (source, sourceLayer) = sourceFor(ctx, "line")
            add(ctx, "symbol", source, sourceLayer) {
                put("layout", text(a, key, size) + obj(
                    "symbol-placement" to "line",
                    "text-offset" to arr(0, round(dy / size)),
                    "symbol-spacing" to (a["repeat-gap"]?.toDoubleOrNull() ?: 250.0),
                ))
                put("paint", textPaint(a))
            }
        }

        private fun symbol(a: Map<String, String>, ctx: Ctx) {
            if (a["display"] == "never") return
            val src = a["src"]?.substringAfter(':')?.takeIf { it.endsWith(".svg") } ?: return
            val layout = LinkedHashMap<String, Any>()
            layout["icon-image"] = SYMBOL_PREFIX + src
            // The images are drawn SYMBOL_SIZE points high; the theme may ask for another size.
            (a["symbol-width"] ?: a["symbol-height"])?.toDoubleOrNull()?.let { layout["icon-size"] = round(it / SYMBOL_SIZE) }
            a["priority"]?.toDoubleOrNull()?.let { layout["symbol-sort-key"] = -it }
            if (a["display"] == "always") layout["icon-allow-overlap"] = true
            val (source, _) = sourceFor(ctx, "poi")
            add(ctx, "symbol", source, "poi", labelFilter = labelFilter(ctx)) {
                put("layout", JsonObject(layout.mapValues { (_, v) -> json(v) }))
            }
        }

        private fun circle(a: Map<String, String>, ctx: Ctx) {
            val radius = a["radius"]?.toDoubleOrNull() ?: return
            val paint = LinkedHashMap<String, Any>()
            paint["circle-radius"] = scaled(radius, a["scale-radius"] == "true")
            paint["circle-color"] = a["fill"]?.let(::color) ?: "rgba(0,0,0,0)"
            a["stroke"]?.let { paint["circle-stroke-color"] = color(it) }
            a["stroke-width"]?.toDoubleOrNull()?.let { paint["circle-stroke-width"] = it }
            val (source, _) = sourceFor(ctx, "poi")
            add(ctx, "circle", source, "poi", labelFilter = labelFilter(ctx)) {
                put("paint", JsonObject(paint.mapValues { (_, v) -> json(v) }))
            }
        }

        private fun text(a: Map<String, String>, key: String, size: Double): JsonObject {
            val layout = LinkedHashMap<String, Any>()
            layout["text-field"] = arr("to-string", arr("get", key))
            layout["text-font"] = arr(font(a["font-style"]))
            layout["text-size"] = size
            layout["text-max-width"] = 20
            // mapsforge places higher priorities first; MapLibre lower sort keys.
            a["priority"]?.toDoubleOrNull()?.let { layout["symbol-sort-key"] = -it }
            return JsonObject(layout.mapValues { (_, v) -> json(v) })
        }

        private fun textPaint(a: Map<String, String>): JsonObject {
            val paint = LinkedHashMap<String, Any>()
            paint["text-color"] = color(a["fill"] ?: "#000000")
            a["stroke"]?.let { paint["text-halo-color"] = color(it) }
            // mapsforge strokes the glyph outline: half of the width lies outside.
            a["stroke-width"]?.toDoubleOrNull()?.let { paint["text-halo-width"] = round(it / 2) }
            return JsonObject(paint.mapValues { (_, v) -> json(v) })
        }

        /** Points for nodes, area label points for ways (see MapsforgeToMvt), both for "any". */
        private fun labelFilter(ctx: Ctx): JsonElement? = when (ctx.element) {
            "node" -> arr("!", arr("has", "label_of"))
            "way" -> arr("==", arr("get", "label_of"), "area")
            else -> null
        }

        private fun add(
            ctx: Ctx, type: String, source: String, sourceLayer: String,
            area: Boolean = false, labelFilter: JsonElement? = null,
            body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ) {
            // mapsforge zoom z shows what MapLibre shows at z - 1 (256- vs 512-point tiles).
            val min = maxOf(ctx.zoomMin - 1, 0)
            val max = if (ctx.zoomMax >= 99) null else ctx.zoomMax
            if (max != null && max <= min) return
            val conditions = ctx.matchers.mapNotNull { it.expression() } + listOfNotNull(labelFilter)
            layers += layer("t${layers.size}-$type", type, if (area) AREA_FLAG else null) {
                put("source", source)
                put("source-layer", sourceLayer)
                if (min > 0) put("minzoom", min)
                if (max != null) put("maxzoom", max)
                when (conditions.size) {
                    0 -> Unit
                    1 -> put("filter", conditions[0])
                    else -> put("filter", JsonArray(listOf(JsonPrimitive("all")) + conditions))
                }
                body()
            }
        }
    }

    /** Which tiles a rule is about: contour lines and marked trails have their own. */
    private fun sourceFor(ctx: Ctx, osmLayer: String): Pair<String, String> {
        val keys = ctx.keys
        return when {
            "contour" in keys -> CONTOURS to CONTOURS
            keys.any { it == "trail" || it.startsWith("trail_") } -> TRAILS to TRAILS
            else -> MapStyle.OSM_SOURCE to osmLayer
        }
    }

    /** Keys whose ways are mostly surfaces, so lines in their rules also outline areas. */
    private val AREA_KEYS = setOf(
        "building", "landuse", "natural", "leisure", "amenity", "tourism", "man_made", "military",
        "aeroway", "water", "wetland", "boundary", "area", "area:highway", "historic", "sport",
    )

    /** Height of symbol images, points (mapsforge's default size of SVG symbols). */
    const val SYMBOL_SIZE = 20.0

    /** Captions above or below a symbol sit this far (ems) from the point. */
    private const val SYMBOL_GAP_EM = 0.9

    private fun font(style: String?): String = when (style) {
        "bold", "bold_italic" -> "Noto Sans Bold"
        "italic" -> "Noto Sans Italic"
        else -> "Noto Sans Regular"
    }

    /**
     * mapsforge widens strokes by 1.5× per zoom level from zoom 12 (MapLibre 11); `scale="none"`
     * keeps them as they are.
     */
    private fun scaled(v: Double, scales: Boolean): Any =
        if (!scales) v else arr("interpolate", arr("exponential", 1.5), arr("zoom"), 11, v, 22, round(v * 1.5.pow(11)))

    /** "#RRGGBB" or "#AARRGGBB" as a CSS colour. */
    fun color(c: String): String {
        val hex = c.removePrefix("#")
        val v = hex.toLongOrNull(16) ?: return "#000000"
        val a = if (hex.length == 8) (v shr 24) and 0xFF else 0xFF
        val r = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val b = v and 0xFF
        return if (a == 0xFFL) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${round(a / 255.0)})"
    }

    private fun round(v: Double): Double = (v * 1000).roundToLong() / 1000.0

    private fun json(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("Not a JSON value: $v")
    }

    private operator fun JsonObject.plus(other: JsonObject) = JsonObject(this.toMap() + other.toMap())

    private fun layer(id: String, type: String, flag: String?, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            if (flag != null) put("metadata", obj(flag to true))
            body()
        }
}
