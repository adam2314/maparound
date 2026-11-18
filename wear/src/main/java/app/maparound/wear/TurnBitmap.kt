package app.maparound.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.maparound.routing.Turn
import app.maparound.routing.TurnGlyph
import java.io.ByteArrayOutputStream

/** A maneuver arrow (the phone's and the car's shapes) as a white PNG [size] pixels square, for the tile. */
fun turnPng(turn: Turn, exit: Int, size: Int = 96): ByteArray {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val glyph = TurnGlyph.of(turn, exit)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = TurnGlyph.STROKE * size
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    fun path(points: List<TurnGlyph.Pt>, close: Boolean) = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * size, p.y * size) else lineTo(p.x * size, p.y * size) }
        if (close) close()
    }
    glyph.lines.forEach { canvas.drawPath(path(it, false), stroke) }
    glyph.heads.forEach { canvas.drawPath(path(it, true), fill) }
    glyph.rings.forEach { canvas.drawCircle(it.center.x * size, it.center.y * size, it.radius * size, if (it.filled) fill else stroke) }
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
