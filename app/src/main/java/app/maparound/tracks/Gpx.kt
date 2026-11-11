package app.maparound.tracks

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.routing.TrackStats
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

// Plain Kotlin/JVM (SAX is available on Android and the desktop), so it is unit-tested.

enum class TrackKind(val label: String) {
    PLANNED("Planned route"),
    DRAWN("Drawn route"),
    RECORDED("Recorded track"),
    IMPORTED("Imported"),
}

/**
 * A saved route or track. Stored as one GPX file: the line as `<trk>`, and for planned routes the
 * tapped stops ([waypoints]) as `<rte>`, so the route can be recalculated or shared as a link.
 */
data class Track(
    val name: String,
    val kind: TrackKind,
    val points: List<GeoPoint>,
    val created: Long,
    val profile: RouteProfile? = null,
    val waypoints: List<GeoPoint> = emptyList(),
) {
    val stats: TrackStats by lazy { TrackStats.of(points) }
}

/** A GPX waypoint (`<wpt>`), e.g. a shared location. */
data class NamedPoint(val lat: Double, val lon: Double, val name: String?)

/** Everything read from a GPX file. */
class GpxContent(
    val name: String?,
    val type: String?,
    val created: Long?,
    val trackPoints: List<GeoPoint>,
    val routePoints: List<GeoPoint>,
    val waypoints: List<NamedPoint>,
)

object Gpx {

    fun write(track: Track, out: OutputStream) {
        out.bufferedWriter(Charsets.UTF_8).let { w ->
            write(track, w)
            w.flush()
        }
    }

    fun write(track: Track, w: Writer) {
        val name = escape(track.name)
        header(w)
        w.write("  <metadata><name>$name</name><time>${Instant.ofEpochMilli(track.created)}</time></metadata>\n")
        if (track.waypoints.size >= 2) {
            w.write("  <rte>\n    <name>$name</name>\n")
            for (p in track.waypoints) w.write(String.format(Locale.ROOT, "    <rtept lat=\"%.7f\" lon=\"%.7f\"/>\n", p.lat, p.lon))
            w.write("  </rte>\n")
        }
        w.write("  <trk>\n    <name>$name</name>\n    <type>${typeOf(track)}</type>\n    <trkseg>\n")
        for (p in track.points) {
            w.write(String.format(Locale.ROOT, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon))
            p.ele?.let { w.write(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
            p.time?.let { w.write("<time>${Instant.ofEpochMilli(it)}</time>") }
            w.write("</trkpt>\n")
        }
        w.write("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** A single waypoint, for sharing a location with apps that take GPX (Garmin, OsmAnd, …). */
    fun writeWaypoint(name: String?, lat: Double, lon: Double, out: OutputStream) {
        out.bufferedWriter(Charsets.UTF_8).let { w ->
            header(w)
            w.write(String.format(Locale.ROOT, "  <wpt lat=\"%.7f\" lon=\"%.7f\">", lat, lon))
            name?.takeIf { it.isNotBlank() }?.let { w.write("<name>${escape(it)}</name>") }
            w.write("</wpt>\n</gpx>\n")
            w.flush()
        }
    }

    private fun header(w: Writer) {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write(
            "<gpx version=\"1.1\" creator=\"MapAround\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
    }

    fun parse(input: InputStream): GpxContent {
        val h = Handler()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        factory.newSAXParser().parse(input, h)
        return GpxContent(h.trackName ?: h.routeName ?: h.metaName, h.type, h.metaTime, h.trkPoints, h.rtePoints, h.wpts)
    }

    /**
     * Reads a GPX file as one line: its track(s), or its route if it has no track. Waypoints
     * alone are not a line.
     * @throws IllegalArgumentException when the file has no track or route points.
     */
    fun read(input: InputStream, fallbackName: String, fallbackTime: Long): Track = toTrack(parse(input), fallbackName, fallbackTime)

    fun toTrack(c: GpxContent, fallbackName: String, fallbackTime: Long): Track {
        val line = c.trackPoints.ifEmpty { c.routePoints }
        require(line.isNotEmpty()) { "No track or route in this GPX file" }
        val (kind, profile) = parseType(c.type)
        return Track(
            name = c.name ?: fallbackName,
            kind = kind ?: TrackKind.IMPORTED,
            points = line,
            created = c.created ?: line.firstNotNullOfOrNull { it.time } ?: fallbackTime,
            profile = profile,
            waypoints = if (c.trackPoints.isNotEmpty()) c.routePoints else emptyList(),
        )
    }

    private fun typeOf(track: Track): String =
        track.kind.name.lowercase(Locale.ROOT) + (track.profile?.let { "-" + it.name.lowercase(Locale.ROOT) } ?: "")

    /** "planned-hike" -> (PLANNED, HIKE); other apps' types are kept as imported. */
    private fun parseType(type: String?): Pair<TrackKind?, RouteProfile?> {
        if (type == null) return null to null
        val parts = type.trim().uppercase(Locale.ROOT).split('-')
        val kind = TrackKind.entries.firstOrNull { it.name == parts[0] } ?: return null to null
        val profile = parts.getOrNull(1)?.let { p -> RouteProfile.entries.firstOrNull { it.name == p } }
        return kind to profile
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private class Handler : DefaultHandler() {
        val trkPoints = ArrayList<GeoPoint>()
        val rtePoints = ArrayList<GeoPoint>()
        val wpts = ArrayList<NamedPoint>()
        var trackName: String? = null
        var routeName: String? = null
        var metaName: String? = null
        var metaTime: Long? = null
        var type: String? = null

        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var lat = 0.0
        private var lon = 0.0
        private var ele: Double? = null
        private var time: Long? = null
        private var wptName: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val name = qName.substringAfter(':')
            path += name
            text.setLength(0)
            if (name == "trkpt" || name == "rtept" || name == "wpt") {
                lat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                lon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                ele = null
                time = null
                wptName = null
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = qName.substringAfter(':')
            val parent = path.getOrNull(path.size - 2)
            val value = text.toString().trim()
            val inPoint = parent == "trkpt" || parent == "rtept" || parent == "wpt"
            when {
                name == "ele" && inPoint -> ele = value.toDoubleOrNull()
                name == "time" && inPoint -> time = parseTime(value)
                name == "name" && parent == "wpt" -> wptName = value.ifEmpty { null }
                name == "time" && parent == "metadata" -> metaTime = parseTime(value)
                name == "name" && parent == "metadata" -> metaName = value.ifEmpty { null }
                name == "name" && parent == "trk" && trackName == null -> trackName = value.ifEmpty { null }
                name == "name" && parent == "rte" && routeName == null -> routeName = value.ifEmpty { null }
                name == "type" && parent == "trk" && type == null -> type = value
                !lat.isNaN() && !lon.isNaN() && name == "trkpt" -> trkPoints += GeoPoint(lat, lon, ele, time)
                !lat.isNaN() && !lon.isNaN() && name == "rtept" -> rtePoints += GeoPoint(lat, lon, ele, time)
                !lat.isNaN() && !lon.isNaN() && name == "wpt" -> wpts += NamedPoint(lat, lon, wptName)
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun parseTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    }
}
