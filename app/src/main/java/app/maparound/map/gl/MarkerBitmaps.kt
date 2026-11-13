package app.maparound.map.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.maparound.party.PartyIcons
import com.caverock.androidsvg.SVG
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws the map's marker images from their names (see MarkerIcons.kt for the format), with the
 * same look as the iPhone app's (MarkerImages.swift). Every image is centred on its point except
 * the pin, whose tip is the point. Sizes are in dp, drawn at the screen's [density].
 */
class MarkerBitmaps(private val density: Float) {

    /** The image for a style image name, or null if it isn't one of ours. */
    fun image(name: String, openSymbol: (String) -> InputStream?): Bitmap? = runCatching {
        if (name.startsWith(SYMBOL)) return@runCatching symbol(name.removePrefix(SYMBOL), openSymbol)
        val f = name.split(SEP)
        when (f[0]) {
            "me" -> dot(rgb(30, 136, 229))
            "pin" -> pin()
            "place" -> if (f.size >= 4) badge(
                ring = argb(f[1]), ringWidth = 2.5f, body = Color.WHITE, radius = 12f,
                symbol = f[2], symbolSize = 14f, symbolBold = false, symbolColor = DARK,
                label = f[3], labelColor = DARK, alpha = 1f,
            ) else null
            "party" -> if (f.size >= 5) party(f) else null
            "wp" -> if (f.size >= 3) {
                val fill = when (f[2]) { "0" -> rgb(46, 125, 50); "2" -> rgb(198, 40, 40); else -> rgb(106, 27, 154) }
                disc(26f, false, listOf(12f to Color.WHITE, 10f to fill), f[1], 12f)
            } else null
            "letter" -> if (f.size >= 3) disc(28f, true, listOf(12f to Color.WHITE, 10f to argb(f[2])), f[1], 14f) else null
            else -> null
        }
    }.getOrNull()?.also { it.density = (density * 160).toInt() }

    private fun party(f: List<String>): Bitmap {
        val kind = f[1]
        val sos = kind == "sos"
        val body = when (kind) { "meet" -> rgb(198, 40, 40); "radio" -> rgb(97, 97, 97); else -> argb(f[2]) }
        val letter = if (sos) "!" else f[3].take(1).uppercase()
        val iconCode = if (f.size >= 7) f[5].toIntOrNull() ?: 0 else 0
        val symbolColor = if (f.size >= 7 && kind == "member") argb(f[6]) else Color.WHITE
        val icon = if (kind == "member" && iconCode != 0) PartyIcons.svg(iconCode) else ""
        return badge(
            ring = if (sos) RED else Color.WHITE, ringWidth = if (sos) 4f else 2f, body = body, radius = 13f,
            symbol = if (kind == "meet") "" else letter, symbolSize = 13f, symbolBold = true, symbolColor = symbolColor,
            label = f[3], labelColor = if (sos) RED else DARK, alpha = if (f[4] == "1") 140 / 255f else 1f,
            flag = kind == "meet", icon = icon,
        )
    }

    /** A symbol of the map style (an SVG), [SYMBOL_HEIGHT] dp high. */
    private fun symbol(path: String, open: (String) -> InputStream?): Bitmap? {
        val svg = open(path)?.use { SVG.getFromInputStream(it) } ?: return null
        val h = SYMBOL_HEIGHT * density
        val ratio = svg.documentAspectRatio.takeIf { it > 0 } ?: 1f
        val w = h * ratio
        val bmp = Bitmap.createBitmap(max(1, ceil(w).toInt()), max(1, ceil(h).toInt()), Bitmap.Config.ARGB_8888)
        svg.setDocumentWidth(w)
        svg.setDocumentHeight(h)
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }

    /** A round marker with an optional label tag below; the circle's centre is the image's centre. */
    private fun badge(
        ring: Int, ringWidth: Float, body: Int, radius: Float,
        symbol: String, symbolSize: Float, symbolBold: Boolean, symbolColor: Int,
        label: String, labelColor: Int, alpha: Float, flag: Boolean = false, icon: String = "",
    ): Bitmap {
        val d = density
        val labelPaint = textPaint(11f, true, withAlpha(labelColor, alpha))
        val labelW = if (label.isEmpty()) 0f else labelPaint.measureText(label) / d + 8
        val labelH = if (label.isEmpty()) 0f else 16f
        val gap = if (label.isEmpty()) 0f else 3f
        val dia = 2 * (radius + ringWidth)
        val content = dia + gap + labelH
        val topPad = content - dia
        val w = ceil(max(dia, labelW)) + 2
        val h = ceil(topPad + content)
        val bmp = Bitmap.createBitmap(ceil(w * d).toInt(), ceil(h * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2
        val cy = topPad + dia / 2
        p.color = withAlpha(ring, alpha)
        c.drawCircle(cx, cy, dia / 2, p)
        p.color = withAlpha(body, alpha)
        c.drawCircle(cx, cy, radius, p)
        when {
            flag -> {
                // A small white flag: the meeting point.
                p.color = withAlpha(Color.WHITE, alpha)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                c.drawLine(cx - 4, cy - 7, cx - 4, cy + 7, p)
                p.style = Paint.Style.FILL
                c.drawPath(Path().apply { moveTo(cx - 4, cy - 7); lineTo(cx + 7, cy - 3.5f); lineTo(cx - 4, cy); close() }, p)
            }
            icon.isNotEmpty() -> drawIcon(c, icon, cx, cy, 18f, withAlpha(symbolColor, alpha))
            symbol.isNotEmpty() -> {
                val sp = textPaint(symbolSize, symbolBold, withAlpha(symbolColor, alpha), scaled = false)
                c.drawText(symbol, cx, cy - (sp.descent() + sp.ascent()) / 2, sp)
            }
        }
        if (label.isNotEmpty()) {
            val top = cy + dia / 2 + gap
            p.style = Paint.Style.FILL
            p.color = withAlpha(Color.WHITE, 0.9f * alpha)
            c.drawRoundRect(RectF(cx - labelW / 2, top, cx + labelW / 2, top + labelH), 4f, 4f, p)
            val lp = textPaint(11f, true, withAlpha(labelColor, alpha), scaled = false)
            c.drawText(label, cx, top + labelH / 2 - (lp.descent() + lp.ascent()) / 2, lp)
        }
        return bmp
    }

    /**
     * A party icon from PartyIcons.svg: paths split by "|", each "N" or "E" (fill rule) then
     * M/L/Z commands in a 24×24 box, drawn centred on (cx, cy), [size] across.
     */
    private fun drawIcon(c: Canvas, svg: String, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (part in svg.split('|')) {
            val tokens = part.split(' ').filter { it.isNotEmpty() }
            val rule = tokens.firstOrNull() ?: continue
            val path = Path().apply { fillType = if (rule == "E") Path.FillType.EVEN_ODD else Path.FillType.WINDING }
            var i = 1
            while (i < tokens.size) {
                val t = tokens[i]
                if (t == "Z") { path.close(); i++; continue }
                if (i + 2 >= tokens.size) break
                val x = tokens[i + 1].toFloatOrNull() ?: break
                val y = tokens[i + 2].toFloatOrNull() ?: break
                val px = cx + (x - 12) * s
                val py = cy + (y - 12) * s
                if (t == "M") path.moveTo(px, py) else path.lineTo(px, py)
                i += 3
            }
            c.drawPath(path, p)
        }
    }

    /** Concentric discs (radius, colour), outermost first, with centred white text. */
    private fun disc(size: Float, shadow: Boolean, rings: List<Pair<Float, Int>>, text: String, textSize: Float): Bitmap {
        val d = density
        val bmp = Bitmap.createBitmap(ceil(size * d).toInt(), ceil(size * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2
        if (shadow) {
            p.color = Color.argb(70, 0, 0, 0)
            c.drawCircle(r, r + 1, r - 1, p)
        }
        for ((radius, color) in rings) {
            p.color = color
            c.drawCircle(r, r, radius, p)
        }
        val tp = textPaint(textSize, true, Color.WHITE, scaled = false)
        c.drawText(text, r, r - (tp.descent() + tp.ascent()) / 2, tp)
        return bmp
    }

    /** The own position: a blue dot with a white rim. */
    private fun dot(color: Int): Bitmap {
        val d = density
        val bmp = Bitmap.createBitmap(ceil(24 * d).toInt(), ceil(24 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(60, 0, 0, 0)
        c.drawCircle(12f, 13f, 11f, p)
        p.color = Color.WHITE
        c.drawCircle(12f, 12f, 10f, p)
        p.color = color
        c.drawCircle(12f, 12f, 7f, p)
        return bmp
    }

    /** The dropped pin; its tip is at the bottom centre. */
    private fun pin(): Bitmap {
        val d = density
        val w = 28f
        val h = 40f
        val r = w / 2
        val bmp = Bitmap.createBitmap(ceil(w * d).toInt(), ceil(h * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = rgb(211, 47, 47)
        c.drawPath(Path().apply { moveTo(r, h); lineTo(r - r * 0.62f, r * 1.55f); lineTo(r + r * 0.62f, r * 1.55f); close() }, p)
        c.drawCircle(r, r, r - 1, p)
        p.color = Color.WHITE
        c.drawCircle(r, r, r * 0.38f, p)
        return bmp
    }

    /** Text paint; sizes in dp ([scaled]: in screen pixels, for measuring before the canvas scales). */
    private fun textPaint(size: Float, bold: Boolean, color: Int, scaled: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = if (scaled) size * density else size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
        this.color = color
    }

    companion object {
        private const val SEP = "\u001F"
        private const val SYMBOL = "sym:"

        /** Height of style symbols, dp (ThemeStyle.SYMBOL_SIZE). */
        private const val SYMBOL_HEIGHT = 20f

        private val DARK = Color.rgb(40, 40, 40)
        private val RED = Color.rgb(213, 0, 0)

        private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

        /** "AARRGGBB" hex. */
        private fun argb(hex: String): Int = (hex.toLongOrNull(16) ?: 0xFF808080).toInt()

        private fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
