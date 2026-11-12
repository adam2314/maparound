package app.maparound.map

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.Way

/**
 * Virtual mapsforge data source for the world's country borders ([BorderLines]), as OSM-style
 * ways `boundary=administrative` + `admin_level=2`, plus `disputed=yes` on contested lines, so
 * every style draws them like the maps' own borders.
 *
 * Up to [overviewMaxZoom] they're drawn everywhere (the world overview map has no borders). Above
 * it only away from the installed country maps ([covered]), which have exact borders of their own.
 */
class BorderDataStore(
    private val lines: () -> BorderLines?,
    private val covered: List<BoundingBox>,
    private val overviewMaxZoom: Int,
    /** Reported as this store's area: the maps' own, so it doesn't change what counts as outside them. */
    private val bounds: BoundingBox,
) : MapDataStore() {

    override fun boundingBox(): BoundingBox = bounds
    override fun close() = Unit
    override fun getDataTimestamp(tile: Tile): Long = 0L
    override fun readPoiData(tile: Tile): MapReadResult = MapReadResult()
    override fun startPosition(): LatLong? = null
    override fun startZoomLevel(): Byte? = null
    override fun supportsTile(tile: Tile): Boolean = supportsArea(tile.boundingBox, tile.zoomLevel)
    override fun supportsFullTile(tile: Tile): Boolean = false
    override fun supportsFullArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean = false

    override fun supportsArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean {
        var any = false
        visit(boundingBox, zoomLevel.toInt()) { any = true }
        return any
    }

    override fun readMapData(tile: Tile): MapReadResult {
        val result = MapReadResult()
        val zoom = tile.zoomLevel.toInt()
        // Consecutive pieces of a line become one way, so dashes run on across them.
        var points: ArrayList<LatLong>? = null
        var last: BorderLines.Piece? = null
        fun flush() {
            val p = points ?: return
            val tags = if (last!!.contested) TAGS_CONTESTED else TAGS
            result.ways.add(Way(LAYER, tags, arrayOf(p.toTypedArray()), null))
            points = null
        }
        visit(tile.boundingBox, zoom) { piece ->
            val c = piece.coordsAt(zoom)
            val prev = last
            val joins = points != null && prev != null && prev.line == piece.line && prev.contested == piece.contested &&
                prev.coords[prev.coords.size - 2] == piece.coords[0] && prev.coords[prev.coords.size - 1] == piece.coords[1]
            if (!joins) {
                flush()
                points = ArrayList(c.size / 2)
            }
            val list = points!!
            for (k in (if (joins) 2 else 0) until c.size step 2) list += LatLong(c[k] / 1e6, c[k + 1] / 1e6)
            last = piece
        }
        flush()
        return result
    }

    private inline fun visit(bb: BoundingBox, zoom: Int, crossinline action: (BorderLines.Piece) -> Unit) {
        val data = lines() ?: return
        // A few pixels around the tile, so lines along its edge are drawn whole.
        val margin = (MARGIN_PX * 360.0 / (256L shl zoom.coerceIn(0, 30)) * 1e6).toInt()
        val detail = zoom > overviewMaxZoom
        data.query(
            (bb.minLatitude * 1e6).toInt() - margin, (bb.minLongitude * 1e6).toInt() - margin,
            (bb.maxLatitude * 1e6).toInt() + margin, (bb.maxLongitude * 1e6).toInt() + margin,
        ) { piece ->
            if (detail && covered.any { piece.meets(it) }) return@query
            action(piece)
        }
    }

    private fun BorderLines.Piece.meets(bb: BoundingBox) = intersects(
        (bb.minLatitude * 1e6).toInt(), (bb.minLongitude * 1e6).toInt(),
        (bb.maxLatitude * 1e6).toInt(), (bb.maxLongitude * 1e6).toInt(),
    )

    companion object {
        /**
         * The country maps (download.mapsforge.org) carry their borders from this zoom; below it,
         * with no world map installed, these lines are drawn everywhere.
         */
        const val MAP_BORDERS_FROM_ZOOM = 4

        private const val LAYER: Byte = 5
        private const val MARGIN_PX = 4
        private val TAGS = listOf(Tag("boundary", "administrative"), Tag("admin_level", "2"))
        private val TAGS_CONTESTED = TAGS + Tag("disputed", "yes")
    }
}
