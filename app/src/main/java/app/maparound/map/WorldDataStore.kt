package app.maparound.map

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.PointOfInterest
import org.mapsforge.map.datastore.Way

/**
 * Virtual mapsforge data source for the built-in world ([WorldData], [BorderLines]) as OSM-style
 * map data, so every style draws it: sea (`natural=sea`) and land (`natural=nosea`) like the
 * mapsforge maps, lakes (`natural=water`), rivers (`waterway=river`), country borders
 * (`boundary=administrative` + `admin_level=2`, `disputed=yes` on contested lines), and names
 * (`place=country|city|town|ocean|sea`, `natural=water` points for lakes).
 *
 * Up to [overviewMaxZoom] it covers the whole world (the country maps start above it). Above it,
 * only what's outside the installed country maps' areas ([covered]): those have their own,
 * exact data.
 */
class WorldDataStore(
    private val world: () -> WorldData?,
    private val borders: () -> BorderLines?,
    covered: List<BoundingBox>,
    private val overviewMaxZoom: Int,
) : MapDataStore() {

    private val covered = covered.map { it.toGeoBox() }

    override fun boundingBox(): BoundingBox = WORLD
    override fun close() = Unit
    override fun getDataTimestamp(tile: Tile): Long = 0L
    override fun startPosition(): LatLong? = null
    override fun startZoomLevel(): Byte? = null
    override fun supportsTile(tile: Tile): Boolean = parts(tile).isNotEmpty()
    override fun supportsFullTile(tile: Tile): Boolean = tile.zoomLevel <= overviewMaxZoom
    override fun supportsArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean =
        zoomLevel <= overviewMaxZoom || subtractBoxes(boundingBox.toGeoBox(), covered).isNotEmpty()
    override fun supportsFullArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean = zoomLevel <= overviewMaxZoom

    override fun readPoiData(tile: Tile): MapReadResult {
        val result = MapReadResult()
        labels(tile, result)
        return result
    }

    override fun readMapData(tile: Tile): MapReadResult {
        val result = MapReadResult()
        val zoom = tile.zoomLevel.toInt()
        val tileBox = tile.boundingBox.toGeoBox()
        val holes = holes(tile)
        // A few pixels around the tile, so what's cut at its edge is cut out of sight.
        val margin = (MARGIN_PX * 2 * halfPixel(zoom)).toInt()
        val seam = (SEAM_PX * 2 * halfPixel(zoom)).toInt()
        val w = world()

        // Sea and land per rectangle of the tile outside the country maps. The rectangles overlap
        // each other by the margin and reach a little into the maps, so no hairline shows where
        // they meet; the maps draw their own land and sea over that.
        if (w != null) {
            val parts = if (holes.isEmpty()) listOf(tileBox) else subtractBoxes(tileBox, holes)
            for (part in parts) {
                fun touches(strip: GeoBox) = holes.any { it.intersects(strip) }
                val clip = GeoBox(
                    part.minLat - if (touches(GeoBox(part.minLat - 1, part.minLon, part.minLat - 1, part.maxLon))) seam else margin,
                    part.minLon - if (touches(GeoBox(part.minLat, part.minLon - 1, part.maxLat, part.minLon - 1))) seam else margin,
                    part.maxLat + if (touches(GeoBox(part.maxLat + 1, part.minLon, part.maxLat + 1, part.maxLon))) seam else margin,
                    part.maxLon + if (touches(GeoBox(part.minLat, part.maxLon + 1, part.maxLat, part.maxLon + 1))) seam else margin,
                )
                result.ways.add(area(TAGS_SEA, listOf(rect(clip))))
                w.land(zoom, clip) { rings -> result.ways.add(area(TAGS_LAND, rings)) }
                w.lakes(zoom, clip) { rings -> result.ways.add(area(TAGS_LAKE, rings)) }
            }
        }

        // Lines: cut to the tile (with the margin), then the country maps cut out exactly.
        val clip = tileBox.grow(margin, margin)
        fun addLine(tags: List<Tag>, coords: IntArray) {
            var pieces = listOf(coords)
            for (h in holes) pieces = pieces.flatMap { cutOutBox(it, h) }
            for (p in pieces) result.ways.add(line(tags, p))
        }
        w?.rivers(zoom, clip) { name, coords -> addLine(if (name.isEmpty()) TAGS_RIVER else TAGS_RIVER + Tag("name", name), coords) }
        borders()?.let { b ->
            val joiner = LineJoiner { key, coords ->
                val tags = if ((key as BorderLines.Piece).contested) TAGS_CONTESTED else TAGS_BORDER
                for (cut in clipLine(coords, clip)) addLine(tags, cut)
            }
            var first: BorderLines.Piece? = null
            b.query(clip) { piece ->
                val f = first
                val key = if (f != null && f.line == piece.line && f.contested == piece.contested) f else piece.also { first = it }
                joiner.add(key, piece.coordsAt(zoom))
            }
            joiner.flush()
        }
        labels(tile, result)
        return result
    }

    private fun labels(tile: Tile, result: MapReadResult) {
        val w = world() ?: return
        val holes = holes(tile)
        w.labels(tile.zoomLevel.toInt(), tile.boundingBox.toGeoBox()) { l ->
            if (holes.none { it.contains(l.lat, l.lon) }) result.pois.add(poi(l))
        }
    }

    /** The country maps' areas this tile must leave to them (none at overview zooms). */
    private fun holes(tile: Tile): List<GeoBox> {
        if (tile.zoomLevel <= overviewMaxZoom || covered.isEmpty()) return emptyList()
        val box = tile.boundingBox.toGeoBox()
        return covered.filter { it.intersects(box) }
    }

    /** The parts of the tile this store draws: all of it, or what the country maps don't cover. */
    private fun parts(tile: Tile): List<GeoBox> {
        val box = tile.boundingBox.toGeoBox()
        val holes = holes(tile)
        return if (holes.isEmpty()) listOf(box) else subtractBoxes(box, holes)
    }

    private fun area(tags: List<Tag>, rings: List<IntArray>): Way =
        Way(LAYER, tags, Array(rings.size) { closedRing(rings[it]) }, null)

    private fun line(tags: List<Tag>, coords: IntArray): Way =
        Way(LAYER, tags, arrayOf(Array(coords.size / 2) { latLong(coords[2 * it], coords[2 * it + 1]) }), null)

    private fun poi(l: WorldData.Label): PointOfInterest {
        val tags = ArrayList<Tag>(4)
        when (l.kind) {
            WorldData.Kind.COUNTRY -> tags += Tag("place", "country")
            WorldData.Kind.CAPITAL -> { tags += Tag("place", "city"); tags += Tag("capital", "yes") }
            WorldData.Kind.CITY -> tags += Tag("place", "city")
            WorldData.Kind.TOWN -> tags += Tag("place", "town")
            WorldData.Kind.OCEAN -> tags += Tag("place", "ocean")
            WorldData.Kind.SEA -> tags += Tag("place", "sea")
            WorldData.Kind.LAKE -> tags += Tag("natural", "water")
        }
        tags += Tag("name", l.name)
        if (l.population > 0 && l.kind != WorldData.Kind.COUNTRY) tags += Tag("population", l.population.toString())
        return PointOfInterest(LAYER, tags, LatLong(l.lat / 1e6, l.lon / 1e6))
    }

    companion object {
        private const val LAYER: Byte = 5
        private const val MARGIN_PX = 8

        /** How far sea and land reach into a country map's area, so no hairline shows at its edge. */
        private const val SEAM_PX = 2
        private val WORLD = BoundingBox(-85.0511, -180.0, 85.0511, 180.0)
        private val TAGS_SEA = listOf(Tag("natural", "sea"))
        private val TAGS_LAND = listOf(Tag("natural", "nosea"))
        private val TAGS_LAKE = listOf(Tag("natural", "water"))
        private val TAGS_RIVER = listOf(Tag("waterway", "river"))
        private val TAGS_BORDER = listOf(Tag("boundary", "administrative"), Tag("admin_level", "2"))
        private val TAGS_CONTESTED = TAGS_BORDER + Tag("disputed", "yes")

        private fun BoundingBox.toGeoBox() = GeoBox(
            (minLatitude * 1e6).toInt(), (minLongitude * 1e6).toInt(),
            (maxLatitude * 1e6).toInt(), (maxLongitude * 1e6).toInt(),
        )

        private fun rect(b: GeoBox) = intArrayOf(b.minLat, b.minLon, b.minLat, b.maxLon, b.maxLat, b.maxLon, b.maxLat, b.minLon)

        /** mapsforge draws a way as an area when its first ring is closed. */
        private fun closedRing(c: IntArray): Array<LatLong> {
            val n = c.size / 2
            return Array(n + 1) { i -> val k = if (i == n) 0 else i; latLong(c[2 * k], c[2 * k + 1]) }
        }

        /** mapsforge refuses points beyond ±90°/±180°: the margins at the world's edges are cut back. */
        private fun latLong(lat: Int, lon: Int) =
            LatLong(lat.coerceIn(-90_000_000, 90_000_000) / 1e6, lon.coerceIn(-180_000_000, 180_000_000) / 1e6)
    }
}
