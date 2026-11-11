package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.shared.util.fixed
import app.maparound.shared.util.urlDecode
import app.maparound.shared.util.urlEncode

// Plain Kotlin: reading locations/routes out of links and text other apps send us, and building
// links we send. Unit-tested.

/** Something that was shared with MapAround. */
sealed class Shared {
    data class Point(val lat: Double, val lon: Double, val label: String? = null, val zoom: Int? = null) : Shared()
    data class Route(val points: List<GeoPoint>, val profile: RouteProfile?) : Shared()
}

object SharedLinks {

    // ---- reading -------------------------------------------------------------------------------

    /** Short links that only contain coordinates after following their redirects. */
    fun shortLink(text: String): String? =
        Regex("""https?://(maps\.app\.goo\.gl|goo\.gl/maps|g\.co/kgs|maps\.google\.[a-z.]+/\?cid=)[^\s]*""", RegexOption.IGNORE_CASE)
            .find(text)?.value

    /** Finds a location or route in a link, a geo: URI or free text. Null if there is none. */
    fun parse(input: String): Shared? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val label = labelFrom(raw)
        val text = decode(raw)

        route(text)?.let { return it }

        // geo:lat,lon?q=lat,lon(label)  /  geo:0,0?q=lat,lon(label)  /  geo:lat,lon?z=15
        Regex("""geo:(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""").find(text)?.let { m ->
            val q = Regex("""[?&]q=(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\(([^)]*)\))?""").find(text)
            val z = Regex("""[?&]z=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (q != null) point(q.groupValues[1], q.groupValues[2], q.groupValues[3].ifBlank { null } ?: label, z)?.let { return it }
            point(m.groupValues[1], m.groupValues[2], label, z)?.let { return it }
        }

        // Google "data" parameter (exact place position): !3d<lat>!4d<lon>
        Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(text)?.let { m ->
            point(m.groupValues[1], m.groupValues[2], googlePlace(text) ?: label, zoomOf(text))?.let { return it }
        }
        // Query parameters used by Google, Apple, Waze, OSM routing…
        Regex("""[?&#](?:q|query|ll|sll|center|destination|daddr|to|loc)=(?:loc:)?(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
            .find(text)?.let { m ->
                point(m.groupValues[1], m.groupValues[2], googlePlace(text) ?: appleLabel(text) ?: label, zoomOf(text))?.let { return it }
            }
        // OpenStreetMap marker
        Regex("""[?&]mlat=(-?\d+(?:\.\d+)?)&mlon=(-?\d+(?:\.\d+)?)""").find(text)?.let { m ->
            point(m.groupValues[1], m.groupValues[2], label, zoomOf(text))?.let { return it }
        }
        // Google viewport "@lat,lon,15z"
        Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)(?:,(\d+(?:\.\d+)?)z)?""").find(text)?.let { m ->
            point(m.groupValues[1], m.groupValues[2], googlePlace(text) ?: label, m.groupValues[3].toDoubleOrNull()?.toInt())?.let { return it }
        }
        // map=zoom/lat/lon (OSM, many web maps)
        Regex("""map=(\d+(?:\.\d+)?)/(-?\d+\.\d+)/(-?\d+\.\d+)""").find(text)?.let { m ->
            point(m.groupValues[2], m.groupValues[3], label, m.groupValues[1].toDoubleOrNull()?.toInt())?.let { return it }
        }
        // Degrees, minutes, seconds: 32°00'54.4"N 34°56'31.5"E
        val dms = Regex("""(\d{1,3})[°º]\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([NSns])[,\s]+(\d{1,3})[°º]\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([EWew])""")
        dms.find(text)?.let { m ->
            val g = m.groupValues
            val lat = (g[1].toDouble() + g[2].toDouble() / 60 + g[3].toDouble() / 3600) * if (g[4].equals("S", true)) -1 else 1
            val lon = (g[5].toDouble() + g[6].toDouble() / 60 + g[7].toDouble() / 3600) * if (g[8].equals("W", true)) -1 else 1
            point(lat.toString(), lon.toString(), label, null)?.let { return it }
        }
        // Plain decimal pair "32.01512, 34.94208" (needs a decimal point, to avoid matching other numbers)
        Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,})\s*[,;\s]\s*(-?\d{1,3}\.\d{3,})(?![\d.])""").find(text)?.let { m ->
            point(m.groupValues[1], m.groupValues[2], label, null)?.let { return it }
        }
        return null
    }

    /** Routes: BRouter-Web (lonlats=lon,lat;…), Google directions (/dir/lat,lon/lat,lon), OSM directions. */
    private fun route(text: String): Shared.Route? {
        Regex("""lonlats=([-\d.,;|]+)""").find(text)?.let { m ->
            val pts = m.groupValues[1].split(';', '|').mapNotNull { pair ->
                val (lon, lat) = pair.split(',').takeIf { it.size >= 2 } ?: return@mapNotNull null
                valid(lat.toDoubleOrNull(), lon.toDoubleOrNull())
            }
            val profile = Regex("""profile=([\w-]+)""").find(text)?.groupValues?.get(1)?.let(::profileOf)
            if (pts.size >= 2) return Shared.Route(pts, profile)
        }
        Regex("""/maps/dir/((?:-?\d+\.\d+,-?\d+\.\d+/?){2,})""").find(text)?.let { m ->
            val pts = m.groupValues[1].split('/').filter { it.isNotBlank() }.mapNotNull { pair ->
                val (lat, lon) = pair.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
                valid(lat.toDoubleOrNull(), lon.toDoubleOrNull())
            }
            if (pts.size >= 2) return Shared.Route(pts, null)
        }
        // Google directions API form: origin=lat,lon&destination=lat,lon&waypoints=lat,lon|lat,lon
        val origin = Regex("""[?&]origin=(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)
        val dest = Regex("""[?&]destination=(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)
        if (origin != null && dest != null) {
            val via = Regex("""[?&]waypoints=([-\d.,|]+)""").find(text)?.groupValues?.get(1)?.split('|').orEmpty()
                .mapNotNull { p -> p.split(',').takeIf { it.size == 2 }?.let { valid(it[0].toDoubleOrNull(), it[1].toDoubleOrNull()) } }
            val pts = listOfNotNull(valid(origin.groupValues[1].toDouble(), origin.groupValues[2].toDouble())) + via +
                listOfNotNull(valid(dest.groupValues[1].toDouble(), dest.groupValues[2].toDouble()))
            val mode = Regex("""travelmode=(\w+)""").find(text)?.groupValues?.get(1)
            if (pts.size >= 2) return Shared.Route(pts, when (mode) { "walking" -> RouteProfile.HIKE; "bicycling" -> RouteProfile.BIKE; "driving" -> RouteProfile.CAR; else -> null })
        }
        // OSM directions: route=lat,lon;lat,lon
        Regex("""[?&]route=(-?\d+\.\d+)(?:%2C|,)(-?\d+\.\d+)(?:%3B|;)(-?\d+\.\d+)(?:%2C|,)(-?\d+\.\d+)""").find(text)?.let { m ->
            val a = valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            val b = valid(m.groupValues[3].toDouble(), m.groupValues[4].toDouble())
            if (a != null && b != null) return Shared.Route(listOf(a, b), null)
        }
        return null
    }

    private fun profileOf(id: String): RouteProfile? = when {
        id.startsWith("hiking") || id == "shortest" -> RouteProfile.HIKE
        id.contains("bike") || id == "trekking" || id == "mtb" || id == "gravel" -> RouteProfile.BIKE
        id.startsWith("car") || id == "moped" -> RouteProfile.CAR
        else -> null
    }

    private fun point(lat: String, lon: String, label: String?, zoom: Int?): Shared.Point? {
        val p = valid(lat.toDoubleOrNull(), lon.toDoubleOrNull()) ?: return null
        return Shared.Point(p.lat, p.lon, label?.trim()?.takeIf { it.isNotEmpty() }, zoom?.coerceIn(2, 19))
    }

    private fun valid(lat: Double?, lon: Double?): GeoPoint? {
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(lat, lon)
    }

    private fun zoomOf(text: String): Int? =
        Regex("""[?&](?:z|zoom)=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""map=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(""",(\d+(?:\.\d+)?)z""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()

    /** Google place name: /maps/place/<name>/@… */
    private fun googlePlace(text: String): String? =
        Regex("""/maps/place/([^/@?]+)""").find(text)?.groupValues?.get(1)?.replace('+', ' ')?.takeIf { !it.first().isDigit() && !it.startsWith("-") }

    private fun appleLabel(text: String): String? =
        if ("maps.apple.com" in text) Regex("""[?&]q=([^&]+)""").find(text)?.groupValues?.get(1)?.replace('+', ' ')
            ?.takeUnless { Regex("""^-?\d+\.\d+,""").containsMatchIn(it) } else null

    /** A short first line that isn't a link, e.g. "Mount Tabor" in "Mount Tabor\nhttps://…". */
    private fun labelFrom(text: String): String? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (first.contains("://") || first.startsWith("geo:") || first.length > 60) return null
        if (Regex("""-?\d+\.\d+\s*,\s*-?\d+\.\d+""").containsMatchIn(first)) return null
        return first.removeSuffix(":").trim().takeIf { it.isNotEmpty() }
    }

    private fun decode(s: String): String = runCatching { urlDecode(s.replace("+", "%2B")) }.getOrDefault(s)

    // ---- writing -------------------------------------------------------------------------------

    private fun f(v: Double) = fixed(v, 6).trimEnd('0').trimEnd('.')

    fun googleLink(lat: Double, lon: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${f(lat)},${f(lon)}"

    fun osmLink(lat: Double, lon: Double, zoom: Int = 16): String =
        "https://www.openstreetmap.org/?mlat=${f(lat)}&mlon=${f(lon)}#map=$zoom/${f(lat)}/${f(lon)}"

    fun geoUri(lat: Double, lon: Double, label: String?): String =
        "geo:${f(lat)},${f(lon)}?q=${f(lat)},${f(lon)}" +
            (label?.takeIf { it.isNotBlank() }?.let { "(" + urlEncode(it).replace("+", "%20") + ")" } ?: "")

    /** Opens the route on brouter.de's website, recalculated with the same profile. */
    fun brouterWebLink(waypoints: List<GeoPoint>, profile: RouteProfile?): String? {
        if (waypoints.size < 2) return null
        val c = waypoints.first()
        val lonlats = waypoints.joinToString(";") { "${f(it.lon)},${f(it.lat)}" }
        val p = profile?.brouterProfile ?: "hiking-mountain"
        return "https://brouter.de/brouter-web/#map=13/${f(c.lat)}/${f(c.lon)}&lonlats=$lonlats&profile=$p"
    }

    /** Google Maps directions through up to 9 stops. */
    fun googleDirectionsLink(waypoints: List<GeoPoint>, profile: RouteProfile?): String? {
        if (waypoints.size < 2) return null
        val pts = if (waypoints.size > 11) listOf(waypoints.first()) + waypoints.subList(1, waypoints.lastIndex).let { mid ->
            val step = mid.size / 9.0
            (0 until 9).map { mid[(it * step).toInt()] }
        } + waypoints.last() else waypoints
        fun ll(p: GeoPoint) = "${f(p.lat)},${f(p.lon)}"
        val via = pts.subList(1, pts.lastIndex).joinToString("|") { ll(it) }
        val mode = when (profile) {
            RouteProfile.BIKE -> "bicycling"
            RouteProfile.CAR -> "driving"
            else -> "walking"
        }
        return "https://www.google.com/maps/dir/?api=1&origin=${ll(pts.first())}&destination=${ll(pts.last())}" +
            (if (via.isNotEmpty()) "&waypoints=" + via.replace("|", "%7C") else "") + "&travelmode=$mode"
    }

    /** Text for sharing a point: name, coordinates and links that open anywhere. */
    fun pointText(lat: Double, lon: Double, label: String?): String = buildString {
        label?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine("${f(lat)}, ${f(lon)}")
        appendLine("Google Maps: ${googleLink(lat, lon)}")
        append("OpenStreetMap: ${osmLink(lat, lon)}")
    }
}
