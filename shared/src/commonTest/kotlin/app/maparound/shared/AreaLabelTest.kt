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

class AreaLabelTest {
    @Test
    fun anAreaIsLabelledOnlyInTheTileWithItsLabelPoint() {
        val z = 12
        val x = 2156
        val y = 1438
        // A named forest across the border between tiles x and x+1, labelled in tile x.
        val west = MapsforgeFile.tileLon(x, z) + 0.01
        val east = MapsforgeFile.tileLon(x + 1, z) + 0.02
        val north = MapsforgeFile.tileLat(y, z) - 0.01
        val south = MapsforgeFile.tileLat(y + 1, z) + 0.01
        val ring = doubleArrayOf(north, west, north, east, south, east, south, west, north, west)
        val forest = MfWay(5, listOf(MfTag("landuse", "forest"), MfTag("name", "Testwald")), listOf(ring), (north + south) / 2, west + 0.02)
        val data = MfData(emptyList(), listOf(forest), false)

        fun tile(tx: Int) = MvtTile(z, tx, y).also { MapsforgeToMvt.add(it, data) }.encode().decodeToString()
        val here = tile(x)
        val next = tile(x + 1)
        // Both tiles draw the forest, only the first has its label point.
        assertTrue("Testwald" in here && "Testwald" in next)
        assertTrue("label_of" in here)
        assertFalse("label_of" in next)
    }
}
