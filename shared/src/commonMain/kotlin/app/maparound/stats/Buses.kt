package app.maparound.stats

import app.maparound.tools.MapGrid
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

// Plain Kotlin: Israel's buses where they are now, from the Ministry of Transport's real-time feed
// (SIRI) as the Public Knowledge Workshop's Open Bus project republishes it, a snapshot a minute.
// Unit-tested.

object Buses {
    /** A bus's tap key: "data:b:<its ride's id>". */
    const val TAP = "data:b:"

    /** Open Bus's API (the default; Settings → Sources can give another). */
    const val OPEN_BUS = "https://open-bus-stride-api.hasadna.org.il"

    /** Where the feed has buses: Israel and the West Bank (south, west, north, east). */
    val AREA = MapGrid.View(29.4, 34.2, 33.4, 35.95)

    /** A position older than this when its snapshot was taken is a bus that's finished. */
    const val FRESH_MS = 4 * 60_000L

    /** At most this many buses at once (a whole city zoomed out). */
    const val MAX = 3000

    /** A snapshot of the feed: its id and when it was taken. */
    data class Snapshot(val id: Long, val timeMillis: Long)

    /** A bus: its ride (one trip of a line), where it was and when, which way, how fast (km/h). */
    data class Bus(
        val rideId: Long,
        val lineRef: Long,
        val lat: Double,
        val lon: Double,
        val timeMillis: Long,
        val bearing: Int?,
        val speedKmh: Int?,
        val vehicle: String?,
    )

    /** A line on a day: its number, operator, and the way it goes ([from] and [to]: city and stop). */
    data class Route(val lineRef: Long, val number: String, val operator: String, val from: Place?, val to: Place?)

    /** A route's end: its city and stop. */
    data class Place(val city: String, val stop: String)

    /** The latest snapshots. */
    fun snapshotsUrl(base: String): String = "${base.trimEnd('/')}/siri_snapshots/list?limit=5&order_by=id%20desc"

    /** The newest snapshot that's been read in full ("loaded"), or null. */
    fun latestLoaded(json: String): Snapshot? {
        val items = Json.parseToJsonElement(json) as? JsonArray ?: return null
        return items.mapNotNull { it as? JsonObject }
            .filter { (it["etl_status"] as? JsonPrimitive)?.content == "loaded" }
            .mapNotNull { o ->
                val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                // "2026/11/18/05/30": the minute it was taken (UTC).
                val t = (o["snapshot_id"] as? JsonPrimitive)?.content?.split('/')?.mapNotNull { it.toIntOrNull() }
                    ?.takeIf { it.size == 5 } ?: return@mapNotNull null
                val time = runCatching { kotlinx.datetime.LocalDateTime(t[0], t[1], t[2], t[3], t[4]) }.getOrNull() ?: return@mapNotNull null
                Snapshot(id, time.toInstant(TimeZone.UTC).toEpochMilliseconds())
            }
            .maxByOrNull { it.id }
    }

    /** The part of [view] the feed covers, or null when it's elsewhere. */
    fun clip(view: MapGrid.View): MapGrid.View? {
        val v = MapGrid.View(
            maxOf(view.south, AREA.south), maxOf(view.west, AREA.west),
            minOf(view.north, AREA.north), minOf(view.east, AREA.east),
        )
        return v.takeIf { it.south < it.north && it.west < it.east }
    }

    /**
     * The buses in [area] in [snapshot] (rides that have ended too: [parseLocations] leaves them out;
     * asking the server to, by time, makes it a dozen seconds slower).
     */
    fun locationsUrl(base: String, snapshot: Snapshot, area: MapGrid.View): String =
        "${base.trimEnd('/')}/siri_vehicle_locations/list?limit=$MAX&siri_snapshot_ids=${snapshot.id}" +
            "&lat__greater_or_equal=${fmt(area.south)}&lat__lower_or_equal=${fmt(area.north)}" +
            "&lon__greater_or_equal=${fmt(area.west)}&lon__lower_or_equal=${fmt(area.east)}"

    /**
     * The buses in [locationsUrl]'s answer: each ride's latest position, if it's from [snapshot]'s
     * last [FRESH_MS] (an older one is a ride that has ended).
     */
    fun parseLocations(json: String, snapshot: Snapshot? = null): List<Bus> {
        val from = snapshot?.let { it.timeMillis - FRESH_MS } ?: Long.MIN_VALUE
        val items = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        val byRide = HashMap<Long, Bus>()
        for (e in items) {
            val o = e as? JsonObject ?: continue
            fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val ride = long("siri_ride__id") ?: continue
            val bus = Bus(
                rideId = ride,
                lineRef = long("siri_route__line_ref") ?: continue,
                lat = num("lat") ?: continue,
                lon = num("lon") ?: continue,
                timeMillis = (o["recorded_at_time"] as? JsonPrimitive)?.content?.let(::isoMillis)?.takeIf { it >= from } ?: continue,
                bearing = (o["bearing"] as? JsonPrimitive)?.intOrNull,
                speedKmh = (o["velocity"] as? JsonPrimitive)?.intOrNull,
                vehicle = (o["siri_ride__vehicle_ref"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" },
            )
            val had = byRide[ride]
            if (had == null || bus.timeMillis > had.timeMillis) byRide[ride] = bus
        }
        // A bus that's finished one ride and started the next is in both: once, on the newer.
        val (known, unknown) = byRide.values.partition { it.vehicle != null }
        val byVehicle = known.groupBy { it.vehicle }.values.map { rides -> rides.maxBy { it.timeMillis } }
        return (byVehicle + unknown).sortedBy { it.rideId }
    }

    /** The lines [lineRefs] on [date] ("2026-11-18"), from the timetable (GTFS). */
    fun routesUrl(base: String, lineRefs: Collection<Long>, date: String): String =
        "${base.trimEnd('/')}/gtfs_routes/list?limit=${lineRefs.size.coerceAtLeast(1) * 2}&date_from=$date&date_to=$date&line_refs=${lineRefs.joinToString(",")}"

    fun parseRoutes(json: String): List<Route> {
        val items = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            // Missing values come as "null", or "NaN" (the timetable's own gaps).
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { it != "null" && it != "NaN" }
            val ref = (o["line_ref"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = str("route_long_name").orEmpty()
            Route(
                lineRef = ref,
                number = str("route_short_name").orEmpty(),
                operator = str("agency_name").orEmpty(),
                from = place(name.substringBefore("<->", "")),
                to = place(name.substringAfter("<->", "")),
            )
        }.distinctBy { it.lineRef }
    }

    /**
     * A route's end as the timetable writes it, "stop-city" and, after the destination, the
     * direction and alternative ("ביה''ח תל השומר/מיון-רמת גן-12"): its city and stop.
     */
    fun place(text: String): Place? {
        val t = text.trim().replace(Regex("-[0-9#]+$"), "").trim()
        if (t.isEmpty()) return null
        val cut = t.lastIndexOf('-')
        if (cut <= 0) return Place(t, "")
        return Place(t.substring(cut + 1).trim(), t.substring(0, cut).trim().trimEnd('-').trim())
    }

    /**
     * The day in Israel at [millis] ("2026-11-18"), its clocks [offsetSeconds] ahead of UTC (the
     * browser knows; else winter's two hours): the timetable's days are Israel's.
     */
    fun israelDate(millis: Long, offsetSeconds: Int?): String =
        Instant.fromEpochMilliseconds(millis + (offsetSeconds ?: 7200) * 1000L).toLocalDateTime(TimeZone.UTC).date.toString()

    /** The buses as GeoJSON, labelled with their line's number where it's known. */
    fun geoJson(buses: List<Bus>, routes: Map<Long, Route>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        buses.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .append(b.lon).append(',').append(b.lat)
                .append("""]},"properties":{"tap":"$TAP${b.rideId}","label":""")
                .append(JsonPrimitive(routes[b.lineRef]?.number.orEmpty()).toString()).append("}}")
        }
        return sb.append("]}").toString()
    }

    private fun fmt(v: Double): String = ((v * 1e4).roundToLong() / 1e4).toString()
}
