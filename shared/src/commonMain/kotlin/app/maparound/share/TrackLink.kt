@file:OptIn(ExperimentalEncodingApi::class)

package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.urlDecode
import app.maparound.shared.util.urlEncode
import app.maparound.tracks.Track
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sqrt

// Plain Kotlin: a route or track as a link, and back. Unit-tested.

/**
 * A route or track as a link to MapAround's web app, the line itself in the address after `#`
 * (which browsers never send to the website): `https://maparound.app/app/#track=<line>&n=<name>`. It opens in
 * the web app, and in the Android app when shared or opened there. Long lines are thinned to at
 * most [MAX_POINTS], keeping their shape (Douglas–Peucker); the heights go along when every point
 * has one, so the climb and the elevation profile come too.
 *
 * `<line>`: URL-safe Base64 without padding of a format byte ([FORMAT]), a flags byte (1: heights),
 * then per point the changes in latitude and longitude in 1e-5° (about a metre), and with heights
 * the change in whole metres, each a zigzag varint.
 */
object TrackLink {
    /** The most points a link carries: about 3 kB of address, which chat apps and browsers take. */
    const val MAX_POINTS = 500

    private const val FORMAT = 1
    private const val HEIGHTS = 1
    private const val SCALE = 1e5
    private val B64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val PARAM = Regex("""[#&]track=([A-Za-z0-9_-]+)""")
    private val NAME = Regex("""[#&]n=([^&#\s]*)""")

    /** The link for [track], or null when it has no line. */
    fun of(track: Track): String? = of(track.name, track.points)

    fun of(name: String, points: List<GeoPoint>): String? {
        if (points.size < 2) return null
        return SharedLinks.WEB_APP + "#track=" + encode(thin(points, MAX_POINTS)) + "&n=" + urlEncode(name.take(80))
    }

    /**
     * The route or track on a map in another website: an iframe with the web app's map alone
     * (`?embed`), the line in its address as in [of]. Null when it has no line.
     */
    fun embedCode(track: Track): String? {
        val link = of(track) ?: return null
        return SharedLinks.iframe(link.replaceFirst("#", "?embed#"), track.name)
    }

    /** The route or track in a link (or a text with one), or null. */
    fun parse(text: String): Shared.Track? {
        val code = PARAM.find(text)?.groupValues?.get(1) ?: return null
        val points = runCatching { decode(code) }.getOrNull()?.takeIf { it.size >= 2 } ?: return null
        val name = NAME.find(text.substring(text.indexOf("track=")))?.groupValues?.get(1)?.let(::urlDecode)?.trim()
        return Shared.Track(name?.ifEmpty { null }, points)
    }

    // ---- the line ---------------------------------------------------------------------------------

    internal fun encode(points: List<GeoPoint>): String {
        val heights = points.all { it.ele != null }
        val out = ArrayList<Byte>(points.size * 5 + 2)
        out += FORMAT.toByte()
        out += (if (heights) HEIGHTS else 0).toByte()
        var lat = 0L
        var lon = 0L
        var ele = 0L
        for (p in points) {
            val a = (p.lat * SCALE).roundToLong()
            val o = (p.lon * SCALE).roundToLong()
            varint(out, zigzag(a - lat))
            varint(out, zigzag(o - lon))
            lat = a
            lon = o
            if (heights) {
                val e = p.ele!!.roundToLong()
                varint(out, zigzag(e - ele))
                ele = e
            }
        }
        return B64.encode(out.toByteArray())
    }

    internal fun decode(code: String): List<GeoPoint> {
        val b = B64.decode(code)
        require(b.size >= 2 && b[0].toInt() == FORMAT) { "Not a track link" }
        val heights = b[1].toInt() and HEIGHTS != 0
        var i = 2
        fun next(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                require(i < b.size && shift < 64) { "Track link cut short" }
                val x = b[i++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return (v ushr 1) xor -(v and 1)
                shift += 7
            }
        }
        val out = ArrayList<GeoPoint>()
        var lat = 0L
        var lon = 0L
        var ele = 0L
        while (i < b.size) {
            lat += next()
            lon += next()
            if (heights) ele += next()
            val p = GeoPoint(lat / SCALE, lon / SCALE, if (heights) ele.toDouble() else null)
            require(p.lat in -90.0..90.0 && p.lon in -180.0..180.0) { "Not a place" }
            out += p
        }
        return out
    }

    /** At most [max] points, the shape kept: the tolerance doubles until few enough are left. */
    internal fun thin(points: List<GeoPoint>, max: Int): List<GeoPoint> {
        if (points.size <= max) return points
        var tolerance = 1e-5 // degrees, about a metre
        while (true) {
            val keep = douglasPeucker(points, tolerance)
            if (keep.size <= max) return keep.map { points[it] }
            tolerance *= 2
        }
    }

    /** The indexes Douglas–Peucker keeps, both ends among them (in degrees, longitude by its scale). */
    private fun douglasPeucker(points: List<GeoPoint>, tolerance: Double): List<Int> {
        val n = points.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val cos = cos(points[n / 2].lat * PI / 180)
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack += 0 to n - 1
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ay = points[a].lat
            val ax = points[a].lon * cos
            val by = points[b].lat
            val bx = points[b].lon * cos
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val py = points[i].lat
                val px = points[i].lon * cos
                val d = if (len == 0.0) hypot(px - ax, py - ay) else abs(dx * (ay - py) - (ax - px) * dy) / len
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack += a to at
                stack += at to b
            }
        }
        return (0 until n).filter { keep[it] }
    }

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)

    private fun varint(out: MutableList<Byte>, value: Long) {
        var v = value
        while (true) {
            val x = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out += x.toByte()
                return
            }
            out += (x or 0x80).toByte()
        }
    }
}
