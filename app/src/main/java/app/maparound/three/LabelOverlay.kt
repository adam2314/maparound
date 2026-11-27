package app.maparound.three

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.maparound.map.gl.MarkerBitmaps
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Names standing up over the 3D map: places, peaks with their heights, huts, and the world's
 * countries and seas from far away. Each frame the renderer [publish]es where the camera is; the
 * labels are placed on a background thread (projected, hidden behind ridges or beyond the
 * horizon, the most important first where they'd overlap) and drawn here as text, crisp at any
 * angle.
 */
@SuppressLint("ViewConstructor")
class LabelOverlay(context: Context, private val engine: TerrainEngine) : View(context) {
    private val density = resources.displayMetrics.density
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "3d-label-layout").apply { isDaemon = true } }

    @Volatile private var pending: FrameState? = null
    @Volatile private var busy = false
    @Volatile private var placed = Layout(emptyList(), emptyList())

    /** Ground heights of labels (no exaggeration), looked up once. */
    private val heights = HashMap<Label3D, Float>()

    /** A placed name: where the layout's [frame] had it on the screen, and where it is ([p], ECEF). */
    private class Placed(val label: Label3D, val gx: Float, val gy: Float, val box: RectF, val alpha: Int, val p: Vec3, val frame: FrameState)

    /** A placed marker, as [Placed]: its image, faint ([alpha]) when a ridge hides it. */
    private class PlacedMarker(val image: Bitmap, val gx: Float, val gy: Float, val alpha: Int, val p: Vec3, val frame: FrameState)

    private class Layout(val labels: List<Placed>, val markers: List<PlacedMarker>)

    /** My position and the parties' ([setMarkers]): placed first, the names keep clear of them. */
    @Volatile private var markers: List<Marker3D> = emptyList()

    /** The 2D map's marker images, made on the layout's thread and kept by name. */
    private val markerImages = MarkerBitmaps(density)
    private val images = HashMap<String, Bitmap?>()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setMarkers(list: List<Marker3D>) {
        markers = list
    }

    /** The newest frame: names are drawn where it shows them (the layout runs behind). */
    @Volatile private var latest: FrameState? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val halo = Paint(text).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val stick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * density }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    /** What the last frame showed; the labels follow when their layout is done. */
    fun publish(frame: FrameState) {
        latest = frame
        pending = frame
        if (!busy) schedule()
        // Drawn again with each frame, at its positions, while the layout catches up.
        if (placed.labels.isNotEmpty() || placed.markers.isNotEmpty()) postInvalidateOnAnimation()
    }

    fun release() {
        worker.shutdownNow()
    }

    private fun schedule() {
        val f = pending ?: return
        pending = null
        busy = true
        worker.execute {
            try {
                placed = layout(f)
                postInvalidateOnAnimation()
            } catch (_: Throwable) {
                // A layout that fails just isn't shown; the next frame tries again.
            } finally {
                busy = false
                if (pending != null) post { if (!busy) schedule() }
            }
        }
    }

    private fun layout(f: FrameState): Layout {
        val taken = ArrayList<RectF>()
        val e = f.exaggeration
        val marks = placeMarkers(f, taken)
        val candidates = LinkedHashSet<Label3D>()
        for (t in f.labelTiles) engine.labelsOf(t)?.let(candidates::addAll)
        if (candidates.isEmpty()) return Layout(emptyList(), marks)
        val out = ArrayList<Placed>()
        val pad = 3 * density
        val stickLen = STICK_DP * density
        val textH = text.descent() - text.ascent()
        // From high up (or far away) the map's small things go: only the bigger kinds stay.
        val scale = max(1.0, max(f.targetDistance, f.altitude) / 6_000.0)
        if (heights.size > 5_000) heights.clear()
        // The same place from the world and from a map (e.g. a capital): shown once.
        val shown = HashMap<String, ArrayList<Label3D>>()
        for (l in candidates.sortedBy { it.priority }) {
            if (out.size >= MAX_LABELS) break
            if (shown[l.name]?.any { kotlin.math.abs(it.lat - l.lat) < SAME_PLACE_DEG && kotlin.math.abs(it.lon - l.lon) < SAME_PLACE_DEG } == true) continue
            val range = RANGE_M[l.kind.ordinal] * scale
            val h = heights.getOrPut(l) { engine.groundAt(l.lat, l.lon) } * e
            val p = Wgs84.ecef(l.lat, l.lon, h.toDouble())
            val rel = p - f.eye
            val dist = rel.length
            if (dist > range) continue
            // Beyond the horizon: the eye is below the point's horizon.
            if (((f.eye - p) dot Wgs84.up(l.lat, l.lon)) < -20) continue
            val c = clip(f.viewProj, rel.x, rel.y, rel.z)
            if (c[3] <= 0) continue
            val sx = ((c[0] / c[3] + 1) / 2 * f.width).toFloat()
            val sy = ((1 - c[1] / c[3]) / 2 * f.height).toFloat()
            if (sx < -50 || sx > f.width + 50 || sy < 0 || sy > f.height + 50) continue
            val w = text.measureText(l.text)
            val box = RectF(sx - w / 2 - pad, sy - stickLen - textH - pad, sx + w / 2 + pad, sy - stickLen + pad)
            if (box.top < 0 || taken.any { RectF.intersects(it, box) }) continue
            // Hidden behind a ridge (only worth checking within sight of the terrain).
            if (dist < 150_000 && f.altitude < 300_000 && hidden(f.eye, p, e)) continue
            taken += box
            shown.getOrPut(l.name) { ArrayList() } += l
            // Far ones fade a little.
            val alpha = (255 * (1 - 0.45 * (dist / range))).toInt().coerceIn(120, 255)
            out += Placed(l, sx, sy, box, alpha, p, f)
        }
        return Layout(out, marks)
    }

    /**
     * The markers on the ground where [f] shows them, each image centred on its point (as on the 2D
     * map); behind a ridge faint, not gone (a party member over the hill is still worth seeing).
     * Their boxes go into [taken], so names keep clear of them.
     */
    private fun placeMarkers(f: FrameState, taken: MutableList<RectF>): List<PlacedMarker> {
        val list = markers
        if (list.isEmpty()) return emptyList()
        if (images.size > 200) images.clear()
        val e = f.exaggeration
        val out = ArrayList<PlacedMarker>()
        for (m in list) {
            val image = images.getOrPut(m.image) { markerImages.image(m.image) { null } } ?: continue
            val p = Wgs84.ecef(m.lat, m.lon, (engine.groundAt(m.lat, m.lon) * e).toDouble())
            val rel = p - f.eye
            if (((f.eye - p) dot Wgs84.up(m.lat, m.lon)) < -20) continue
            val c = clip(f.viewProj, rel.x, rel.y, rel.z)
            if (c[3] <= 0) continue
            val sx = ((c[0] / c[3] + 1) / 2 * f.width).toFloat()
            val sy = ((1 - c[1] / c[3]) / 2 * f.height).toFloat()
            val hw = image.width / 2f
            val hh = image.height / 2f
            if (sx < -hw || sx > f.width + hw || sy < -hh || sy > f.height + hh) continue
            val behind = rel.length < 150_000 && f.altitude < 300_000 && hidden(f.eye, p, e)
            out += PlacedMarker(image, sx, sy, if (behind) HIDDEN_ALPHA else 255, p, f)
            taken += RectF(sx - hw, sy - hh, sx + hw, sy + hh)
        }
        return out
    }

    /** Whether the terrain blocks the line from the eye to [p] (samples along it). */
    private fun hidden(eye: Vec3, p: Vec3, e: Float): Boolean {
        for (i in 1 until SAMPLES) {
            val t = i / SAMPLES.toDouble()
            if (t > 0.97) break
            val q = eye + (p - eye) * t
            val g = Wgs84.geodetic(q)
            if (g[2] < engine.groundAt(g[0], g[1]) * e - 15) return true
        }
        return false
    }

    /**
     * Where [p] is on the screen in [f]: at ([gx], [gy]) in the layout's own frame ([placedIn]), else
     * projected again; null behind the eye.
     */
    private fun onScreen(p: Vec3, gx: Float, gy: Float, placedIn: FrameState, f: FrameState?): Pair<Float, Float>? {
        if (f == null || f === placedIn) return gx to gy
        val rel = p - f.eye
        val c = clip(f.viewProj, rel.x, rel.y, rel.z)
        if (c[3] <= 0) return null
        return ((c[0] / c[3] + 1) / 2 * f.width).toFloat() to ((1 - c[1] / c[3]) / 2 * f.height).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val lay = placed
        val frame = latest
        val stickLen = STICK_DP * density
        for (pl in lay.labels) {
            val l = pl.label
            val (gx, gy) = onScreen(pl.p, pl.gx, pl.gy, pl.frame, frame) ?: continue
            val colour = COLOURS[l.kind.ordinal]
            // The stick from the ground up to the name, and a mark on the ground.
            stick.color = Color.WHITE
            stick.alpha = pl.alpha
            stick.strokeWidth = 3f * density
            canvas.drawLine(gx, gy, gx, gy - stickLen, stick)
            stick.color = colour
            stick.alpha = pl.alpha
            stick.strokeWidth = 1.5f * density
            canvas.drawLine(gx, gy, gx, gy - stickLen, stick)
            mark.color = colour
            mark.alpha = pl.alpha
            if (l.kind == Label3D.Kind.PEAK) {
                val s = 5 * density
                canvas.drawPath(Path().apply {
                    moveTo(gx, gy - s)
                    lineTo(gx - s, gy + s * 0.6f)
                    lineTo(gx + s, gy + s * 0.6f)
                    close()
                }, mark)
            } else {
                canvas.drawCircle(gx, gy, 3 * density, mark)
            }
            val baseline = gy - stickLen - text.descent()
            halo.alpha = pl.alpha
            canvas.drawText(l.text, gx, baseline, halo)
            text.color = colour
            text.alpha = pl.alpha
            canvas.drawText(l.text, gx, baseline, text)
        }
        // The markers over the names: mine last, on top.
        for (m in lay.markers) {
            val (x, y) = onScreen(m.p, m.gx, m.gy, m.frame, frame) ?: continue
            imagePaint.alpha = m.alpha
            canvas.drawBitmap(m.image, x - m.image.width / 2f, y - m.image.height / 2f, imagePaint)
        }
    }

    private companion object {
        const val STICK_DP = 16f

        /** A marker behind a ridge: faint. */
        const val HIDDEN_ALPHA = 110
        const val SAMPLES = 24
        const val MAX_LABELS = 60

        /** Names this close (degrees, about 3 km) are the same place. */
        const val SAME_PLACE_DEG = 0.03

        // Per kind (Label3D.Kind order): how far away it's shown (m, scaled up from high up) and its colour.
        val RANGE_M = doubleArrayOf(
            40_000_000.0, // country
            40_000_000.0, // sea
            600_000.0, // city
            60_000.0, // peak
            150_000.0, // town
            25_000.0, // pass
            20_000.0, // hut
            40_000.0, // village
            80_000.0, // water
            12_000.0, // hamlet
        )
        val COLOURS = intArrayOf(
            Color.rgb(90, 60, 110), // country
            Color.rgb(30, 90, 160), // sea
            Color.rgb(30, 30, 30), // city
            Color.rgb(110, 60, 20), // peak
            Color.rgb(45, 45, 45), // town
            Color.rgb(110, 60, 20), // pass
            Color.rgb(170, 40, 40), // hut
            Color.rgb(60, 60, 60), // village
            Color.rgb(30, 90, 160), // water
            Color.rgb(80, 80, 80), // hamlet
        )
    }
}
