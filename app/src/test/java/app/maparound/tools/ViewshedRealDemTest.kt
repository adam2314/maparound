package app.maparound.tools

import app.maparound.routing.GeoPoint
import app.maparound.terrain.DemIndex
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Viewshed on real terrain, rendered to PNGs for a visual check. Runs only with
 * VIEWSHED_DEM=<folder with N47E009.hgt> (and optional VIEWSHED_OUT=<folder>).
 */
class ViewshedRealDemTest {
    @Test
    fun saentis() {
        val dir = System.getenv("VIEWSHED_DEM")?.let(::File)
        assumeTrue(dir != null && File(dir, "N47E009.hgt").exists())
        val dem = DemIndex(dir!!.path)
        val elevation = Elevation { lat, lon -> dem.elevation(lat, lon) }
        val out = File(System.getenv("VIEWSHED_OUT") ?: "build").apply { mkdirs() }

        // From the Säntis summit (2502 m) you see most of the region; from a valley floor very little.
        // Snap to the highest terrain sample nearby (map coordinates and 1" cells don't line up exactly).
        fun peak(near: GeoPoint): GeoPoint {
            var best = near
            var bestZ = Float.NEGATIVE_INFINITY
            for (i in -8..8) for (j in -8..8) {
                val p = GeoPoint(near.lat + i / 3600.0, near.lon + j / 3600.0)
                val z = elevation.at(p.lat, p.lon)
                if (z > bestZ) { bestZ = z; best = p }
            }
            return best
        }
        val summit = peak(GeoPoint(47.24935, 9.34345))
        val t0 = System.nanoTime()
        val top = Viewshed.compute(elevation, summit, 15_000.0, 1.7, 0.0, size = 601)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("summit: ${elevation.at(summit.lat, summit.lon)} m, visible ${(top.visibleFraction * 100).toInt()}% in $ms ms")
        render(top, elevation, File(out, "viewshed-saentis.png"))

        // "Move A up" (what the app offers): climb to the highest ground nearby, then look again.
        var climbed = summit
        while (true) {
            val (p, z) = highestNear(elevation, climbed) ?: break
            if (z <= elevation.at(climbed.lat, climbed.lon) + 0.5) break
            climbed = p
        }
        val fromTop = Viewshed.compute(elevation, climbed, 15_000.0, 1.7, 0.0, size = 601)!!
        println("climbed to ${elevation.at(climbed.lat, climbed.lon)} m: visible ${(fromTop.visibleFraction * 100).toInt()}%")
        render(fromTop, elevation, File(out, "viewshed-saentis-top.png"))
        val hinterrugg = GeoPoint(47.1528, 9.3150)
        println("top->Hinterrugg visible=${LineOfSight.compute(elevation, climbed, hinterrugg, 1.7, 1.7, 25.0)!!.targetVisible}")
        assertTrue("the true top sees more", fromTop.visibleFraction >= top.visibleFraction)

        val valley = GeoPoint(47.2930, 9.3990) // Wasserauen, at the foot of the massif
        val low = Viewshed.compute(elevation, valley, 5_000.0, 1.7, 0.0, size = 401)!!
        println("valley: ${elevation.at(valley.lat, valley.lon)} m, visible ${(low.visibleFraction * 100).toInt()}%")
        render(low, elevation, File(out, "viewshed-wasserauen.png"))
        assertTrue(top.visibleFraction > low.visibleFraction)

        // The fast radial sweep must agree with a brute-force sight line to each cell.
        val fine = Viewshed.compute(elevation, summit, 5_000.0, 1.7, 0.0, size = 401)!!
        for ((name, vs) in listOf("summit" to top, "valley" to low, "summit-5km-fine" to fine)) {
            var visHid = 0
            var hidVis = 0
            val wrongDist = ArrayList<Int>()
            val rnd = java.util.Random(42)
            var agree = 0
            var total = 0
            while (total < 500) {
                val r = rnd.nextInt(vs.size)
                val c = rnd.nextInt(vs.size)
                val cell = vs.cells[r * vs.size + c].toInt()
                if (cell < 0) continue
                val target = GeoPoint(
                    Geodesy.latOf(vs.maxY - (r + 0.5) * (vs.maxY - vs.minY) / vs.size),
                    Geodesy.lonOf(vs.minX + (c + 0.5) * (vs.maxX - vs.minX) / vs.size),
                )
                val los = LineOfSight.compute(elevation, vs.center, target, 1.7, 0.0, 25.0) ?: continue
                total++
                if (los.targetVisible == (cell == 1)) agree++
                else {
                    if (cell == 1) visHid++ else hidVis++
                    wrongDist += (los.distanceM / 1000).toInt()
                }
            }
            println("$name: viewshed agrees with brute-force line of sight on $agree/$total cells; viewshed-visible/LOS-hidden=$visHid, viewshed-hidden/LOS-visible=$hidVis, km=${wrongDist.sorted()}")
            assertTrue("$name agreement $agree/$total", agree >= total * 0.95)
        }

        // For a look only: 1" data shaves 50-100 m off sharp peaks (Säntis 2449 vs 2502 m,
        // Altmann 2331 vs 2436 m), so peak-to-peak sight lines come out marginal.
        val altmann = peak(GeoPoint(47.2378, 9.3667))
        println("peaks: Säntis ${elevation.at(summit.lat, summit.lon)} m at $summit, Altmann ${elevation.at(altmann.lat, altmann.lon)} m at $altmann")
        val los1 = LineOfSight.compute(elevation, summit, altmann, 1.7, 1.7, 25.0)!!
        val los2 = LineOfSight.compute(elevation, valley, summit, 1.7, 1.7, 25.0)!!
        println("summit->Altmann visible=${los1.targetVisible} margin=${los1.marginM.toInt()} m; valley->summit visible=${los2.targetVisible} obstacle at ${los2.obstacle?.distanceM?.toInt()} m")
        assertTrue("the summit sees more than the valley", top.visibleFraction > 1.3 * low.visibleFraction)
    }

    /** Hillshaded terrain with the viewshed on top; observer marked in blue. */
    private fun render(vs: Viewshed, dem: Elevation, file: File) {
        val n = vs.size
        val img = IntArray(n * n)
        val cellM = 2 * vs.radiusM / n
        val ground = Array(n) { r ->
            val lat = Geodesy.latOf(vs.maxY - (r + 0.5) * (vs.maxY - vs.minY) / n)
            DoubleArray(n) { c -> dem.at(lat, Geodesy.lonOf(vs.minX + (c + 0.5) * (vs.maxX - vs.minX) / n)).toDouble() }
        }
        for (r in 0 until n) for (c in 0 until n) {
            val dzdx = (ground[r][min(c + 1, n - 1)] - ground[r][max(c - 1, 0)]) / (2 * cellM)
            val dzdy = (ground[max(r - 1, 0)][c] - ground[min(r + 1, n - 1)][c]) / (2 * cellM)
            val shade = (0.6 + 0.4 * (-dzdx - dzdy) / Math.sqrt(1 + dzdx * dzdx + dzdy * dzdy)).coerceIn(0.0, 1.0)
            var red = 235 * shade
            var green = 230 * shade
            var blue = 220 * shade
            when (vs.cells[r * n + c].toInt()) {
                1 -> { red = red * 0.65; green = green * 0.65 + 160 * 0.35; blue = blue * 0.65 }
                0 -> { red = red * 0.55 + 200 * 0.45; green = green * 0.55; blue = blue * 0.55 }
            }
            img[r * n + c] = (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
        }
        val m = n / 2
        for (dr in -3..3) for (dc in -3..3) img[(m + dr) * n + m + dc] = 0x1565C0
        writePng(img, n, n, file)
    }

    /** Minimal PNG writer (RGB, no filtering): java.awt isn't available in Android unit tests. */
    private fun writePng(rgb: IntArray, w: Int, h: Int, file: File) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            for (y in 0 until h) {
                z.write(0)
                for (x in 0 until w) {
                    val v = rgb[y * w + x]
                    z.write(v shr 16 and 255); z.write(v shr 8 and 255); z.write(v and 255)
                }
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                out.writeInt(data.size)
                val crc = CRC32()
                val t = type.toByteArray()
                out.write(t); out.write(data)
                crc.update(t); crc.update(data)
                out.writeInt(crc.value.toInt())
            }
            val ihdr = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }
            chunk("IHDR", ihdr.toByteArray())
            chunk("IDAT", raw.toByteArray())
            chunk("IEND", ByteArray(0))
        }
    }
}
