package app.maparound.three

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
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
    @Volatile private var placed: List<Placed> = emptyList()

    /** Ground heights of labels (no exaggeration), looked up once. */
    private val heights = HashMap<Label3D, Float>()

    private class Placed(val label: Label3D, val gx: Float, val gy: Float, val box: RectF, val alpha: Int)

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
        pending = frame
        if (!busy) schedule()
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

    private fun layout(f: FrameState): List<Placed> {
        val candidates = LinkedHashSet<Label3D>()
        for (t in f.labelTiles) engine.labelsOf(t)?.let(candidates::addAll)
        if (candidates.isEmpty()) return emptyList()
        val out = ArrayList<Placed>()
        val taken = ArrayList<RectF>()
        val e = f.exaggeration
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
            out += Placed(l, sx, sy, box, alpha)
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

    override fun onDraw(canvas: Canvas) {
        val list = placed
        val stickLen = STICK_DP * density
        for (pl in list) {
            val l = pl.label
            val colour = COLOURS[l.kind.ordinal]
            // The stick from the ground up to the name, and a mark on the ground.
            stick.color = Color.WHITE
            stick.alpha = pl.alpha
            stick.strokeWidth = 3f * density
            canvas.drawLine(pl.gx, pl.gy, pl.gx, pl.gy - stickLen, stick)
            stick.color = colour
            stick.alpha = pl.alpha
            stick.strokeWidth = 1.5f * density
            canvas.drawLine(pl.gx, pl.gy, pl.gx, pl.gy - stickLen, stick)
            mark.color = colour
            mark.alpha = pl.alpha
            if (l.kind == Label3D.Kind.PEAK) {
                val s = 5 * density
                canvas.drawPath(Path().apply {
                    moveTo(pl.gx, pl.gy - s)
                    lineTo(pl.gx - s, pl.gy + s * 0.6f)
                    lineTo(pl.gx + s, pl.gy + s * 0.6f)
                    close()
                }, mark)
            } else {
                canvas.drawCircle(pl.gx, pl.gy, 3 * density, mark)
            }
            val baseline = pl.gy - stickLen - text.descent()
            halo.alpha = pl.alpha
            canvas.drawText(l.text, pl.gx, baseline, halo)
            text.color = colour
            text.alpha = pl.alpha
            canvas.drawText(l.text, pl.gx, baseline, text)
        }
    }

    private companion object {
        const val STICK_DP = 16f
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
