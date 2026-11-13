package app.maparound.map.gl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The map's scale: a metric bar above an imperial one, with their lengths, like mapsforge's
 * scale bar. [update] with the camera.
 */
class ScaleBarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var metresPerDp = 0.0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val halo = Paint(line).apply {
        color = Color.WHITE
        strokeWidth = 5 * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 12 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textHalo = Paint(text).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }

    /** MapLibre's camera: [zoom] (512-point tiles) at latitude [lat]. */
    fun update(lat: Double, zoom: Double) {
        val m = EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / (512 * 2.0.pow(zoom))
        if (m == metresPerDp) return
        metresPerDp = m
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(((MAX_DP + 16) * density).toInt(), (40 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (metresPerDp <= 0) return
        val d = density
        val x0 = 4 * d
        val mid = 20 * d
        // Metric above the line, imperial below.
        val metres = nice(metresPerDp * MAX_DP)
        val feet = metresPerDp * MAX_DP / 0.3048
        val imperial = if (feet >= 5280) nice(feet / 5280) * 1609.344 else nice(feet) * 0.3048
        val wMetric = (metres / metresPerDp * d).toFloat()
        val wImperial = (imperial / metresPerDp * d).toFloat()
        val w = maxOf(wMetric, wImperial)
        for (p in listOf(halo, line)) {
            canvas.drawLine(x0, mid, x0 + w, mid, p)
            canvas.drawLine(x0, mid - 8 * d, x0, mid + 8 * d, p)
            canvas.drawLine(x0 + wMetric, mid, x0 + wMetric, mid - 8 * d, p)
            canvas.drawLine(x0 + wImperial, mid, x0 + wImperial, mid + 8 * d, p)
        }
        val top = metricLabel(metres)
        val bottom = imperialLabel(imperial)
        for (p in listOf(textHalo, text)) {
            canvas.drawText(top, x0 + 4 * d, mid - 3 * d, p)
            canvas.drawText(bottom, x0 + 4 * d, mid + 3 * d - text.ascent(), p)
        }
    }

    private fun metricLabel(m: Double) = if (m >= 1000) "${fmt(m / 1000)} km" else "${fmt(m)} m"

    private fun imperialLabel(m: Double): String {
        val miles = m / 1609.344
        return if (miles >= 0.999) "${fmt(miles)} mi" else "${fmt(m / 0.3048)} ft"
    }

    private fun fmt(v: Double): String = if (v >= 1 || v == 0.0) v.toLong().toString() else "%.1f".format(v)

    private companion object {
        /** Longest bar, dp. */
        const val MAX_DP = 100.0
        const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

        /** The largest 1, 2 or 5 × 10ⁿ not over [v]. */
        fun nice(v: Double): Double {
            if (v <= 0) return 0.0
            val p = 10.0.pow(floor(log10(v)))
            val f = v / p
            return p * when {
                f >= 5 -> 5.0
                f >= 2 -> 2.0
                else -> 1.0
            }
        }
    }
}
