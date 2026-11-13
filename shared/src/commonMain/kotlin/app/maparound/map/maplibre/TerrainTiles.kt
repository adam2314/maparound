package app.maparound.map.maplibre

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.zlibDeflate
import app.maparound.shared.mvt.MvtTile
import app.maparound.terrain.Contours
import app.maparound.terrain.DemIndex
import okio.Buffer
import okio.use
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/**
 * Tiles made from the elevation files ([dem]) for MapLibre: contour lines as vector tiles (layer
 * `contours`, tagged like the Android map's contours, so topo.xml styles them) and elevation as
 * Terrarium-encoded PNGs for MapLibre's own hill shading. Safe to use from one thread at a time.
 */
class TerrainTiles(private val dem: DemIndex) {
    /** The ground's height in metres (bilinear), NaN where there's no data. */
    fun elevation(lat: Double, lon: Double): Float = dem.elevation(lat, lon)

    /** Contour lines of MapLibre tile z/x/y (with mapsforge's intervals one zoom deeper). */
    fun contours(z: Int, x: Int, y: Int): ByteArray {
        val t = MvtTile(z, x, y)
        val north = MapsforgeFile.tileLat(y, z)
        val south = MapsforgeFile.tileLat(y + 1, z)
        val west = MapsforgeFile.tileLon(x, z)
        val east = MapsforgeFile.tileLon(x + 1, z)
        if (z < CONTOUR_MIN_ZOOM || !dem.hasDataIn(south, west, north, east)) return t.encode()

        // One grid cell per elevation sample, within limits; one cell of margin on every side so
        // lines run past the tile edge.
        val cols = ceil((east - west) / dem.finestStep).toInt().coerceIn(MIN_CELLS, MAX_CELLS) + 1
        val rows = ceil((north - south) / dem.finestStep).toInt().coerceIn(MIN_CELLS, MAX_CELLS) + 1
        val stepLat = (north - south) / (rows - 1)
        val stepLon = (east - west) / (cols - 1)
        val gCols = cols + 2
        val gRows = rows + 2
        val top = north + stepLat
        val left = west - stepLon
        val grid = FloatArray(gCols * gRows)
        var valid = 0
        for (r in 0 until gRows) {
            val lat = (top - r * stepLat).coerceIn(-85.0, 85.0)
            for (c in 0 until gCols) {
                val v = dem.elevation(lat, left + c * stepLon)
                grid[r * gCols + c] = v
                if (!v.isNaN()) valid++
            }
        }
        if (valid == 0) return t.encode()
        val (minor, major) = Contours.intervalsFor(z + 1)
        val layer = t.layer(LAYER)
        for (line in Contours.trace(grid, gCols, gRows, minor)) {
            val coords = DoubleArray(line.pointCount * 2)
            for (k in 0 until line.pointCount) {
                coords[2 * k] = (top - line.xy[2 * k + 1] * stepLat).coerceIn(-85.0, 85.0)
                coords[2 * k + 1] = (left + line.xy[2 * k] * stepLon).coerceIn(-180.0, 180.0)
            }
            val kind = if (line.level % major == 0) "major" else "minor"
            layer.line(coords, mapOf("contour" to kind, "ele" to line.level.toLong()))
        }
        return t.encode()
    }

    /**
     * Elevation of a 256-pixel tile, Terrarium-encoded (height + 32768 m = R·256 + G + B/256);
     * null where there's no elevation data at all.
     */
    fun terrarium(z: Int, x: Int, y: Int): ByteArray? {
        val north = MapsforgeFile.tileLat(y, z)
        val south = MapsforgeFile.tileLat(y + 1, z)
        val west = MapsforgeFile.tileLon(x, z)
        val east = MapsforgeFile.tileLon(x + 1, z)
        if (!dem.hasDataIn(south, west, north, east)) return null
        val n = (1L shl z).toDouble()
        val lons = DoubleArray(DEM_SIZE) { (x + (it + 0.5) / DEM_SIZE) / n * 360.0 - 180.0 }
        val rgb = ByteArray(DEM_SIZE * DEM_SIZE * 3)
        for (py in 0 until DEM_SIZE) {
            val m = PI * (1 - 2 * (y + (py + 0.5) / DEM_SIZE) / n)
            val lat = atan((exp(m) - exp(-m)) / 2) * 180 / PI
            for (px in 0 until DEM_SIZE) {
                val h = dem.elevation(lat, lons[px])
                val v = (if (h.isNaN()) 0.0 else h.toDouble()) + 32768.0
                val i = (py * DEM_SIZE + px) * 3
                val whole = floor(v)
                rgb[i] = (whole.toInt() shr 8).toByte()
                rgb[i + 1] = (whole.toInt() and 0xFF).toByte()
                rgb[i + 2] = ((v - whole) * 256).toInt().toByte()
            }
        }
        return Png.rgb(DEM_SIZE, DEM_SIZE, rgb)
    }

    companion object {
        const val LAYER = "contours"

        /** MapLibre zoom (mapsforge 11, like the Android contours). */
        const val CONTOUR_MIN_ZOOM = 10
        const val CONTOUR_MAX_ZOOM = 15
        const val DEM_SIZE = 256
        const val DEM_MAX_ZOOM = 12
        private const val MIN_CELLS = 48
        private const val MAX_CELLS = 384
    }
}

/** A minimal PNG writer (8-bit RGB, zlib-compressed). */
object Png {
    fun rgb(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(rgb.size == width * height * 3)
        val raw = Buffer()
        for (row in 0 until height) {
            raw.writeByte(0) // no filter
            raw.write(rgb, row * width * 3, width * 3)
        }
        val compressed = zlibDeflate(raw.readByteArray())

        val out = Buffer()
        out.write(SIGNATURE)
        chunk(out, "IHDR", Buffer().writeInt(width).writeInt(height).writeByte(8).writeByte(2).writeByte(0).writeByte(0).writeByte(0).readByteArray())
        chunk(out, "IDAT", compressed)
        chunk(out, "IEND", ByteArray(0))
        return out.readByteArray()
    }

    private fun chunk(out: Buffer, type: String, data: ByteArray) {
        val typeBytes = type.encodeToByteArray()
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        var crc = crc32(0xFFFFFFFFL.toInt(), typeBytes)
        crc = crc32(crc, data)
        out.writeInt(crc.inv())
    }

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320L.toInt() xor (c ushr 1)) else c ushr 1 }
        c
    }

    /** Running CRC-32 (start with all ones, invert at the end). */
    fun crc32(start: Int, data: ByteArray): Int {
        var c = start
        for (b in data) c = TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c
    }
}
