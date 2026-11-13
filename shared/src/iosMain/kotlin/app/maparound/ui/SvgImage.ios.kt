package app.maparound.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.roundToInt

/**
 * An SVG (a map style's symbol) drawn [heightPx] high, keeping its proportions (Skia's SVG
 * renderer). Null when it can't be read.
 */
fun renderSvg(bytes: ByteArray, heightPx: Int): ImageBitmap? = runCatching {
    val dom = SVGDOM(Data.makeFromBytes(bytes))
    val root = dom.root ?: return null
    // Its own size: the view box, else its width and height, else a square.
    val box = root.viewBox
    val w0 = box?.width?.takeIf { it > 0f } ?: root.width.value.takeIf { it > 0f } ?: 20f
    val h0 = box?.height?.takeIf { it > 0f } ?: root.height.value.takeIf { it > 0f } ?: 20f
    val h = heightPx.coerceAtLeast(1)
    val w = (h * w0 / h0).roundToInt().coerceAtLeast(1)
    dom.setContainerSize(w0, h0)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.scale(w / w0, h / h0)
    dom.render(surface.canvas)
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()
