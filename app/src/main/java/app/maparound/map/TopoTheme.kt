package app.maparound.map

import android.content.Context
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * MapAround's hiking style (assets/themes/topo.xml). Symbols referenced as `jar:` resolve
 * against the mapsforge-themes library, exactly like the built-in themes.
 *
 * [hybrid]: for drawing over satellite imagery or a raster map. The background is transparent
 * and area fills (land use, water, buildings…) are dropped, leaving roads, paths, trails,
 * contours, symbols and labels.
 */
class TopoTheme(context: Context, private val hybrid: Boolean = false) : XmlRenderTheme {
    private val assets = context.applicationContext.assets
    private var menuCallback: XmlRenderThemeMenuCallback? = null

    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = menuCallback
    override fun getRelativePathPrefix(): String = "/assets/"
    override fun getRenderThemeAsStream(): InputStream {
        val stream = assets.open("themes/topo.xml")
        if (!hybrid) return stream
        return ByteArrayInputStream(hybridOf(stream.use { it.readBytes().toString(Charsets.UTF_8) }).toByteArray(Charsets.UTF_8))
    }
    override fun getResourceProvider(): XmlThemeResourceProvider? = null

    override fun setMenuCallback(menuCallback: XmlRenderThemeMenuCallback?) {
        this.menuCallback = menuCallback
    }

    override fun setResourceProvider(resourceProvider: XmlThemeResourceProvider?) = Unit

    // Different themes must not share cached tiles.
    override fun equals(other: Any?) = other is TopoTheme && other.hybrid == hybrid
    override fun hashCode() = if (hybrid) 1 else 0

    companion object {
        /** The theme text without area fills and with a transparent background. */
        fun hybridOf(xml: String): String {
            var s = xml
                .replace(Regex("""<area\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""map-background="#[0-9A-Fa-f]+""""), "map-background=\"#00000000\"")
                .replace(Regex("""map-background-outside="#[0-9A-Fa-f]+""""), "map-background-outside=\"#00000000\"")
            // Rules left without instructions (also nested ones) go too.
            val empty = Regex("""<rule\b[^>]*>\s*</rule>""")
            while (true) {
                val next = empty.replace(s, "")
                if (next == s) return s
                s = next
            }
        }
    }
}
