package app.maparound.map.maplibre

/**
 * The offline map's MapLibre styles, one per map style (Topo, Topo v2): the theme's
 * rules made into layers by [ThemeStyle], and again with fewer rule categories for its options.
 * Made on first use and kept. The web's and iPhone's; Android's are map/gl/GlStyles.
 * [xml]: a style's render theme, null when there's none.
 */
class ThemeStyles(private val glyphs: String, private val xml: (themeId: String) -> String?) {
    private val styles = HashMap<String, MapStyle>()

    /** No offline map (an online one), or a theme that can't be read: the basic layers. */
    private val basic by lazy { MapStyle(BasicLayers.layers, glyphs) }

    /** The style for [themeId] (null: no offline map). */
    fun styleFor(themeId: String?): MapStyle {
        val id = themeId ?: return basic
        return styles.getOrPut(id) {
            val theme = xml(id) ?: return@getOrPut basic
            runCatching { ThemeStyle.convert(theme) }.getOrNull()
                ?.let { t -> MapStyle(t.layers, glyphs, t.hillshade) { categories -> ThemeStyle.convert(theme, categories) } }
                ?: basic
        }
    }
}
