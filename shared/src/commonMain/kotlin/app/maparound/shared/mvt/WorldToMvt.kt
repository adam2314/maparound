package app.maparound.shared.mvt

import app.maparound.map.GeoBox
import app.maparound.map.WorldData
import app.maparound.map.WorldLabels
import app.maparound.map.WorldTile

/**
 * Writes the built-in world ([WorldTile]) into a vector tile, in the layers and with the tags
 * [MapsforgeToMvt] uses for the country maps, so the theme's layers draw both alike: sea in "sea",
 * land and lakes in "area", rivers and borders in "line", names in "poi".
 */
class WorldToMvt(private val t: MvtTile) : WorldTile.Sink {
    override fun sea(box: GeoBox) {
        val ring = doubleArrayOf(
            box.maxLat / 1e6, box.minLon / 1e6, box.maxLat / 1e6, box.maxLon / 1e6,
            box.minLat / 1e6, box.maxLon / 1e6, box.minLat / 1e6, box.minLon / 1e6,
        )
        t.layer("sea").polygon(listOf(ring), SEA)
    }

    override fun area(lake: Boolean, outer: List<IntArray>, holes: List<IntArray>) {
        val props = if (lake) LAKE else LAND
        for (o in outer) {
            // Each piece of the outer ring with the holes inside it.
            val mine = if (holes.isEmpty()) emptyList() else holes.filter { h -> contains(o, h[0], h[1]) }
            t.layer("area").polygon(listOf(deg(o)) + mine.map(::deg), props)
        }
    }

    override fun river(name: String, line: IntArray) {
        t.layer("line").line(deg(line), if (name.isEmpty()) RIVER else RIVER + ("name" to name))
    }

    override fun border(contested: Boolean, line: IntArray) {
        t.layer("line").line(deg(line), if (contested) CONTESTED else BORDER)
    }

    override fun label(label: WorldData.Label) {
        t.layer("poi").point(label.lat / 1e6, label.lon / 1e6, WorldLabels.tags(label).toMap())
    }

    private companion object {
        val SEA = mapOf<String, Any>("natural" to "sea")
        val LAND = mapOf<String, Any>("natural" to "nosea")
        val LAKE = mapOf<String, Any>("natural" to "water")
        val RIVER = mapOf<String, Any>("waterway" to "river")
        val BORDER = mapOf<String, Any>("boundary" to "administrative", "admin_level" to "2")
        val CONTESTED = BORDER + ("disputed" to "yes")

        fun deg(c: IntArray) = DoubleArray(c.size) { c[it] / 1e6 }

        /** Even–odd point in polygon (microdegrees). */
        fun contains(ring: IntArray, lat: Int, lon: Int): Boolean {
            val n = ring.size / 2
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val yi = ring[2 * i].toDouble(); val xi = ring[2 * i + 1].toDouble()
                val yj = ring[2 * j].toDouble(); val xj = ring[2 * j + 1].toDouble()
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
