package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.math.abs

/**
 * Our reader against mapsforge's own, tile by tile, on a real map: same POIs and ways, same
 * tags, same coordinates. Runs only with MAP_FILE=<a mapsforge .map>.
 */
class CompareWithMapsforgeTest {
    @Test
    fun sameDataAsMapsforge() {
        val path = System.getenv("MAP_FILE")
        assumeTrue(path != null && File(path).isFile)
        MapFile.wayFilterEnabled = false // mapsforge also drops ways just outside small tiles; we don't
        val theirs = MapFile(File(path!!))
        val ours = MapsforgeFile(openTestFile(path))
        val bb = theirs.boundingBox()
        var tiles = 0
        var pois = 0
        var ways = 0
        for (z in listOf(8, 11, 12, 14, 16)) {
            val x0 = MapsforgeFile.lonToTileX(bb.minLongitude, z)
            val x1 = MapsforgeFile.lonToTileX(bb.maxLongitude, z)
            val y0 = MapsforgeFile.latToTileY(bb.maxLatitude, z)
            val y1 = MapsforgeFile.latToTileY(bb.minLatitude, z)
            // Up to ~150 tiles per zoom, spread over the map.
            val stepX = maxOf(1, (x1 - x0 + 1) / 12)
            val stepY = maxOf(1, (y1 - y0 + 1) / 12)
            for (ty in y0..y1 step stepY) for (tx in x0..x1 step stepX) {
                val a = theirs.readMapData(Tile(tx, ty, z.toByte(), 256))
                val b = ours.read(z, tx, ty)
                val where = "z$z/$tx/$ty"
                assertEquals("POIs $where", a.pois.size, b.pois.size)
                assertEquals("ways $where", a.ways.size, b.ways.size)
                a.pois.zip(b.pois).forEach { (p, q) ->
                    assertEquals(p.layer.toInt(), q.layer)
                    assertTrue("POI position $where", abs(p.position.latitude - q.lat) < 1e-9 && abs(p.position.longitude - q.lon) < 1e-9)
                    assertEquals("POI tags $where", p.tags.map { it.key to it.value }, q.tags.map { it.key to firstName(it.key, it.value) })
                }
                a.ways.zip(b.ways).forEach { (w, v) ->
                    assertEquals(w.layer.toInt(), v.layer)
                    assertEquals("way tags $where", w.tags.map { it.key to it.value }, v.tags.map { it.key to firstName(it.key, it.value) })
                    assertEquals("rings $where", w.latLongs.size, v.rings.size)
                    w.latLongs.zip(v.rings).forEach { (ring, r) ->
                        assertEquals(ring.size * 2, r.size)
                        ring.forEachIndexed { i, ll -> assertTrue(abs(ll.latitude - r[2 * i]) < 1e-9 && abs(ll.longitude - r[2 * i + 1]) < 1e-9) }
                    }
                    if (w.labelPosition != null) {
                        assertTrue("label $where", abs(w.labelPosition.latitude - v.labelLat!!) < 1e-9 && abs(w.labelPosition.longitude - v.labelLon!!) < 1e-9)
                    }
                }
                tiles++
                pois += b.pois.size
                ways += b.ways.size
            }
        }
        println("matched mapsforge on $tiles tiles: $pois POIs, $ways ways")
        assertTrue(tiles > 100 && ways > 1000)
    }

    /** mapsforge (without a preferred language) shows the first of a name's languages. */
    private fun firstName(key: String, value: String) = if (key == "name") value.split('\r')[0] else value
}
