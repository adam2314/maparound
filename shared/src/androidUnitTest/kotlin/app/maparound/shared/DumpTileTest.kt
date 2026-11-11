package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Writes one vector tile to MVT_OUT for checking with other tools. */
class DumpTileTest {
    @Test
    fun dump() {
        val map = System.getenv("MAP_FILE")
        val out = System.getenv("MVT_OUT")
        assumeTrue(map != null && out != null)
        val m = MapsforgeFile(openTestFile(map!!))
        File(out!!).writeBytes(MapsforgeToMvt.tile(listOf(m), 13, 4312, 2876))
    }
}
