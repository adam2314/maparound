package app.maparound.tracks

import app.maparound.i18n.T
import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.routing.TrackStats
import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler
import app.maparound.shared.util.fixed
import app.maparound.shared.util.isoTime
import app.maparound.shared.util.parseIsoTime

// Plain Kotlin (Android and iOS), unit-tested on both.

enum class TrackKind {
    PLANNED,
    DRAWN,
    RECORDED,
    IMPORTED,
    ;

    /** The kind's name in the app's language. */
    val label: String
        get() = when (this) {
            PLANNED -> T.library.kindPlanned
            DRAWN -> T.library.kindDrawn
            RECORDED -> T.library.kindRecorded
            IMPORTED -> T.library.kindImported
        }
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

    /** Writes GPX to [w]: a java.io.Writer on Android, a StringBuilder anywhere. */
    fun write(track: Track, w: Appendable) {
        val name = escape(track.name)
        header(w)
        w.append("  <metadata><name>$name</name><time>${isoTime(track.created)}</time></metadata>\n")
        if (track.waypoints.size >= 2) {
            w.append("  <rte>\n    <name>$name</name>\n")
            for (p in track.waypoints) w.append("    <rtept lat=\"${fixed(p.lat, 7)}\" lon=\"${fixed(p.lon, 7)}\"/>\n")
            w.append("  </rte>\n")
        }
        w.append("  <trk>\n    <name>$name</name>\n    <type>${typeOf(track)}</type>\n    <trkseg>\n")
        for (p in track.points) {
            w.append("      <trkpt lat=\"${fixed(p.lat, 7)}\" lon=\"${fixed(p.lon, 7)}\">")
            p.ele?.let { w.append("<ele>${fixed(it, 1)}</ele>") }
            p.time?.let { w.append("<time>${isoTime(it)}</time>") }
            w.append("</trkpt>\n")
        }
        w.append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun toXml(track: Track): String = StringBuilder().also { write(track, it) }.toString()

    /** A single waypoint, for sharing a location with apps that take GPX (Garmin, OsmAnd, …). */
    fun writeWaypoint(name: String?, lat: Double, lon: Double, w: Appendable) {
        header(w)
        w.append("  <wpt lat=\"${fixed(lat, 7)}\" lon=\"${fixed(lon, 7)}\">")
        name?.takeIf { it.isNotBlank() }?.let { w.append("<name>${escape(it)}</name>") }
        w.append("</wpt>\n</gpx>\n")
    }

    private fun header(w: Appendable) {
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.append(
            "<gpx version=\"1.1\" creator=\"MapAround\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
    }

    /** Reads a GPX file's bytes (UTF-8 unless it declares another encoding). */
    fun parse(bytes: ByteArray): GpxContent = Handler().also { Xml.parse(bytes, it) }.content()

    fun parse(xml: String): GpxContent = Handler().also { Xml.parse(xml, it) }.content()

    /**
     * Reads a GPX file as one line: its track(s), or its route if it has no track. Waypoints
     * alone are not a line.
     * @throws IllegalArgumentException when the file has no track or route points.
     */
    fun read(bytes: ByteArray, fallbackName: String, fallbackTime: Long): Track = toTrack(parse(bytes), fallbackName, fallbackTime)

    fun read(xml: String, fallbackName: String, fallbackTime: Long): Track = toTrack(parse(xml), fallbackName, fallbackTime)

    fun toTrack(c: GpxContent, fallbackName: String, fallbackTime: Long): Track {
        val line = c.trackPoints.ifEmpty { c.routePoints }
        // Shown to the user after "Import failed: ".
        require(line.isNotEmpty()) { T.library.noTrackInGpx }
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
        track.kind.name.lowercase() + (track.profile?.let { "-" + it.name.lowercase() } ?: "")

    /** "planned-hike" -> (PLANNED, HIKE); other apps' types are kept as imported. */
    private fun parseType(type: String?): Pair<TrackKind?, RouteProfile?> {
        if (type == null) return null to null
        val parts = type.trim().uppercase().split('-')
        val kind = TrackKind.entries.firstOrNull { it.name == parts[0] } ?: return null to null
        val profile = parts.getOrNull(1)?.let { p -> RouteProfile.entries.firstOrNull { it.name == p } }
        return kind to profile
    }

    private fun escape(s: String): String = Xml.escape(s)

    private class Handler : XmlHandler {
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

        fun content() = GpxContent(trackName ?: routeName ?: metaName, type, metaTime, trkPoints, rtePoints, wpts)

        override fun start(name: String, attrs: Map<String, String>) {
            val local = name.substringAfter(':')
            path += local
            text.setLength(0)
            if (local == "trkpt" || local == "rtept" || local == "wpt") {
                lat = attrs["lat"]?.toDoubleOrNull() ?: Double.NaN
                lon = attrs["lon"]?.toDoubleOrNull() ?: Double.NaN
                ele = null
                time = null
                wptName = null
            }
        }

        override fun text(text: String) {
            this.text.append(text)
        }

        override fun end(name: String) {
            val local = name.substringAfter(':')
            val parent = path.getOrNull(path.size - 2)
            val value = text.toString().trim()
            val inPoint = parent == "trkpt" || parent == "rtept" || parent == "wpt"
            when {
                local == "ele" && inPoint -> ele = value.toDoubleOrNull()
                local == "time" && inPoint -> time = parseIsoTime(value)
                local == "name" && parent == "wpt" -> wptName = value.ifEmpty { null }
                local == "time" && parent == "metadata" -> metaTime = parseIsoTime(value)
                local == "name" && parent == "metadata" -> metaName = value.ifEmpty { null }
                local == "name" && parent == "trk" && trackName == null -> trackName = value.ifEmpty { null }
                local == "name" && parent == "rte" && routeName == null -> routeName = value.ifEmpty { null }
                local == "type" && parent == "trk" && type == null -> type = value
                !lat.isNaN() && !lon.isNaN() && local == "trkpt" -> trkPoints += GeoPoint(lat, lon, ele, time)
                !lat.isNaN() && !lon.isNaN() && local == "rtept" -> rtePoints += GeoPoint(lat, lon, ele, time)
                !lat.isNaN() && !lon.isNaN() && local == "wpt" -> wpts += NamedPoint(lat, lon, wptName)
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }
    }
}
