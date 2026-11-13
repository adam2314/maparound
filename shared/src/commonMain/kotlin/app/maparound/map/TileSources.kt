package app.maparound.map

import app.maparound.i18n.T

// Plain Kotlin (Android and iOS): the online map tile services.

/** An online XYZ raster tile service. `{s}` `{z}` `{x}` `{y}` and `{-y}` (TMS) are substituted. */
data class OnlineSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val maxZoom: Int = 19,
    val attribution: String,
    val subdomains: List<String> = listOf("a", "b", "c"),
    val custom: Boolean = false,
    /** What it is; the built-in sources' come from the texts ([TileSources.description]). */
    val description: String = "",
    /** Overlay tiles with transparent background. */
    val transparent: Boolean = false,
    val kind: Kind = Kind.STREET,
) {
    /** What the map shows (for its icon). */
    enum class Kind { STREET, TOPO, IMAGERY }
}

object TileSources {
    /** Pseudo-source id meaning "render the downloaded offline vector maps". */
    const val OFFLINE_ID = "offline"
    const val DEFAULT_ID = "osmfr"

    private const val OSM = "© OpenStreetMap contributors"
    private const val ESRI = "Tiles © Esri and its data providers, the GIS User Community"

    val builtIn = listOf(
        OnlineSource(
            id = "osmfr",
            name = "OpenStreetMap France",
            urlTemplate = "https://{s}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png",
            maxZoom = 20,
            attribution = "$OSM · tiles OSM France",
        ),
        OnlineSource(
            id = "osmde",
            name = "OpenStreetMap Germany",
            urlTemplate = "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
            maxZoom = 19,
            attribution = "$OSM · tiles openstreetmap.de",
        ),
        OnlineSource(
            id = "opentopomap",
            name = "OpenTopoMap",
            urlTemplate = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
            maxZoom = 17,
            attribution = "$OSM, SRTM · © OpenTopoMap (CC-BY-SA)",
            kind = OnlineSource.Kind.TOPO,
        ),
        OnlineSource(
            id = "cyclosm",
            name = "CyclOSM",
            urlTemplate = "https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            maxZoom = 19,
            attribution = "$OSM · CyclOSM, OSM France",
        ),
        OnlineSource(
            id = "hot",
            name = "Humanitarian",
            urlTemplate = "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            maxZoom = 19,
            subdomains = listOf("a", "b"),
            attribution = "$OSM · HOT, OSM France",
        ),
        OnlineSource(
            id = "esri_topo",
            name = "Esri World Topo",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 19,
            attribution = ESRI,
            kind = OnlineSource.Kind.TOPO,
        ),
        OnlineSource(
            id = "esri_street",
            name = "Esri World Street",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 19,
            attribution = ESRI,
        ),
        OnlineSource(
            id = "esri_imagery",
            name = "Satellite",
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 19,
            attribution = "Imagery © Esri, Maxar, Earthstar Geographics, GIS User Community",
            kind = OnlineSource.Kind.IMAGERY,
        ),
        // National mapping agencies' open data: their own country only.
        OnlineSource(
            id = "swisstopo",
            name = "swisstopo",
            urlTemplate = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg",
            maxZoom = 18,
            attribution = "© swisstopo",
            kind = OnlineSource.Kind.TOPO,
        ),
        OnlineSource(
            id = "basemap_at",
            name = "basemap.at",
            urlTemplate = "https://mapsneu.wien.gv.at/basemap/geolandbasemap/normal/google3857/{z}/{y}/{x}.png",
            maxZoom = 19,
            attribution = "basemap.at (CC BY 4.0)",
        ),
        OnlineSource(
            id = "kartverket",
            name = "Kartverket Topo",
            urlTemplate = "https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/{z}/{y}/{x}.png",
            maxZoom = 18,
            attribution = "© Kartverket (CC BY 4.0)",
            kind = OnlineSource.Kind.TOPO,
        ),
        OnlineSource(
            id = "ign_plan",
            name = "Plan IGN",
            urlTemplate = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2" +
                "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            maxZoom = 19,
            attribution = "© IGN – Géoplateforme",
        ),
        OnlineSource(
            id = "ign_ortho",
            name = "IGN aerial",
            urlTemplate = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS" +
                "&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            maxZoom = 19,
            attribution = "© IGN – Géoplateforme",
            kind = OnlineSource.Kind.IMAGERY,
        ),
        OnlineSource(
            id = "usgs_topo",
            name = "USGS Topo",
            urlTemplate = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 16,
            attribution = "USGS The National Map",
            kind = OnlineSource.Kind.TOPO,
        ),
    )

    /**
     * Sources that are gone, and what replaces them. tile.openstreetmap.org refuses this app
     * ("not following the tile usage policy"); OSM France shows the same data.
     */
    private val retired = mapOf("osm" to DEFAULT_ID)

    /** A saved source id as it is now ([retired] ones replaced). */
    fun current(id: String): String = retired[id] ?: id

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

    /** A source's name as people see it: the built-in ones that aren't names of their own, in the app's language. */
    fun title(source: OnlineSource): String = when (source.id) {
        "hot" -> T.map.srcHumanitarian
        "esri_imagery" -> T.map.srcSatellite
        "ign_ortho" -> T.map.srcIgnAerial
        else -> source.name
    }

    /** What a source is, in the app's language for the built-in ones. */
    fun description(source: OnlineSource): String = when (source.id) {
        "osmfr" -> T.map.srcOsmFrance
        "osmde" -> T.map.srcOsmGermany
        "opentopomap" -> T.map.srcOpenTopoMap
        "cyclosm" -> T.map.srcCyclOsm
        "hot" -> T.map.srcHot
        "esri_topo" -> T.map.srcEsriTopo
        "esri_street" -> T.map.srcEsriStreet
        "esri_imagery" -> T.map.srcEsriImagery
        "swisstopo" -> T.map.srcSwisstopo
        "basemap_at" -> T.map.srcBasemapAt
        "kartverket" -> T.map.srcKartverket
        "ign_plan" -> T.map.srcIgnPlan
        "ign_ortho" -> T.map.srcIgnOrtho
        "usgs_topo" -> T.map.srcUsgsTopo
        else -> source.description
    }

    /** Returns an error message, or null when the template is usable. */
    fun validateTemplate(url: String): String? = when {
        !url.startsWith("https://") && !url.startsWith("http://") -> T.map.urlMustStartHttps
        "{z}" !in url || "{x}" !in url || ("{y}" !in url && "{-y}" !in url) -> T.map.urlMustHaveZxy
        else -> null
    }

    /** The URL of one XYZ tile ({s} rotates through the subdomains, {-y} is the TMS row). */
    fun tileUrl(source: OnlineSource, z: Int, x: Int, y: Int): String {
        val subs = source.subdomains
        val s = if (subs.isEmpty()) "" else subs[((x + y) % subs.size + subs.size) % subs.size]
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
