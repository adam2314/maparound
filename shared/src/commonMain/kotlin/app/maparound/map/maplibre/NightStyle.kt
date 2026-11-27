package app.maparound.map.maplibre

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The dark map ([app.maparound.map.MapNight], the car at night): light text and lines on dark
 * ground, made from the day style's layers ([MapStyle.build] with night on).
 *
 * The base map's colours keep their hue and swap light for dark: pale land and water go dark,
 * black labels light, white halos dark. Raster maps are inverted the same way, satellite imagery
 * only dimmed (inverted it would be a negative), hill shading darkens only. Our overlays (route,
 * tracks, party) keep their colours, except labels: dark text on a light halo turns light on dark.
 */
internal object NightStyle {
    /** The darkest and lightest a colour becomes (white and black, by day). */
    private const val DARKEST = 0.08
    private const val LIGHTEST = 0.86

    /** Colours a little greyer at night, to glare less. */
    private const val SATURATION = 0.8

    /** A layer of the base map (the offline map's, an online style's, the raster under them). */
    fun base(layer: JsonObject, imagery: Boolean): JsonObject = when (layer.type()) {
        "raster" -> layer.withPaint(if (imagery) DIMMED else INVERTED)
        "hillshade" -> layer.withPaint(HILLSHADE)
        else -> layer.mapPaint { key, value -> if (key.endsWith("-color")) colors(value, top = true) else value }
    }

    /** One of our overlays: only labels drawn dark on a light halo change, to light on dark. */
    fun overlay(layer: JsonObject): JsonObject {
        val halo = (layer.paint()?.get("text-halo-color") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(::parse) ?: return layer
        if (lightness(halo) < 0.6) return layer
        return layer.mapPaint { key, value -> if (key == "text-color" || key == "text-halo-color") colors(value, top = true) else value }
    }

    /**
     * Every colour in a paint value at night: a literal, or the colours inside an expression or
     * zoom stops. Colour names only as the whole value (inside an expression "white" may be a
     * value to match, not a colour).
     */
    fun colors(value: JsonElement, top: Boolean = false): JsonElement = when (value) {
        is JsonPrimitive -> if (value.isString) parse(value.content, names = top)?.let { JsonPrimitive(format(night(it))) } ?: value else value
        is JsonArray -> JsonArray(value.map { colors(it) })
        is JsonObject -> JsonObject(value.mapValues { (_, v) -> colors(v) })
    }

    /** [c] at night: its lightness turned over into [DARKEST]..[LIGHTEST], hue and alpha kept. */
    fun night(c: Rgba): Rgba {
        val (h, s, l) = hsl(c)
        return fromHsl(h, s * SATURATION, DARKEST + (LIGHTEST - DARKEST) * (1 - l), c.a)
    }

    // ---- paint for whole layers -----------------------------------------------------------------

    /**
     * Raster maps turned over: MapLibre draws min + (max − min) × colour, so a max below the min
     * inverts; the hue turned half round puts it back (blue water stays blue).
     */
    private val INVERTED = mapOf(
        "raster-brightness-min" to JsonPrimitive(LIGHTEST),
        "raster-brightness-max" to JsonPrimitive(DARKEST),
        "raster-hue-rotate" to JsonPrimitive(180),
        "raster-saturation" to JsonPrimitive(SATURATION - 1),
    )

    /** Satellite imagery: darker and greyer. */
    private val DIMMED = mapOf(
        "raster-brightness-max" to JsonPrimitive(0.55),
        "raster-saturation" to JsonPrimitive(-0.25),
    )

    /** Shadows only: highlights would glow on the dark ground. */
    private val HILLSHADE = mapOf(
        "hillshade-shadow-color" to JsonPrimitive("#000000"),
        "hillshade-accent-color" to JsonPrimitive("#000000"),
        "hillshade-highlight-color" to JsonPrimitive("rgba(255,255,255,0.06)"),
    )

    private fun JsonObject.type() = (this["type"] as? JsonPrimitive)?.content
    private fun JsonObject.paint() = this["paint"] as? JsonObject

    private fun JsonObject.withPaint(extra: Map<String, JsonElement>) =
        JsonObject(this + ("paint" to JsonObject((paint() ?: emptyMap()) + extra)))

    private fun JsonObject.mapPaint(f: (String, JsonElement) -> JsonElement): JsonObject {
        val p = paint() ?: return this
        return JsonObject(this + ("paint" to JsonObject(p.mapValues { (k, v) -> f(k, v) })))
    }

    // ---- colours ----------------------------------------------------------------------------------

    /** A colour, each part 0..1. */
    data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0)

    private val NAMED = mapOf("white" to Rgba(1.0, 1.0, 1.0), "black" to Rgba(0.0, 0.0, 0.0))
    private val FUNCTION = Regex("""^(rgba?|hsla?)\(([^)]*)\)$""")

    /** A CSS colour as styles write them (#rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl(), hsla()); else null. */
    fun parse(s: String, names: Boolean = true): Rgba? {
        val t = s.trim().lowercase()
        if (t.startsWith("#")) return hex(t.substring(1))
        if (names) NAMED[t]?.let { return it }
        val m = FUNCTION.matchEntire(t) ?: return null
        val parts = m.groupValues[2].split(',', ' ', '/').filter { it.isNotBlank() }
        if (parts.size !in 3..4) return null
        val alpha = if (parts.size == 4) part(parts[3], 1.0) ?: return null else 1.0
        return if (m.groupValues[1].startsWith("rgb")) {
            val r = part(parts[0], 255.0) ?: return null
            val g = part(parts[1], 255.0) ?: return null
            val b = part(parts[2], 255.0) ?: return null
            Rgba(r, g, b, alpha)
        } else {
            val h = parts[0].removeSuffix("deg").toDoubleOrNull() ?: return null
            val sat = part(parts[1], 100.0) ?: return null
            val l = part(parts[2], 100.0) ?: return null
            fromHsl(((h % 360) + 360) % 360, sat, l, alpha)
        }
    }

    /** "50%" or a number out of [scale], as 0..1. */
    private fun part(s: String, scale: Double): Double? {
        val v = if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull()?.div(100) else s.toDoubleOrNull()?.div(scale)
        return v?.coerceIn(0.0, 1.0)
    }

    private fun hex(h: String): Rgba? {
        val full = when (h.length) {
            3, 4 -> h.map { "$it$it" }.joinToString("")
            6, 8 -> h
            else -> return null
        }
        val v = full.chunked(2).map { it.toIntOrNull(16) ?: return null }
        return Rgba(v[0] / 255.0, v[1] / 255.0, v[2] / 255.0, v.getOrNull(3)?.div(255.0) ?: 1.0)
    }

    fun format(c: Rgba): String {
        fun ch(v: Double) = (v * 255).roundToInt().coerceIn(0, 255)
        return if (c.a >= 0.999) "rgb(${ch(c.r)},${ch(c.g)},${ch(c.b)})"
        else "rgba(${ch(c.r)},${ch(c.g)},${ch(c.b)},${(c.a * 1000).roundToInt() / 1000.0})"
    }

    fun lightness(c: Rgba) = (max(c.r, max(c.g, c.b)) + min(c.r, min(c.g, c.b))) / 2

    /** Hue (0..360), saturation and lightness (0..1). */
    private fun hsl(c: Rgba): Triple<Double, Double, Double> {
        val hi = max(c.r, max(c.g, c.b))
        val lo = min(c.r, min(c.g, c.b))
        val l = (hi + lo) / 2
        val d = hi - lo
        if (d < 1e-9) return Triple(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (hi) {
            c.r -> 60 * (((c.g - c.b) / d) % 6)
            c.g -> 60 * ((c.b - c.r) / d + 2)
            else -> 60 * ((c.r - c.g) / d + 4)
        }
        return Triple((h + 360) % 360, s.coerceIn(0.0, 1.0), l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double, a: Double): Rgba {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Rgba(r + m, g + m, b + m, a)
    }
}
