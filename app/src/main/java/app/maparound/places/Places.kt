package app.maparound.places

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.Writer
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

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
            val text = hints.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
            if (text.isBlank()) return OTHER
            entries.firstOrNull { text.contains("maparound:" + it.name.lowercase(Locale.ROOT)) }?.let { return it }
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
    val created: Long = System.currentTimeMillis(),
) {
    val point: GeoPoint get() = GeoPoint(lat, lon, ele)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

object Places {
    enum class Sort(val label: String) { NEAREST("Nearest"), NAME("Name"), NEWEST("Newest") }

    /** Filters by text (name and note) and category, then sorts. Nearest needs [from]. */
    fun filter(places: List<Place>, query: String, category: PlaceCategory?, sort: Sort, from: GeoPoint?): List<Place> {
        val q = query.trim().lowercase(Locale.getDefault())
        val hits = places.filter { p ->
            (category == null || p.category == category) &&
                (q.isEmpty() || p.name.lowercase(Locale.getDefault()).contains(q) || p.note.lowercase(Locale.getDefault()).contains(q))
        }
        return when {
            sort == Sort.NEAREST && from != null -> hits.sortedBy { Geo.distance(from, it.point) }
            sort == Sort.NAME -> hits.sortedBy { it.name.lowercase(Locale.getDefault()) }
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

    fun writeGpx(places: List<Place>, w: Writer, title: String = "MapAround places") {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write(
            "<gpx version=\"1.1\" creator=\"MapAround\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
        w.write("  <metadata><name>${esc(title)}</name></metadata>\n")
        for (p in places) {
            w.write(String.format(Locale.ROOT, "  <wpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon))
            p.ele?.let { w.write(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
            w.write("<time>${Instant.ofEpochMilli(p.created)}</time>")
            w.write("<name>${esc(p.name)}</name>")
            if (p.note.isNotBlank()) w.write("<desc>${esc(p.note)}</desc>")
            // <link> keeps our id so edits survive a round trip; <type> keeps the exact category.
            w.write("<link href=\"maparound:place/${esc(p.id)}\"/>")
            w.write("<sym>${esc(p.category.gpxSym)}</sym>")
            w.write("<type>maparound:${p.category.name.lowercase(Locale.ROOT)}</type>")
            w.write("</wpt>\n")
        }
        w.write("</gpx>\n")
    }

    /** Every `<wpt>` in a GPX file as a place (categories guessed from `<sym>` / `<type>`). */
    fun readGpx(input: InputStream): List<Place> {
        val h = WptHandler()
        SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser().parse(input, h)
        return h.places
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private class WptHandler : DefaultHandler() {
        val places = ArrayList<Place>()
        private var inWpt = false
        private var lat = Double.NaN
        private var lon = Double.NaN
        private val fields = HashMap<String, String>()
        private var link: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val name = qName.substringAfter(':')
            text.setLength(0)
            if (name == "wpt") {
                inWpt = true
                lat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                lon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                fields.clear()
                link = null
            } else if (inWpt && name == "link") {
                link = attrs.getValue("href")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = qName.substringAfter(':')
            if (!inWpt) return
            if (name == "wpt") {
                inWpt = false
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
                val created = fields["time"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                places += Place(
                    id = link?.takeIf { it.startsWith("maparound:place/") }?.substringAfter("maparound:place/") ?: Place.newId(),
                    name = fields["name"]?.takeIf { it.isNotBlank() } ?: fields["cmt"]?.takeIf { it.isNotBlank() }
                        ?: String.format(Locale.ROOT, "%.5f, %.5f", lat, lon),
                    lat = lat,
                    lon = lon,
                    ele = fields["ele"]?.toDoubleOrNull(),
                    category = PlaceCategory.guess(fields["type"], fields["sym"]),
                    note = listOfNotNull(fields["desc"], fields["cmt"]?.takeIf { it != fields["name"] }).joinToString("\n"),
                    created = created ?: System.currentTimeMillis(),
                )
            } else if (name in setOf("name", "desc", "cmt", "sym", "type", "ele", "time")) {
                fields[name] = text.toString().trim()
            }
            text.setLength(0)
        }
    }
}
