package app.maparound.data

// Plain Kotlin: where elevation data comes from, and the addresses the user can put instead.
// Unit-tested.

/**
 * The elevation data's sources. The 1° tiles (contour lines, hill shading, line of sight, the
 * viewshed and the apps' 3D view) come from an address template with `{folder}` (`N47`) and
 * `{name}` (`N47E009`), gzipped when it ends in `.gz`; the web app's 3D view reads image tiles
 * (`{z}/{x}/{y}`), Terrarium or Mapbox Terrain-RGB. Null templates mean the defaults: Terrain
 * Tiles on AWS Open Data.
 */
object ElevationSources {
    const val DEFAULT_TILES = "https://elevation-tiles-prod.s3.amazonaws.com/skadi/{folder}/{name}.hgt.gz"
    const val DEFAULT_3D = "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png"

    /** The default's page (what it is, its sources and licence). */
    const val DEFAULT_SITE = "https://registry.opendata.aws/terrain-tiles/"

    /** [text] as a template for the 1° tiles, or null if it can't be one (http(s), with `{name}`). */
    fun tilesTemplate(text: String): String? {
        val t = text.trim()
        return t.takeIf { isWebAddress(it) && "{name}" in it }
    }

    /** [text] as a template for the 3D view's image tiles, or null (http(s), with `{z}`, `{x}` and `{y}`). */
    fun tiles3dTemplate(text: String): String? {
        val t = text.trim()
        return t.takeIf { isWebAddress(it) && "{z}" in it && "{x}" in it && "{y}" in it }
    }

    /** The address of the tile [name] (`N47E009`) from [template] (null: the default). */
    fun tileUrl(template: String?, name: String): String =
        (template ?: DEFAULT_TILES).replace("{folder}", name.take(3)).replace("{name}", name)

    /** Whether the file at [url] is gzipped (by its ending, before any query). */
    fun isGzip(url: String): Boolean = url.substringBefore('?').substringBefore('#').endsWith(".gz", ignoreCase = true)

    /** Who to credit for [template]'s data: null for the default (its own credit), else the server's name. */
    fun customHost(template: String?): String? =
        template?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    /** The site to open for [template]: the default's page, else the server's home. */
    fun site(template: String?): String =
        if (template == null) DEFAULT_SITE else template.substringBefore("://") + "://" + template.substringAfter("://").substringBefore('/') + "/"

    private fun isWebAddress(t: String) =
        (t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) &&
            t.substringAfter("://").substringBefore('/').isNotEmpty() && t.none { it.isWhitespace() }
}

/** The web app's 3D terrain tiles: an address template ([ElevationSources.tiles3dTemplate]) and their encoding. */
data class Terrain3dSource(val template: String, val mapbox: Boolean) {
    /** As kept in the settings. */
    fun encode(): String = (if (mapbox) "mapbox " else "terrarium ") + template

    companion object {
        fun parse(text: String?): Terrain3dSource? {
            val t = text?.trim() ?: return null
            val mapbox = t.startsWith("mapbox ")
            val template = ElevationSources.tiles3dTemplate(t.substringAfter(' ')) ?: return null
            return Terrain3dSource(template, mapbox)
        }
    }
}
