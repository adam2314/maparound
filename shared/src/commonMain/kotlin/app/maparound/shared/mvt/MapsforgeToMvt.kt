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
        return t.encode()
    }

    fun add(t: MvtTile, data: MfData) {
        val poi = t.layer("poi")
        for (p in data.pois) poi.point(p.lat, p.lon, props(p.tags, p.layer))
        for (w in data.ways) {
            val props = props(w.tags, w.layer)
            if (isArea(w)) {
                t.layer("area").polygon(w.rings, props)
                // Name labels of areas go in the point layer, at mapsforge's label position.
                if (w.labelLat != null && w.labelLon != null && props.containsKey("name")) {
                    poi.point(w.labelLat, w.labelLon, props + ("label_of" to "area"))
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
        val closed = n >= 4 && outer[0] == outer[2 * n - 2] && outer[1] == outer[2 * n - 1]
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
