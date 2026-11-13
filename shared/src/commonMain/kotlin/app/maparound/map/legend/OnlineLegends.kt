package app.maparound.map.legend

import app.maparound.map.OnlineSource

/** The online maps' legends: their makers publish them on their sites; MapAround links there. */
object OnlineLegends {
    sealed interface Info {
        /** The legend's page; [standardStyle]: a map drawn like OpenStreetMap's standard one, so its key. */
        class Link(val url: String, val standardStyle: Boolean = false) : Info

        /** Satellite or aerial photos: nothing to explain. */
        data object Imagery : Info

        /** No legend MapAround knows of. */
        data object None : Info
    }

    /** OpenStreetMap's standard map key (the wiki's, by feature). */
    const val STANDARD_KEY = "https://wiki.openstreetmap.org/wiki/Standard_tile_layer/Key"

    /** Pages checked to answer (Nov 2026). */
    private val PAGES = mapOf(
        "opentopomap" to "https://opentopomap.org/about#legende",
        "cyclosm" to "https://www.cyclosm.org/legend.html",
        "hot" to "https://wiki.openstreetmap.org/wiki/Humanitarian_map_style",
        "usgs_topo" to "https://pubs.usgs.gov/gip/TopographicMapSymbols/topomapsymbols.pdf",
    )

    /** OpenStreetMap's own style or close to it (the French and German servers' variants). */
    private val STANDARD = setOf("osmfr", "osmde")

    fun of(source: OnlineSource): Info = when {
        source.kind == OnlineSource.Kind.IMAGERY -> Info.Imagery
        source.id in STANDARD -> Info.Link(STANDARD_KEY, standardStyle = true)
        else -> PAGES[source.id]?.let { Info.Link(it) } ?: Info.None
    }
}
