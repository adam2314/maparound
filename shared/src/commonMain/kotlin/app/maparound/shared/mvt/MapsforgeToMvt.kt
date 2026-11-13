package app.maparound.shared.mvt

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mapsforge.MfData
import app.maparound.shared.mapsforge.MfTag
import app.maparound.shared.mapsforge.MfWay

/**
 * Turns mapsforge map data into vector tiles for MapLibre. Layers: "poi" (points), "line",
 * "area" (polygons), "sea" (whole water blocks) and "trails" (marked trail lines, `trail=*`, in
 * MapAround's own maps). Every OSM tag becomes a property; names are split into `name` (the local
 * one) and `name_<lang>` (other languages mapsforge stores).
 */
object MapsforgeToMvt {
    /** The layer of marked trail lines built into the maps. */
    const val TRAILS = "trails"

    /**
     * The vector tile z/x/y from several maps (a world overview and country maps). MapLibre's
     * tiles are 512 px, so data is read one zoom deeper than the tile, like a 256 px map would.
     */
    fun tile(maps: List<MapsforgeFile>, z: Int, x: Int, y: Int, maxReadZoom: Int = 17): ByteArray {
        val t = MvtTile(z, x, y)
        fill(t, maps, z, x, y, maxReadZoom)
        return t.encode()
    }

    /**
     * Adds the maps' data for the tile to [t] (z/x/y as in [tile]). With [ground] (heights in
     * metres, NaN where unknown), buildings get how their ground varies, for the 3D view: see
     * [RELIEF_UP] and [RELIEF_DOWN].
     */
    fun fill(t: MvtTile, maps: List<MapsforgeFile>, z: Int, x: Int, y: Int, maxReadZoom: Int = 17, ground: ((Double, Double) -> Float)? = null) {
        val readZoom = minOf(z + 1, maxReadZoom)
        val shift = readZoom - z
        var water = maps.isNotEmpty()
        var anyCovered = false
        for (m in maps) {
            val h = m.header
            if (!overlaps(h.minLat, h.minLon, h.maxLat, h.maxLon, z, x, y)) continue
            anyCovered = true
            // Read the four (or one) sub-tiles at the read zoom.
            val n = 1 shl shift
            for (dy in 0 until n) for (dx in 0 until n) {
                val data = m.read(readZoom, (x shl shift) + dx, (y shl shift) + dy)
                add(t, data, ground.takeIf { z >= RELIEF_MIN_ZOOM })
                if (!data.isWater) water = false
            }
        }
        if (anyCovered && water) {
            t.layer("sea").polygon(listOf(tileRing(z, x, y)), mapOf("natural" to "sea"))
        }
    }

    fun add(t: MvtTile, data: MfData, ground: ((Double, Double) -> Float)? = null) {
        val poi = t.layer("poi")
        for (p in data.pois) poi.point(p.lat, p.lon, props(p.tags, p.layer))
        for (w in data.ways) {
            val props = props(w.tags, w.layer)
            if (w.tags.any { it.key == "trail" }) {
                // Marked trail lines built into MapAround's maps (my-pipeline): their own layer, like
                // the downloaded ones, so the trail switch and the styles' trail rules apply.
                for (ring in w.rings) t.layer(TRAILS).line(ring, props)
            } else if (isArea(w)) {
                val relief = if (ground != null && w.tags.any { it.key == "building" || it.key == "building:part" }) relief(w.rings[0], ground) else null
                t.layer("area").polygon(w.rings, if (relief != null) props + relief else props)
                // Labels and symbols of areas (names, a parking's P, a hut…) go in the point
                // layer, at mapsforge's label position or else the middle of the outline; only in
                // the tile that holds that point (the area itself is in every tile it crosses).
                if (props.containsKey("name") || props.keys.any { it in POI_KEYS }) {
                    val (lat, lon) = if (w.labelLat != null && w.labelLon != null) w.labelLat to w.labelLon else middle(w.rings[0])
                    if (inside(t, lat, lon)) poi.point(lat, lon, props + ("label_of" to "area"))
                }
            } else {
                for (ring in w.rings) t.layer("line").line(ring, props)
            }
        }
    }

    /** Closed ways that are surfaces (building, landuse, water…) rather than lines (roundabouts, fences…). */
    fun isArea(w: MfWay): Boolean {
        val outer = w.rings.firstOrNull() ?: return false
        val n = outer.size / 2
        // Like mapsforge: closed if the ends meet within ~1 cm (decoded coordinates carry rounding).
        val closed = n >= 4 && kotlin.math.abs(outer[0] - outer[2 * n - 2]) < 1e-7 && kotlin.math.abs(outer[1] - outer[2 * n - 1]) < 1e-7
        if (!closed && w.rings.size == 1) return false
        val tags = w.tags.associate { it.key to it.value }
        if (tags["area"] == "no") return false
        if (tags["area"] == "yes") return true
        if (tags.keys.any { it in AREA_KEYS }) {
            // Linear features that share those keys.
            val natural = tags["natural"]
            if (natural in setOf("coastline", "cliff", "ridge", "arete", "tree_row")) return false
            val barrier = tags["barrier"]
            if (barrier != null && tags.size <= 2) return false
            return true
        }
        return tags["highway"] == "pedestrian" || tags["natural"] == "issea" || tags["natural"] == "nosea"
    }

    /**
     * How the ground under a building's corners varies from the ground at their middle (where
     * MapLibre stands a building on terrain): [RELIEF_UP] to the highest corner, [RELIEF_DOWN] to
     * the lowest, in metres (one decimal). Null when the ground there isn't known.
     */
    internal fun relief(ring: DoubleArray, ground: (Double, Double) -> Float): Map<String, Any>? {
        val n = ring.size / 2
        if (n < 3) return null
        var sLat = 0.0
        var sLon = 0.0
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until n) {
            val h = ground(ring[2 * i], ring[2 * i + 1])
            if (h.isNaN()) return null
            low = minOf(low, h)
            high = maxOf(high, h)
            sLat += ring[2 * i]
            sLon += ring[2 * i + 1]
        }
        val mid = ground(sLat / n, sLon / n)
        if (mid.isNaN()) return null
        val up = kotlin.math.round((high - mid) * 10) / 10.0
        val down = kotlin.math.round((mid - low) * 10) / 10.0
        if (up <= 0.0 && down <= 0.0) return null
        return mapOf(RELIEF_UP to up, RELIEF_DOWN to down)
    }

    /** Metres from the ground at a building's middle up to its highest corner. */
    const val RELIEF_UP = "relief_up"

    /** Metres from the ground at a building's middle down to its lowest corner. */
    const val RELIEF_DOWN = "relief_down"

    /** From this tile zoom on (the 3D view's buildings start at 14). */
    private const val RELIEF_MIN_ZOOM = 13

    /** Areas that get a point for their symbol even without a name. */
    private val POI_KEYS = setOf("amenity", "tourism", "shop", "historic", "leisure", "sport", "man_made")

    private fun inside(t: MvtTile, lat: Double, lon: Double): Boolean =
        lat <= MapsforgeFile.tileLat(t.y, t.z) && lat > MapsforgeFile.tileLat(t.y + 1, t.z) &&
            lon >= MapsforgeFile.tileLon(t.x, t.z) && lon < MapsforgeFile.tileLon(t.x + 1, t.z)

    /** Middle of a ring's bounding box ([lat0, lon0, …]). */
    private fun middle(ring: DoubleArray): Pair<Double, Double> {
        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0
        for (i in 0 until ring.size / 2) {
            minLat = minOf(minLat, ring[2 * i]); maxLat = maxOf(maxLat, ring[2 * i])
            minLon = minOf(minLon, ring[2 * i + 1]); maxLon = maxOf(maxLon, ring[2 * i + 1])
        }
        return (minLat + maxLat) / 2 to (minLon + maxLon) / 2
    }

    private val AREA_KEYS = setOf(
        "building", "building:part", "landuse", "landcover", "natural", "leisure", "amenity", "tourism",
        "man_made", "military", "aeroway", "water", "waterway_area", "wetland", "place", "shop", "historic",
        "boundary_area", "sea", "parking",
    )

    private fun props(tags: List<MfTag>, layer: Int): Map<String, Any> {
        val p = LinkedHashMap<String, Any>()
        for (t in tags) {
            if (t.key == "name") {
                val parts = t.value.split('\r')
                p["name"] = parts[0]
                for (part in parts.drop(1)) {
                    val sep = part.indexOf('\b')
                    if (sep > 0) p["name_" + part.substring(0, sep)] = part.substring(sep + 1)
                }
            } else {
                p[t.key] = numberOrString(t.key, t.value)
            }
        }
        if (layer != 5) p["layer"] = (layer - 5).toLong() // mapsforge stores OSM layer + 5
        return p
    }

    private val NUMERIC = setOf("ele", "building:levels", "roof:levels", "height", "min_height", "level")
    private fun numberOrString(key: String, v: String): Any =
        if (key in NUMERIC) v.toLongOrNull() ?: v.toDoubleOrNull() ?: v else v

    private fun overlaps(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, z: Int, x: Int, y: Int): Boolean {
        val north = MapsforgeFile.tileLat(y, z)
        val south = MapsforgeFile.tileLat(y + 1, z)
        val west = MapsforgeFile.tileLon(x, z)
        val east = MapsforgeFile.tileLon(x + 1, z)
        return minLat <= north && maxLat >= south && minLon <= east && maxLon >= west
    }

    private fun tileRing(z: Int, x: Int, y: Int): DoubleArray {
        val n = MapsforgeFile.tileLat(y, z)
        val s = MapsforgeFile.tileLat(y + 1, z)
        val w = MapsforgeFile.tileLon(x, z)
        val e = MapsforgeFile.tileLon(x + 1, z)
        return doubleArrayOf(n, w, n, e, s, e, s, w, n, w)
    }
}
