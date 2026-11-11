package app.maparound.map

import app.maparound.USER_AGENT
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.download.tilesource.OnlineTileSource
import java.net.URL

/** An online XYZ raster tile service. `{s}` `{z}` `{x}` `{y}` and `{-y}` (TMS) are substituted. */
data class OnlineSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val maxZoom: Int = 19,
    val attribution: String,
    val subdomains: List<String> = listOf("a", "b", "c"),
    val custom: Boolean = false,
    val description: String = "",
    /** Overlay tiles with transparent background. */
    val transparent: Boolean = false,
)

object TileSources {
    /** Pseudo-source id meaning "render the downloaded offline vector maps". */
    const val OFFLINE_ID = "offline"
    const val DEFAULT_ID = "osm"

    private const val OSM = "© OpenStreetMap contributors"

    val builtIn = listOf(
        OnlineSource(
            id = "osm",
            name = "OpenStreetMap",
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            maxZoom = 19,
            attribution = OSM,
            description = "Standard OSM map",
        ),
        OnlineSource(
            id = "opentopomap",
            name = "OpenTopoMap",
            urlTemplate = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
            maxZoom = 17,
            attribution = "$OSM, SRTM · © OpenTopoMap (CC-BY-SA)",
            description = "Topographic, contour lines",
        ),
        OnlineSource(
            id = "cyclosm",
            name = "CyclOSM",
            urlTemplate = "https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            maxZoom = 19,
            attribution = "$OSM · CyclOSM, OSM France",
            description = "Cycling & outdoor",
        ),
        OnlineSource(
            id = "hot",
            name = "Humanitarian",
            urlTemplate = "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            maxZoom = 19,
            subdomains = listOf("a", "b"),
            attribution = "$OSM · HOT, OSM France",
            description = "Humanitarian OSM style",
        ),
        OnlineSource(
            id = "esri_imagery",
            name = "Satellite",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 19,
            attribution = "Imagery © Esri, Maxar, Earthstar Geographics, GIS User Community",
            description = "Esri World Imagery (aerial / satellite)",
        ),
    )

    /** Marked hiking routes as a transparent overlay (online only). */
    val trailsOverlay = OnlineSource(
        id = "wmt_hiking",
        name = "Marked trails",
        urlTemplate = "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png",
        maxZoom = 18,
        attribution = "trails © waymarkedtrails.org",
        transparent = true,
    )

    fun find(id: String, custom: List<OnlineSource>): OnlineSource? =
        builtIn.firstOrNull { it.id == id } ?: custom.firstOrNull { it.id == id }

    /** Returns an error message, or null when the template is usable. */
    fun validateTemplate(url: String): String? = when {
        !url.startsWith("https://") && !url.startsWith("http://") -> "URL must start with https://"
        "{z}" !in url || "{x}" !in url || ("{y}" !in url && "{-y}" !in url) -> "URL must contain {z}, {x} and {y}"
        else -> null
    }

    /** The URL of one XYZ tile ({s} rotates through the subdomains, {-y} is the TMS row). */
    fun tileUrl(source: OnlineSource, z: Int, x: Int, y: Int): String {
        val subs = source.subdomains
        val s = if (subs.isEmpty()) "" else subs[Math.floorMod(x + y, subs.size)]
        return source.urlTemplate
            .replace("{s}", s)
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{-y}", ((1 shl z) - 1 - y).toString())
            .replace("{y}", y.toString())
    }

    fun hostOf(template: String, subdomains: List<String>): String =
        Regex("^https?://([^/:]+)").find(template.replace("{s}", subdomains.firstOrNull() ?: "a"))
            ?.groupValues?.get(1) ?: "localhost"
}

/** Lets mapsforge's TileDownloadLayer fetch from any XYZ URL template. */
class UrlTemplateTileSource(private val source: OnlineSource) :
    OnlineTileSource(arrayOf(TileSources.hostOf(source.urlTemplate, source.subdomains)), 443) {

    init {
        setName(source.id)
        setAlpha(source.transparent)
        setZoomLevelMin(0)
        setZoomLevelMax(source.maxZoom.coerceIn(1, 22).toByte())
        setParallelRequestsLimit(4)
        setTileSize(256)
        setUserAgent(USER_AGENT)
    }

    override fun getTileUrl(tile: Tile): URL = URL(TileSources.tileUrl(source, tile.zoomLevel.toInt(), tile.tileX, tile.tileY))
}
