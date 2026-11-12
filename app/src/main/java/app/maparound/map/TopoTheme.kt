package app.maparound.map

/**
 * MapAround's hiking style is assets/themes/topo.xml (see [AndroidMapThemes]). Symbols
 * referenced as `jar:` resolve against the mapsforge-themes library, like the built-in themes.
 */
object TopoTheme {
    /**
     * A theme for drawing over satellite imagery or a raster map: the background is transparent
     * and area fills (land use, water, buildings…) are dropped, leaving roads, paths, trails,
     * contours, symbols and labels.
     */
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
