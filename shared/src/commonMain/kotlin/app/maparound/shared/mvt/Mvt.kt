package app.maparound.shared.mvt

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// Mapbox Vector Tile (MVT 2.1) writer: the format MapLibre draws. Plain Kotlin, unit-tested.

/** One MVT tile being built; add features to layers, then [encode]. */
class MvtTile(val z: Int, val x: Int, val y: Int, val extent: Int = 4096) {
    private val layers = LinkedHashMap<String, Layer>()

    fun layer(name: String): Layer = layers.getOrPut(name) { Layer(name) }

    val isEmpty: Boolean get() = layers.values.all { it.features.isEmpty() }

    /** Tile-local coordinates (0..extent across the tile) of a lat/lon. */
    fun px(lon: Double): Long = floor(((lon + 180.0) / 360.0 * (1L shl z) - x) * extent + 0.5).toLong()
    fun py(lat: Double): Long {
        val r = lat.coerceIn(-85.05112877980659, 85.05112877980659) * PI / 180.0
        val v = (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1L shl z)
        return floor((v - y) * extent + 0.5).toLong()
    }

    inner class Layer(val name: String) {
        internal val features = ArrayList<ByteArray>()
        private val keys = LinkedHashMap<String, Int>()
        private val values = LinkedHashMap<Any, Int>()

        /** A point; [props] values may be String, Int, Long, Double, Float or Boolean. */
        fun point(lat: Double, lon: Double, props: Map<String, Any>) {
            val g = Geometry()
            g.moveTo(px(lon), py(lat))
            add(TYPE_POINT, g, props)
        }

        /** A line from [lat0, lon0, lat1, lon1, …]. */
        fun line(coords: DoubleArray, props: Map<String, Any>) {
            val pts = quantize(coords)
            if (pts.size < 4) return
            val g = Geometry()
            g.path(pts, closed = false)
            add(TYPE_LINE, g, props)
        }

        /**
         * A polygon: outer ring first, then holes, each [lat0, lon0, …]. Winding is fixed as MVT
         * requires (outer rings positive area in tile coordinates, holes negative).
         */
        fun polygon(rings: List<DoubleArray>, props: Map<String, Any>) {
            val g = Geometry()
            var any = false
            rings.forEachIndexed { i, ring ->
                var pts = quantize(ring)
                // Drop the closing point; ClosePath closes the ring.
                if (pts.size >= 4 && pts[0] == pts[pts.size - 2] && pts[1] == pts[pts.size - 1]) pts = pts.copyOf(pts.size - 2)
                if (pts.size < 6) return@forEachIndexed
                val area = area(pts)
                if (area == 0L) return@forEachIndexed
                val outer = i == 0
                if ((outer && area < 0) || (!outer && area > 0)) pts = reverse(pts)
                g.path(pts, closed = true)
                any = true
            }
            if (any) add(TYPE_POLYGON, g, props)
        }

        private fun add(type: Int, g: Geometry, props: Map<String, Any>) {
            val f = Pb()
            val tags = Pb()
            for ((k, v) in props) {
                val key = keys.getOrPut(k) { keys.size }
                val value = values.getOrPut(normalize(v)) { values.size }
                tags.rawVarint(key.toLong())
                tags.rawVarint(value.toLong())
            }
            if (props.isNotEmpty()) f.bytes(2, tags.toByteArray()) // tags (packed)
            f.varint(3, type.toLong())
            f.bytes(4, g.out.toByteArray()) // geometry (packed)
            features += f.toByteArray()
        }

        internal fun encode(): ByteArray {
            val l = Pb()
            l.varint(15, 2) // version
            l.string(1, name)
            for (f in features) l.bytes(2, f)
            for (k in keys.keys) l.string(3, k)
            for (v in values.keys) l.bytes(4, valueMessage(v))
            l.varint(5, extent.toLong())
            return l.toByteArray()
        }
    }

    fun encode(): ByteArray {
        val t = Pb()
        for (l in layers.values) if (l.features.isNotEmpty()) t.bytes(3, l.encode())
        return t.toByteArray()
    }

    private fun quantize(coords: DoubleArray): LongArray {
        val out = LongArray(coords.size)
        var n = 0
        var i = 0
        while (i < coords.size) {
            val xx = px(coords[i + 1])
            val yy = py(coords[i])
            if (n == 0 || xx != out[n - 2] || yy != out[n - 1]) {
                out[n++] = xx
                out[n++] = yy
            }
            i += 2
        }
        return out.copyOf(n)
    }

    /** Twice the signed area (tile coordinates, y down): positive = clockwise on screen. */
    private fun area(p: LongArray): Long {
        var a = 0L
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return a
    }

    private fun reverse(p: LongArray): LongArray {
        val n = p.size / 2
        return LongArray(p.size) { k -> val i = n - 1 - k / 2; p[2 * i + k % 2] }
    }

    private class Geometry {
        val out = Pb()
        private var cx = 0L
        private var cy = 0L

        fun moveTo(x: Long, y: Long) {
            out.rawVarint(command(1, 1))
            delta(x, y)
        }

        fun path(p: LongArray, closed: Boolean) {
            out.rawVarint(command(1, 1))
            delta(p[0], p[1])
            val n = p.size / 2 - 1
            if (n > 0) {
                out.rawVarint(command(2, n))
                for (i in 1..n) delta(p[2 * i], p[2 * i + 1])
            }
            if (closed) out.rawVarint(command(7, 1))
        }

        private fun delta(x: Long, y: Long) {
            out.rawVarint(zigzag(x - cx))
            out.rawVarint(zigzag(y - cy))
            cx = x
            cy = y
        }

        private fun command(id: Int, count: Int): Long = ((id and 7) or (count shl 3)).toLong()
        private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    }

    companion object {
        const val TYPE_POINT = 1
        const val TYPE_LINE = 2
        const val TYPE_POLYGON = 3

        private fun normalize(v: Any): Any = when (v) {
            is Int -> v.toLong()
            is Float -> v.toDouble()
            else -> v
        }

        private fun valueMessage(v: Any): ByteArray {
            val m = Pb()
            when (v) {
                is String -> m.string(1, v)
                is Double -> m.fixed64(3, v.toRawBits())
                is Long -> if (v >= 0) m.varint(5, v) else m.varint(6, (v shl 1) xor (v shr 63)) // uint / sint
                is Boolean -> m.varint(7, if (v) 1 else 0)
                else -> m.string(1, v.toString())
            }
            return m.toByteArray()
        }
    }
}

/** Minimal protobuf writer. */
internal class Pb {
    private var buf = ByteArray(256)
    private var n = 0

    private fun ensure(k: Int) {
        if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k))
    }

    fun rawVarint(v: Long) {
        ensure(10)
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            buf[n++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        buf[n++] = x.toByte()
    }

    fun varint(field: Int, v: Long) {
        rawVarint((field shl 3).toLong())
        rawVarint(v)
    }

    fun fixed64(field: Int, v: Long) {
        rawVarint(((field shl 3) or 1).toLong())
        ensure(8)
        for (i in 0 until 8) buf[n++] = (v ushr (8 * i)).toByte()
    }

    fun bytes(field: Int, b: ByteArray) {
        rawVarint(((field shl 3) or 2).toLong())
        rawVarint(b.size.toLong())
        ensure(b.size)
        b.copyInto(buf, n)
        n += b.size
    }

    fun string(field: Int, s: String) = bytes(field, s.encodeToByteArray())

    fun toByteArray(): ByteArray = buf.copyOf(n)
}

