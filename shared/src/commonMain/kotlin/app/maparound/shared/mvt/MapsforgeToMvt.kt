package app.maparound.shared.mvt

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mapsforge.MfData
import app.maparound.shared.mapsforge.MfTag
import app.maparound.shared.mapsforge.MfWay

/**
 * Turns mapsforge map data into vector tiles for MapLibre. Layers: "poi" (points), "line",
 * "area" (polygons) and "sea" (whole water blocks). Every OSM tag becomes a property; names are
 * split into `name` (the local one) and `name_<lang>` (other languages mapsforge stores).
 */
object MapsforgeToMvt {
    /**
     * The vector tile z/x/y from several maps (a world overview and country maps). MapLibre's
     * tiles are 512 px, so data is read one zoom deeper than the tile, like a 256 px map would.
     */
    fun tile(maps: List<MapsforgeFile>, z: Int, x: Int, y: Int, maxReadZoom: Int = 17): ByteArray {
        val t = MvtTile(z, x, y)
        fill(t, maps, z, x, y, maxReadZoom)
        return t.encode()
    }

    /** Adds the maps' data for the tile to [t] (z/x/y as in [tile]). */
    fun fill(t: MvtTile, maps: List<MapsforgeFile>, z: Int, x: Int, y: Int, maxReadZoom: Int = 17) {
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
                add(t, data)
                if (!data.isWater) water = false
            }
        }
        if (anyCovered && water) {
            t.layer("sea").polygon(listOf(tileRing(z, x, y)), mapOf("natural" to "sea"))
        }
    }

    fun add(t: MvtTile, data: MfData) {
        val poi = t.layer("poi")
        for (p in data.pois) poi.point(p.lat, p.lon, props(p.tags, p.layer))
        for (w in data.ways) {
            val props = props(w.tags, w.layer)
            if (isArea(w)) {
                t.layer("area").polygon(w.rings, props)
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
