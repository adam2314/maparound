package app.maparound.raster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/** Draws a raster map's Web Mercator tiles (RMAP or tile database) into ARGB pixels. */
interface RasterTiles {
    /** Renders tile z/x/y at [size]² into [out] (ARGB). False if the map doesn't cover it at all. */
    fun render(z: Int, x: Int, y: Int, size: Int, out: IntArray): Boolean
}

/**
 * A tile database's images as map tiles: its own tiles at its zoom levels, enlarged parts of its
 * most detailed tiles beyond them, and up to two levels above its least detailed one put together
 * from their tiles.
 */
class TileDatabaseRenderer(private val db: TileDatabase) : RasterTiles {
    private class Entry(val bitmap: Bitmap?)
    private val cache = object : LinkedHashMap<Long, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > 24
    }

    override fun render(z: Int, x: Int, y: Int, size: Int, out: IntArray): Boolean {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val drew = when {
            z > db.maxZoom -> {
                // Enlarge the part of a more detailed tile that this one covers.
                val dz = z - db.maxZoom
                val n = 1 shl dz
                val src = source(db.maxZoom, x shr dz, y shr dz)
                if (src != null) {
                    val m = Matrix()
                    val scale = size.toFloat() * n / src.width
                    m.setScale(scale, size.toFloat() * n / src.height)
                    m.postTranslate(-(x - (x shr dz shl dz)) * size.toFloat(), -(y - (y shr dz shl dz)) * size.toFloat())
                    canvas.drawBitmap(src, m, paint)
                }
                src != null
            }
            z >= db.minZoom -> source(z, x, y)?.also { canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, size, size), paint) } != null
            db.minZoom - z <= 2 -> {
                // Put the tile together from its smaller tiles one or two levels down.
                val dz = db.minZoom - z
                val n = 1 shl dz
                val cell = size.toFloat() / n
                var any = false
                for (j in 0 until n) for (i in 0 until n) {
                    val src = source(db.minZoom, (x shl dz) + i, (y shl dz) + j) ?: continue
                    canvas.drawBitmap(src, null, android.graphics.RectF(i * cell, j * cell, (i + 1) * cell, (j + 1) * cell), paint)
                    any = true
                }
                any
            }
            else -> false
        }
        if (drew) bmp.getPixels(out, 0, size, 0, 0, size, size)
        bmp.recycle()
        return drew
    }

    /** A decoded tile of the database (kept for a while: neighbouring map tiles often need it again). */
    private fun source(z: Int, x: Int, y: Int): Bitmap? {
        val key = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()
        synchronized(cache) { cache[key]?.let { return it.bitmap } }
        val bytes = runCatching { db.tile(z, x, y) }.getOrNull()
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        synchronized(cache) { cache[key] = Entry(bitmap) }
        return bitmap
    }
}
