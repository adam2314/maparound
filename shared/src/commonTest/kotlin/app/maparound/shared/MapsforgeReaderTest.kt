package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Reads a real mapsforge map on every platform (JVM and the iOS simulator) and makes vector tiles
 * from it. Runs only with MAP_FILE=<europe__liechtenstein.map> (download.mapsforge.org).
 */
class MapsforgeReaderTest {
    @Test
    fun readsARealMapAndMakesVectorTiles() {
        val path = testEnv("MAP_FILE") ?: return
        val handle = openTestFile(path)
        val map = MapsforgeFile(handle)
        val h = map.header
        assertTrue(h.version in 3..5)
        assertEquals("Mercator", h.projection)
        assertTrue(9.47 in h.minLon..h.maxLon && 47.14 in h.minLat..h.maxLat, "Liechtenstein in the bounds")
        assertTrue(h.poiTags.isNotEmpty() && h.wayTags.isNotEmpty())

        val mark = TimeSource.Monotonic.markNow()
        // Vaduz at zoom 14 (the usual base zoom of the detailed interval).
        val z = 14
        val x = MapsforgeFile.lonToTileX(9.5209, z)
        val y = MapsforgeFile.latToTileY(47.1410, z)
        var pois = 0
        var ways = 0
        var named = 0
        for (dy in -1..1) for (dx in -1..1) {
            val d = map.read(z, x + dx, y + dy)
            pois += d.pois.size
            ways += d.ways.size
            named += d.pois.count { p -> p.tags.any { it.key == "name" } }
        }
        val readMs = mark.elapsedNow().inWholeMilliseconds
        assertTrue(pois > 50 && ways > 500, "pois=$pois ways=$ways")
        assertTrue(named > 10)

        val m2 = TimeSource.Monotonic.markNow()
        var bytes = 0
        var tiles = 0
        for (zz in listOf(10, 12, 13, 14, 15)) {
            val tx = MapsforgeFile.lonToTileX(9.5209, zz)
            val ty = MapsforgeFile.latToTileY(47.1410, zz)
            val mvt = MapsforgeToMvt.tile(listOf(map), zz, tx, ty)
            assertTrue(mvt.size > 100, "tile $zz has data")
            bytes += mvt.size
            tiles++
        }
        val mvtMs = m2.elapsedNow().inWholeMilliseconds
        println("mapsforge reader: 9 blocks ($pois POIs, $ways ways) in $readMs ms; $tiles vector tiles ($bytes bytes) in $mvtMs ms")
        handle.close()
    }
}
