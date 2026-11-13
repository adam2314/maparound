package app.maparound.map

/**
 * What the built-in world ([WorldData], [BorderLines]) draws in one tile, as plain geometry for a
 * renderer to turn into its own features: mapsforge ways (the 3D view) or vector tiles (MapLibre).
 * Coordinates are microdegrees, (lat, lon) pairs.
 */
class WorldTile(private val world: () -> WorldData?, private val borders: () -> BorderLines?) {

    interface Sink {
        /** Sea under everything else (`natural=sea`): a box. */
        fun sea(box: GeoBox)

        /**
         * Land (`natural=nosea`) or a lake (`natural=water`): the pieces of one polygon's outer
         * ring, and of its holes (which run the other way).
         */
        fun area(lake: Boolean, outer: List<IntArray>, holes: List<IntArray>)

        /** A river (`waterway=river`); [name] may be empty. */
        fun river(name: String, line: IntArray)

        /** A country border (`admin_level=2`); [contested]: disputed, a line of control or undefined. */
        fun border(contested: Boolean, line: IntArray)

        fun label(label: WorldData.Label)
    }

    /**
     * Everything in [tile] at [zoom] (the zoom of 256-pixel tiles at the scale it's shown), except
     * inside [holes]: the installed maps' areas, which draw themselves. Sea and land reach
     * [marginPx] beyond the tile and [seamPx] into the holes, so no hairline shows where pieces
     * meet; lines and labels stop exactly at the holes.
     */
    fun draw(
        tile: GeoBox,
        zoom: Int,
        holes: List<GeoBox>,
        sink: Sink,
        marginPx: Int = 8,
        seamPx: Int = 2,
        /** Where the maps in [holes] really draw (their regions' outlines): lines and names are cut by these. */
        covers: List<Coverage> = holes.map { Coverage(it) },
        /**
         * The maps' own country borders (`admin_level=2` lines) in the tile, when the caller knows
         * them: within the [covers], the world's borders then stay where the maps have none.
         */
        ownBorders: List<IntArray>? = null,
    ) {
        val w = world()
        val margin = (marginPx * 2 * halfPixel(zoom)).toInt()
        val seam = (seamPx * 2 * halfPixel(zoom)).toInt()

        // Sea and land per rectangle of the tile outside the holes. The rectangles overlap each
        // other by the margin and reach a little into the holes, whose maps draw their own over it.
        if (w != null) {
            val parts = if (holes.isEmpty()) listOf(tile) else subtractBoxes(tile, holes)
            for (part in parts) {
                fun touches(strip: GeoBox) = holes.any { it.intersects(strip) }
                val clip = GeoBox(
                    part.minLat - if (touches(GeoBox(part.minLat - 1, part.minLon, part.minLat - 1, part.maxLon))) seam else margin,
                    part.minLon - if (touches(GeoBox(part.minLat, part.minLon - 1, part.maxLat, part.minLon - 1))) seam else margin,
                    part.maxLat + if (touches(GeoBox(part.maxLat + 1, part.minLon, part.maxLat + 1, part.maxLon))) seam else margin,
                    part.maxLon + if (touches(GeoBox(part.minLat, part.maxLon + 1, part.maxLat, part.maxLon + 1))) seam else margin,
                )
                sink.sea(clip)
                w.land(zoom, clip) { outer, inner -> sink.area(false, outer, inner) }
                w.lakes(zoom, clip) { outer, inner -> sink.area(true, outer, inner) }
            }
        }

        // Lines: cut to the tile (with the margin), then the holes cut out exactly.
        val clip = tile.grow(margin, margin)
        fun pieces(coords: IntArray): List<IntArray> {
            var out = listOf(coords)
            for (c in covers) out = out.flatMap { c.cutOut(it) }
            return out
        }
        w?.rivers(zoom, clip) { name, coords -> for (p in pieces(coords)) sink.river(name, p) }
        // The world's borders where a map is: gone where the map has its own (near one of its
        // admin_level=2 lines). A map can have none there (OpenStreetMap puts the country on the
        // border's relation, and maps made from the lines' own tags miss it), and then the world's
        // stay, as long as they're close enough to right (Natural Earth's are coarse).
        val near = ownBorders?.takeIf { zoom <= WORLD_BORDERS_IN_MAPS_TO_ZOOM }
            ?.let { NearLines(it, MapRegions.OUTLINE_MARGIN_M / 111_320.0 * 1e6) }
        fun borderPieces(coords: IntArray): List<IntArray> {
            if (near == null) return pieces(coords)
            var out = listOf(coords)
            for (c in covers) out = out.flatMap { c.cutOut(it) { lat, lon -> !near.near(lat, lon) } }
            return out
        }
        borders()?.let { b ->
            val joiner = LineJoiner { key, coords ->
                val contested = (key as BorderLines.Piece).contested
                for (cut in clipLine(coords, clip)) for (p in borderPieces(cut)) sink.border(contested, p)
            }
            var first: BorderLines.Piece? = null
            b.query(clip) { piece ->
                val f = first
                val key = if (f != null && f.line == piece.line && f.contested == piece.contested) f else piece.also { first = it }
                joiner.add(key, piece.coordsAt(zoom))
            }
            joiner.flush()
        }
        labels(tile, zoom, covers, sink)
    }

    /** Only the names in [tile] (outside the maps' [covers]). */
    fun labels(tile: GeoBox, zoom: Int, covers: List<Coverage>, sink: Sink) {
        world()?.labels(zoom, tile) { l -> if (covers.none { it.contains(l.lat, l.lon) }) sink.label(l) }
    }

    companion object {
        /** The country maps are drawn from this zoom (256-pixel tiles); below it the world is. */
        const val COUNTRY_MAPS_FROM_ZOOM = 8

        /**
         * Up to this zoom (256-pixel tiles; the map's 12) the world's borders stand in for a map's
         * missing ones; closer in they're too coarse to show (a few hundred metres off).
         */
        const val WORLD_BORDERS_IN_MAPS_TO_ZOOM = 13
    }
}

/** The OSM tags of the world's names. */
object WorldLabels {
    fun tags(l: WorldData.Label): List<Pair<String, String>> {
        val tags = ArrayList<Pair<String, String>>(4)
        when (l.kind) {
            WorldData.Kind.COUNTRY -> tags += "place" to "country"
            WorldData.Kind.CAPITAL -> { tags += "place" to "city"; tags += "capital" to "yes" }
            WorldData.Kind.CITY -> tags += "place" to "city"
            WorldData.Kind.TOWN -> tags += "place" to "town"
            WorldData.Kind.OCEAN -> tags += "place" to "ocean"
            WorldData.Kind.SEA -> tags += "place" to "sea"
            WorldData.Kind.LAKE -> tags += "natural" to "water"
        }
        tags += "name" to l.name
        if (l.population > 0 && l.kind != WorldData.Kind.COUNTRY) tags += "population" to l.population.toString()
        return tags
    }
}

/**
 * Where an installed map draws itself: its [box], and finer, when it's one of the download list's
 * regions, the region's outline ([region]). Beyond the outline the map has nothing (a country's
 * neighbours are in its box), so the world's borders, rivers and names stay there.
 */
class Coverage(val box: GeoBox, val region: MapRegions.Region? = null) {
    fun contains(lat: Int, lon: Int): Boolean = box.contains(lat, lon) && (region?.containsMicro(lat.toDouble(), lon.toDouble()) ?: true)

    /** The parts of a line outside it. */
    fun cutOut(coords: IntArray): List<IntArray> = region?.cutOut(coords) ?: cutOutBox(coords, box)

    /** The parts of a line outside it, and in it those [keep] keeps (by microdegrees). */
    fun cutOut(coords: IntArray, keep: (Double, Double) -> Boolean): List<IntArray> =
        region?.cutOut(coords, keep = keep) ?: cutOutBox(coords, box, keep)
}
