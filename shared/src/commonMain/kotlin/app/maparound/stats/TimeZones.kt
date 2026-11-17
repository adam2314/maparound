package app.maparound.stats

import app.maparound.map.PackedReader
import kotlin.math.abs

// Plain Kotlin: the world's time zones for the data view, from the app's own file (timezones.bin,
// by tools/timezones/make_timezones.py from timezone-boundary-builder). Unit-tested.

/**
 * A time zone's area: its IANA name ("Asia/Jerusalem"; "Etc/GMT-2" at sea) and its polygons, each
 * the outer ring and then its holes, a ring as (lat, lon) pairs in thousandths of a degree.
 */
class ZoneArea(val name: String, val polygons: List<List<IntArray>>) {
    val south: Int
    val north: Int
    val west: Int
    val east: Int

    init {
        var s = Int.MAX_VALUE
        var n = Int.MIN_VALUE
        var w = Int.MAX_VALUE
        var e = Int.MIN_VALUE
        for (p in polygons) {
            val outer = p.first()
            for (k in outer.indices step 2) {
                s = minOf(s, outer[k]); n = maxOf(n, outer[k])
                w = minOf(w, outer[k + 1]); e = maxOf(e, outer[k + 1])
            }
        }
        south = s; north = n; west = w; east = e
    }

    /** Whether (lat, lon), in thousandths of a degree, is in the zone: in an outer ring and none of its holes. */
    fun contains(lat: Int, lon: Int): Boolean {
        if (lat < south || lat > north || lon < west || lon > east) return false
        return polygons.any { p -> inside(p.first(), lat, lon) && (1 until p.size).none { inside(p[it], lat, lon) } }
    }
}

/** A clock change: when ([atMillis]) and the offset after it. */
data class ClockChange(val atMillis: Long, val offsetSeconds: Int)

object TimeZones {
    /** A zone's tap key: "data:z:Asia/Jerusalem". */
    const val TAP_ZONE = "data:z:"

    /** The offsets' classes: whole hours from UTC−12 to UTC+14 (+5:30 is in +5's, −3:30 in −3's). */
    const val MIN_HOUR = -12
    const val MAX_HOUR = 14

    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    /** How far ahead a clock change is looked for. */
    private const val CHANGE_DAYS = 400

    /** The zones in timezones.bin; empty when it isn't one. */
    fun decode(bytes: ByteArray): List<ZoneArea> = runCatching {
        if (bytes.size < 5 || bytes.decodeToString(0, 4) != "MATZ" || bytes[4].toInt() != 1) return emptyList()
        val r = PackedReader(bytes, 5)
        List(r.int()) {
            val name = r.text()
            ZoneArea(name, List(r.int()) { List(r.int()) { r.points(1) } })
        }
    }.getOrDefault(emptyList())

    /** The zone at ([lat], [lon]), or null (none of [zones] has it). */
    fun at(zones: List<ZoneArea>, lat: Double, lon: Double): ZoneArea? {
        val y = kotlin.math.round(lat * 1000).toInt()
        val x = kotlin.math.round(lon * 1000).toInt()
        return zones.firstOrNull { it.contains(y, x) }
    }

    /** The class of an offset (seconds): its whole hours, from [MIN_HOUR]. */
    fun classOf(offsetSeconds: Int): Int = (offsetSeconds / 3600 - MIN_HOUR).coerceIn(0, MAX_HOUR - MIN_HOUR)

    /**
     * The classes' colours: round the colour wheel once, from UTC−12 to UTC+14 (which meet at the
     * date line), lighter and darker in turn, so neighbouring hours always differ.
     */
    val colors: List<String> = (0..MAX_HOUR - MIN_HOUR).map { i ->
        hsl(i * 360.0 / (MAX_HOUR - MIN_HOUR + 1), 0.62, if (i % 2 == 0) 0.68 else 0.5)
    }

    /** The legend: "−12" … "0" … "+14" (hours from UTC). */
    val classes: List<StatClass>
        get() = (MIN_HOUR..MAX_HOUR).mapIndexed { i, h -> StatClass(hours(h), colors[i]) }

    /** An offset as "+2", "−3:30", "+5:45", "0". */
    fun offset(seconds: Int): String {
        if (seconds == 0) return "0"
        val a = abs(seconds)
        val h = a / 3600
        val m = a % 3600 / 60
        return (if (seconds < 0) "−" else "+") + h + (if (m != 0) ":" + m.toString().padStart(2, '0') else "")
    }

    private fun hours(h: Int) = if (h == 0) "0" else if (h < 0) "−${-h}" else "+$h"

    /**
     * The next change of [zone]'s clocks after [fromMillis] (within about a year), or null if none
     * (or its offsets aren't known). [offsetAt]: its offset at a time.
     */
    fun nextChange(fromMillis: Long, offsetAt: (Long) -> Int?): ClockChange? {
        val now = offsetAt(fromMillis) ?: return null
        var before = fromMillis
        for (d in 1..CHANGE_DAYS) {
            val t = fromMillis + d * DAY_MS
            val o = offsetAt(t) ?: return null
            if (o != now) {
                // To the minute: clocks change on one.
                var lo = before
                var hi = t
                while (hi - lo > MINUTE_MS / 2) {
                    val mid = lo + (hi - lo) / 2
                    if (offsetAt(mid) == now) lo = mid else hi = mid
                }
                return ClockChange(hi - hi.mod(MINUTE_MS), o)
            }
            before = t
        }
        return null
    }

    /**
     * A zone's name as a place: ("Buenos Aires", "America · Argentina") for
     * "America/Argentina/Buenos_Aires"; at sea ("Etc/GMT-2") null.
     */
    fun place(name: String): Pair<String, String>? {
        if (name.startsWith("Etc/")) return null
        val parts = name.split('/').map { it.replace('_', ' ') }
        return parts.last() to parts.dropLast(1).joinToString(" · ")
    }

    /**
     * The zones as GeoJSON: each zone's area ("role": "zone") coloured by its [offsets] (seconds,
     * by name; grey when unknown), and a point for its label ("role": "label", "label": "+2") in
     * its biggest part. Zones whose class is in [hidden] (unknown: -1) are left out.
     */
    fun geoJson(zones: List<ZoneArea>, offsets: Map<String, Int>, hidden: Set<Int> = emptySet()): String {
        val sb = StringBuilder(2_000_000)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (z in zones) {
            val o = offsets[z.name]
            val c = o?.let(::classOf) ?: -1
            if (c in hidden) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"MultiPolygon","coordinates":[""")
            z.polygons.forEachIndexed { pi, p ->
                if (pi > 0) sb.append(',')
                sb.append('[')
                p.forEachIndexed { ri, ring ->
                    if (ri > 0) sb.append(',')
                    // Outer rings anticlockwise, holes clockwise, as GeoJSON has them.
                    ring(sb, ring, clockwise = ri > 0)
                }
                sb.append(']')
            }
            sb.append("""]},"properties":{"role":"zone","tap":"$TAP_ZONE${z.name}","color":"${if (c < 0) NO_DATA else colors[c]}"}}""")
            val at = labelPoint(z) ?: continue
            val span = labelSpan(z)
            sb.append(""",{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
            coord(sb, at.second).append(',')
            coord(sb, at.first)
            sb.append("""]},"properties":{"role":"label","label":"${if (o == null) "?" else offset(o)}","minz":""")
                .append(labelZoom(span)).append(""","rank":""").append(-span).append("}}")
        }
        return sb.append("]}").toString()
    }

    /** The size of a zone's biggest polygon: its box's longer side, in thousandths of a degree. */
    fun labelSpan(z: ZoneArea): Int = z.polygons.maxOf { poly ->
        val r = poly.first()
        var s = Int.MAX_VALUE; var n = Int.MIN_VALUE; var w = Int.MAX_VALUE; var e = Int.MIN_VALUE
        for (k in r.indices step 2) { s = minOf(s, r[k]); n = maxOf(n, r[k]); w = minOf(w, r[k + 1]); e = maxOf(e, r[k + 1]) }
        maxOf(n - s, e - w)
    }

    /**
     * From which (MapLibre) zoom a zone's label shows, by its size: a 40° zone from 0, a country of
     * 10° from 2, a 1° sliver from 5.3; zoomed out, Europe isn't a crowd of "+1".
     */
    fun labelZoom(span: Int): Double {
        val degrees = maxOf(span, 1) / 1000.0
        val z = kotlin.math.ln(LABEL_DEGREES / degrees) / kotlin.math.ln(2.0)
        return kotlin.math.round(z.coerceIn(0.0, 9.0) * 10) / 10
    }

    /** A zone this big (degrees) is labelled from zoom 0. */
    private const val LABEL_DEGREES = 40.0

    /**
     * Where a zone's label goes: in its biggest polygon, halfway across the widest stretch of it
     * along the line through the middle of its height. Null when it has no area.
     */
    fun labelPoint(z: ZoneArea): Pair<Int, Int>? {
        val p = z.polygons.maxByOrNull { poly ->
            val r = poly.first()
            var s = Int.MAX_VALUE; var n = Int.MIN_VALUE; var w = Int.MAX_VALUE; var e = Int.MIN_VALUE
            for (k in r.indices step 2) { s = minOf(s, r[k]); n = maxOf(n, r[k]); w = minOf(w, r[k + 1]); e = maxOf(e, r[k + 1]) }
            (n - s).toLong() * (e - w)
        } ?: return null
        val outer = p.first()
        var s = Int.MAX_VALUE
        var n = Int.MIN_VALUE
        for (k in outer.indices step 2) { s = minOf(s, outer[k]); n = maxOf(n, outer[k]) }
        // Half a unit off the grid, so the line never runs through a corner.
        val y = (s.toDouble() + n) / 2 + 0.5
        val xs = ArrayList<Double>()
        for (ring in p) {
            val m = ring.size / 2
            for (i in 0 until m) {
                val j = (i + m - 1) % m
                val yi = ring[2 * i].toDouble()
                val yj = ring[2 * j].toDouble()
                if ((yi > y) != (yj > y)) {
                    xs += ring[2 * i + 1] + (y - yi) / (yj - yi) * (ring[2 * j + 1] - ring[2 * i + 1])
                }
            }
        }
        xs.sort()
        var best = -1.0
        var bx = 0.0
        for (k in 0 until xs.size - 1 step 2) {
            val w = xs[k + 1] - xs[k]
            if (w > best) { best = w; bx = (xs[k] + xs[k + 1]) / 2 }
        }
        return if (best < 0) outer[0] to outer[1] else kotlin.math.round(y).toInt() to kotlin.math.round(bx).toInt()
    }

    /** A ring as GeoJSON ([lon, lat], closed), turned the asked way round. */
    private fun ring(sb: StringBuilder, r: IntArray, clockwise: Boolean) {
        val m = r.size / 2
        // The shoelace sum, with lon as x: positive anticlockwise.
        var area = 0L
        for (i in 0 until m) {
            val j = (i + 1) % m
            area += r[2 * i + 1].toLong() * r[2 * j] - r[2 * j + 1].toLong() * r[2 * i]
        }
        val reverse = (area < 0) != clockwise
        sb.append('[')
        for (k in 0..m) {
            val i = if (reverse) (m - k % m) % m else k % m
            if (k > 0) sb.append(',')
            sb.append('[')
            coord(sb, r[2 * i + 1]).append(',')
            coord(sb, r[2 * i])
            sb.append(']')
        }
        sb.append(']')
    }

    /** Thousandths of a degree as a decimal ("35.123", "-0.5"). */
    private fun coord(sb: StringBuilder, v: Int): StringBuilder {
        if (v < 0) sb.append('-')
        val a = abs(v)
        sb.append(a / 1000)
        val f = a % 1000
        if (f != 0) sb.append('.').append(f.toString().padStart(3, '0').trimEnd('0'))
        return sb
    }

    /** "#rrggbb" for a hue (degrees), saturation and lightness (0–1). */
    private fun hsl(h: Double, s: Double, l: Double): String {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = h / 60
        val x = c * (1 - abs(hp % 2 - 1))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        fun hex(v: Double) = kotlin.math.round((v + m) * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        return "#" + hex(r) + hex(g) + hex(b)
    }
}

/** Ray casting: whether (y, x) is inside [ring] ((lat, lon) pairs). */
private fun inside(ring: IntArray, y: Int, x: Int): Boolean {
    var inside = false
    val m = ring.size / 2
    var j = m - 1
    for (i in 0 until m) {
        val yi = ring[2 * i]
        val yj = ring[2 * j]
        if ((yi > y) != (yj > y)) {
            val xi = ring[2 * i + 1]
            val xj = ring[2 * j + 1]
            if (x < xi + (y - yi).toDouble() * (xj - xi) / (yj - yi)) inside = !inside
        }
        j = i
    }
    return inside
}
