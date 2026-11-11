package app.maparound.places

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler
import app.maparound.shared.util.fixed
import app.maparound.shared.util.isoTime
import app.maparound.shared.util.nowMillis
import app.maparound.shared.util.parseIsoTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Plain Kotlin: your saved places (points of interest), stored as GPX waypoints. Unit-tested.

enum class PlaceCategory(val label: String, val emoji: String, val argb: Long, /** Garmin-style GPX symbol. */ val gpxSym: String) {
    FAVORITE("Favourite", "⭐", 0xFFF9A825, "Flag, Blue"),
    CAMP("Camp", "⛺", 0xFF2E7D32, "Campground"),
    WATER("Water", "💧", 0xFF1E88E5, "Drinking Water"),
    VIEW("Viewpoint", "🔭", 0xFF6A1B9A, "Scenic Area"),
    PARKING("Parking", "🅿️", 0xFF455A64, "Parking Area"),
    FUEL("Fuel", "⛽", 0xFFE65100, "Gas Station"),
    FOOD("Food", "🍴", 0xFFD84315, "Restaurant"),
    DANGER("Danger", "⚠️", 0xFFC62828, "Danger Area"),
    OTHER("Other", "📍", 0xFF546E7A, "Waypoint"),
    ;

    companion object {
        /** Best guess from another app's GPX `<sym>` / `<type>` (Garmin, OsmAnd, …). */
        fun guess(vararg hints: String?): PlaceCategory {
            val text = hints.filterNotNull().joinToString(" ").lowercase()
            if (text.isBlank()) return OTHER
            entries.firstOrNull { text.contains("maparound:" + it.name.lowercase()) }?.let { return it }
            return when {
                Regex("camp|tent|shelter|hut|bivouac").containsMatchIn(text) -> CAMP
                Regex("water|spring|fountain|well").containsMatchIn(text) -> WATER
                Regex("scenic|view|summit|peak|overlook").containsMatchIn(text) -> VIEW
                Regex("parking|car").containsMatchIn(text) -> PARKING
                Regex("gas|fuel|petrol").containsMatchIn(text) -> FUEL
                Regex("restaurant|food|cafe|bar|pizza|fast").containsMatchIn(text) -> FOOD
                Regex("danger|hazard|caution|skull|warning").containsMatchIn(text) -> DANGER
                Regex("favou?rite|star|flag|heart").containsMatchIn(text) -> FAVORITE
                else -> OTHER
            }
        }
    }
}

data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val category: PlaceCategory = PlaceCategory.OTHER,
    val note: String = "",
    val created: Long = nowMillis(),
) {
    val point: GeoPoint get() = GeoPoint(lat, lon, ele)

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

object Places {
    enum class Sort(val label: String) { NEAREST("Nearest"), NAME("Name"), NEWEST("Newest") }

    /** Filters by text (name and note) and category, then sorts. Nearest needs [from]. */
    fun filter(places: List<Place>, query: String, category: PlaceCategory?, sort: Sort, from: GeoPoint?): List<Place> {
        val q = query.trim().lowercase()
        val hits = places.filter { p ->
            (category == null || p.category == category) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.note.lowercase().contains(q))
        }
        return when {
            sort == Sort.NEAREST && from != null -> hits.sortedBy { Geo.distance(from, it.point) }
            sort == Sort.NAME -> hits.sortedBy { it.name.lowercase() }
            else -> hits.sortedByDescending { it.created }
        }
    }

    /** Adds [incoming] places that aren't already there (same name within 10 m). Returns the merged list and how many were new. */
    fun merge(existing: List<Place>, incoming: List<Place>): Pair<List<Place>, Int> {
        val out = existing.toMutableList()
        var added = 0
        for (p in incoming) {
            val dup = out.any { it.name.equals(p.name, ignoreCase = true) && Geo.distance(it.point, p.point) < 10 }
            if (!dup) {
                out += p
                added++
            }
        }
        return out to added
    }

    // ---- GPX ------------------------------------------------------------------------------------------

    /** Writes GPX to [w]: a java.io.Writer on Android, a StringBuilder anywhere. */
    fun writeGpx(places: List<Place>, w: Appendable, title: String = "MapAround places") {
        w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.append(
            "<gpx version=\"1.1\" creator=\"MapAround\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
        w.append("  <metadata><name>${esc(title)}</name></metadata>\n")
        for (p in places) {
            w.append("  <wpt lat=\"${fixed(p.lat, 7)}\" lon=\"${fixed(p.lon, 7)}\">")
            p.ele?.let { w.append("<ele>${fixed(it, 1)}</ele>") }
            w.append("<time>${isoTime(p.created)}</time>")
            w.append("<name>${esc(p.name)}</name>")
            if (p.note.isNotBlank()) w.append("<desc>${esc(p.note)}</desc>")
            // <link> keeps our id so edits survive a round trip; <type> keeps the exact category.
            w.append("<link href=\"maparound:place/${esc(p.id)}\"/>")
            w.append("<sym>${esc(p.category.gpxSym)}</sym>")
            w.append("<type>maparound:${p.category.name.lowercase()}</type>")
            w.append("</wpt>\n")
        }
        w.append("</gpx>\n")
    }

    /** Every `<wpt>` in a GPX file as a place (categories guessed from `<sym>` / `<type>`). */
    fun readGpx(bytes: ByteArray): List<Place> = WptHandler().also { Xml.parse(bytes, it) }.places

    fun readGpx(xml: String): List<Place> = WptHandler().also { Xml.parse(xml, it) }.places

    private fun esc(s: String) = Xml.escape(s)

    private class WptHandler : XmlHandler {
        val places = ArrayList<Place>()
        private var inWpt = false
        private var lat = Double.NaN
        private var lon = Double.NaN
        private val fields = HashMap<String, String>()
        private var link: String? = null
        private val text = StringBuilder()

        override fun start(name: String, attrs: Map<String, String>) {
            val local = name.substringAfter(':')
            text.setLength(0)
            if (local == "wpt") {
                inWpt = true
                lat = attrs["lat"]?.toDoubleOrNull() ?: Double.NaN
                lon = attrs["lon"]?.toDoubleOrNull() ?: Double.NaN
                fields.clear()
                link = null
            } else if (inWpt && local == "link") {
                link = attrs["href"]
            }
        }

        override fun text(text: String) {
            this.text.append(text)
        }

        override fun end(name: String) {
            val local = name.substringAfter(':')
            if (!inWpt) return
            if (local == "wpt") {
                inWpt = false
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
                val created = fields["time"]?.let(::parseIsoTime)
                places += Place(
                    id = link?.takeIf { it.startsWith("maparound:place/") }?.substringAfter("maparound:place/") ?: Place.newId(),
                    name = fields["name"]?.takeIf { it.isNotBlank() } ?: fields["cmt"]?.takeIf { it.isNotBlank() }
                        ?: "${fixed(lat, 5)}, ${fixed(lon, 5)}",
                    lat = lat,
                    lon = lon,
                    ele = fields["ele"]?.toDoubleOrNull(),
                    category = PlaceCategory.guess(fields["type"], fields["sym"]),
                    note = listOfNotNull(fields["desc"], fields["cmt"]?.takeIf { it != fields["name"] }).joinToString("\n"),
                    created = created ?: nowMillis(),
                )
            } else if (local in setOf("name", "desc", "cmt", "sym", "type", "ele", "time")) {
                fields[local] = text.toString().trim()
            }
            text.setLength(0)
        }
    }
}
