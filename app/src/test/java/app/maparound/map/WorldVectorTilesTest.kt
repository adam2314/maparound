package app.maparound.map

import app.maparound.shared.MapTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The built-in world in MapLibre's vector tiles (MapTiles → WorldToMvt), from the bundled data. */
class WorldVectorTilesTest {
    private val world = WORLD

    /** Per layer, the tags of each feature (string values only). */
    private fun decode(tile: ByteArray): Map<String, List<Map<String, String>>> {
        val out = LinkedHashMap<String, List<Map<String, String>>>()
        for ((field, layer) in fields(tile)) {
            if (field != 3) continue
            val parts = fields(layer as ByteArray)
            val name = String(parts.first { it.first == 1 }.second as ByteArray)
            val keys = parts.filter { it.first == 3 }.map { String(it.second as ByteArray) }
            val values = parts.filter { it.first == 4 }.map { v ->
                fields(v.second as ByteArray).firstOrNull { it.first == 1 }?.let { String(it.second as ByteArray) } ?: "?"
            }
            out[name] = parts.filter { it.first == 2 }.map { f ->
                val tags = fields(f.second as ByteArray).firstOrNull { it.first == 2 }?.let { packed(it.second as ByteArray) } ?: emptyList()
                tags.chunked(2).associate { (k, v) -> keys[k.toInt()] to values[v.toInt()] }
            }
        }
        return out
    }

    private fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var p = 0
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val c = b[p++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                if (c < 0x80) return r
                s += 7
            }
        }
        while (p < b.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> out += (key shr 3) to varint()
                2 -> { val n = varint().toInt(); out += (key shr 3) to b.copyOfRange(p, p + n); p += n }
                1 -> { p += 8 }
                5 -> { p += 4 }
            }
        }
        return out
    }

    private fun packed(b: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var p = 0
        while (p < b.size) {
            var r = 0L
            var s = 0
            while (true) {
                val c = b[p++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                if (c < 0x80) break
                s += 7
            }
            out += r
        }
        return out
    }

    @Test
    fun worldTilesHaveTheCountryMapsLayersAndTags() {
        // MapLibre zoom 3 over Europe (shown like mapsforge's 4): x 4, y 2.
        val layers = decode(MapTiles(emptyList(), world).tile(3, 4, 2))
        assertEquals(1, layers["sea"]?.size)
        assertEquals("sea", layers["sea"]!![0]["natural"])
        assertTrue(layers["area"]!!.any { it["natural"] == "nosea" })
        assertTrue(layers["area"]!!.any { it["natural"] == "water" })
        assertTrue(layers["line"]!!.any { it["admin_level"] == "2" && it["boundary"] == "administrative" })
        assertTrue(layers["line"]!!.any { it["waterway"] == "river" })
        val names = layers["poi"]!!.mapNotNull { it["name"] }
        assertTrue(names.toString(), "Germany" in names && "Paris" in names)
        assertTrue(layers["poi"]!!.any { it["place"] == "city" && it["capital"] == "yes" })
    }

    @Test
    fun zoomedInWhereNoMapIsInstalledItIsStillTheWorld() {
        // MapLibre 9 (mapsforge 10) over the Pyrenees, with no country map: land, borders, names.
        val layers = decode(MapTiles(emptyList(), world).tile(9, 258, 189))
        assertTrue(layers["area"]!!.any { it["natural"] == "nosea" })
        assertTrue(layers["line"]!!.any { it["admin_level"] == "2" })
    }

    private companion object {
        val WORLD_DATA = WorldData.decode(File("src/main/assets/world.bin").readBytes())
        val BORDERS = BorderLines.decode(File("src/main/assets/borders.bin").readBytes())
        val WORLD = WorldTile({ WORLD_DATA }, { BORDERS })
    }
}
