package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mapsforge.MfData
import app.maparound.shared.mapsforge.MfTag
import app.maparound.shared.mapsforge.MfWay
import app.maparound.shared.mvt.MapsforgeToMvt
import app.maparound.shared.mvt.MvtTile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailLayerTest {
    @Test
    fun trailLinesBuiltIntoAMapGetTheirOwnLayer() {
        val z = 12
        val x = 2156
        val y = 1438
        val west = MapsforgeFile.tileLon(x, z) + 0.01
        val east = west + 0.02
        val lat = MapsforgeFile.tileLat(y, z) - 0.01
        val line = doubleArrayOf(lat, west, lat, east)
        // As my-pipeline's maps.py writes them: one copy of a path per waymark colour.
        val trail = MfWay(
            5,
            listOf(MfTag("trail", "hiking"), MfTag("trail_colour", "red"), MfTag("trail_offset", "0"), MfTag("trail_n", "1"), MfTag("name", "Fürstin-Gina-Weg")),
            listOf(line), null, null,
        )
        val path = MfWay(5, listOf(MfTag("highway", "path"), MfTag("sac_scale", "T2")), listOf(line), null, null)

        fun tile(vararg ways: MfWay) = MvtTile(z, x, y).also { MapsforgeToMvt.add(it, MfData(emptyList(), ways.toList(), false)) }.encode().decodeToString()
        val withTrail = tile(trail, path)
        assertTrue(MapsforgeToMvt.TRAILS in withTrail && "Fürstin-Gina-Weg" in withTrail && "sac_scale" in withTrail)
        // Without trail lines there's no trails layer.
        assertFalse(MapsforgeToMvt.TRAILS in tile(path))
    }
}
