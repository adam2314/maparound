package app.maparound.three

import app.maparound.terrain.HgtProcessor
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

// Plain Kotlin: the 3D view's relief where no elevation has been downloaded, from image tiles
// (Terrain Tiles' Terrarium by default, or Mapbox Terrain-RGB; Settings → Sources): each pixel's
// colour is a height. Small (a tile is about 100 kB), so the distance gets its mountains without
// downloading whole 1° cells. Unit-tested.

object ReliefTiles {
    /** The finest relief zoom used: about 38 m a pixel at the equator, enough for what isn't downloaded. */
    const val MAX_ZOOM = 12

    /** Pixels across a relief tile. */
    const val SIZE = 256

    /** The relief zoom for a 3D tile: its own, at most [MAX_ZOOM] (a tile then has more samples than its mesh). */
    fun zoomFor(t: TileId): Int = min(t.z, MAX_ZOOM)

    /**
     * A tile's heights in metres from its pixels ([argb], row by row from the north-west), as
     * Terrarium or Mapbox Terrain-RGB ([mapbox]) encode them. The sea's depths (below 0 where the
     * land doesn't go below sea level: see [HgtProcessor.keepsNegativeHeights]) are 0: the 3D view
     * draws the sea's surface, not its floor.
     */
    fun decode(argb: IntArray, mapbox: Boolean, tile: TileId): ShortArray {
        require(argb.size == SIZE * SIZE) { "A relief tile is $SIZE × $SIZE pixels" }
        val out = ShortArray(argb.size)
        // Whether the tile's area has land below sea level (the Dead Sea, the Caspian…), by 1° cell.
        val belowSea = HashMap<Long, Boolean>()
        for (r in 0 until SIZE) {
            val lat = latAt(tile, r + 0.5)
            for (c in 0 until SIZE) {
                val p = argb[r * SIZE + c]
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                var h = if (mapbox) -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1 else red * 256.0 + green + blue / 256.0 - 32_768.0
                if (h < 0) {
                    val lon = lonAt(tile, c + 0.5)
                    val la = floor(lat).toInt()
                    val lo = floor(lon).toInt()
                    val keep = belowSea.getOrPut(la.toLong() * 1000 + lo) { HgtProcessor.keepsNegativeHeights(la, lo) }
                    if (!keep) h = 0.0
                }
                out[r * SIZE + c] = h.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    /** The relief tile at [z] holding [lat], [lon]. */
    fun tileAt(lat: Double, lon: Double, z: Int): TileId = TileId(z, TileId.xOf(lon, z), TileId.yOf(lat, z))

    /** The height at [lat], [lon] in [tile]'s [heights], between its pixels (they're Web Mercator, like the tiles). */
    fun sample(heights: ShortArray, tile: TileId, lat: Double, lon: Double): Float {
        val n = 1 shl tile.z
        val fx = ((lon + 180) / 360 * n - tile.x) * SIZE - 0.5
        val fy = (mercatorY(lat) * n - tile.y) * SIZE - 0.5
        val c = floor(fx).toInt().coerceIn(0, SIZE - 2)
        val r = floor(fy).toInt().coerceIn(0, SIZE - 2)
        val dx = (fx - c).toFloat().coerceIn(0f, 1f)
        val dy = (fy - r).toFloat().coerceIn(0f, 1f)
        val a = heights[r * SIZE + c].toFloat()
        val b = heights[r * SIZE + c + 1].toFloat()
        val d = heights[(r + 1) * SIZE + c].toFloat()
        val e = heights[(r + 1) * SIZE + c + 1].toFloat()
        val top = a + (b - a) * dx
        return top + (d + (e - d) * dx - top) * dy
    }

    /** The address of [t] from [template] (`{z}`, `{x}`, `{y}`). */
    fun url(template: String, t: TileId): String =
        template.replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())

    /** 0 at the north edge of the Web Mercator square, 1 at the south. */
    private fun mercatorY(lat: Double): Double {
        val r = app.maparound.shared.util.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / kotlin.math.PI) / 2
    }

    private fun latAt(t: TileId, row: Double): Double {
        val n = 1 shl t.z
        val y = t.y + row / SIZE
        return app.maparound.shared.util.toDegrees(kotlin.math.atan(kotlin.math.sinh(kotlin.math.PI * (1 - 2 * y / n))))
    }

    private fun lonAt(t: TileId, col: Double): Double = (t.x + col / SIZE) / (1 shl t.z) * 360.0 - 180.0
}
