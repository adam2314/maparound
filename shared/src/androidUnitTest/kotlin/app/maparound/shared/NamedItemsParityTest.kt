package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mapsforge.MfSelector
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.Way
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * Offline search reads named items over an area. Ours ([MapsforgeFile.readArea]) against
 * mapsforge's readNamedItems, which the Android app used before. mapsforge returns whole
 * storage blocks (about 2 km beyond the area); ours stops at the area, so theirs is cut the
 * same way before comparing. Runs with MAP_FILE.
 */
class NamedItemsParityTest {
    @Test
    fun sameNamesAsMapsforge() {
        val path = System.getenv("MAP_FILE")
        assumeTrue(path != null && File(path).isFile)
        val theirs = MapFile(File(path!!))
        val ours = MapsforgeFile(openTestFile(path))
        val z: Byte = 17
        // Areas around Vaduz, Malbun and the Rhine (Liechtenstein), small to whole-map.
        val areas = listOf(
            doubleArrayOf(47.12, 9.50, 47.16, 9.54),
            doubleArrayOf(47.08, 9.58, 47.12, 9.64),
            doubleArrayOf(46.9, 9.3, 47.4, 9.8),
        )
        for (a in areas) {
            val (south, west, north, east) = a.toList()
            val ul = Tile(MercatorProjection.longitudeToTileX(west, z), MercatorProjection.latitudeToTileY(north, z), z, 256)
            val lr = Tile(MercatorProjection.longitudeToTileX(east, z), MercatorProjection.latitudeToTileY(south, z), z, 256)
            val t = theirs.readNamedItems(ul, lr)
            val o = ours.readArea(17, south, west, north, east, MfSelector.NAMED)

            fun inside(lat: Double, lon: Double) = lat in south..north && lon in west..east
            fun touches(w: Way) = w.latLongs.any { ring ->
                ring.minOf { it.latitude } <= north && ring.maxOf { it.latitude } >= south &&
                    ring.minOf { it.longitude } <= east && ring.maxOf { it.longitude } >= west
            }
            val theirPois = t.pois.filter { inside(it.position.latitude, it.position.longitude) }
                .mapNotNull { p -> p.tags.firstOrNull { it.key == "name" }?.value }.sorted()
            // mapsforge keeps only the local name (no language set); ours keeps every language.
            fun local(name: String?) = name?.substringBefore('\r')
            val ourPois = o.pois.mapNotNull { p -> local(p.tags.firstOrNull { it.key == "name" }?.value) }.sorted()
            val theirWays = t.ways.filter(::touches).mapNotNull { w -> w.tags.firstOrNull { it.key == "name" }?.value }.sorted()
            val ourWays = o.ways.mapNotNull { w -> local(w.tags.firstOrNull { it.key == "name" }?.value) }.sorted()
            println("area ${a.toList()}: POIs ${t.pois.size} in blocks, ${theirPois.size} inside / ours ${ourPois.size}; " +
                "ways ${t.ways.size} in blocks, ${theirWays.size} touching / ours ${ourWays.size}")
            assertEquals("POI names in ${a.toList()}", theirPois, ourPois)
            assertEquals("way names in ${a.toList()}", theirWays, ourWays)
        }
    }
}
