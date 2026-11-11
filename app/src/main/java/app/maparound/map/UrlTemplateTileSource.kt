package app.maparound.map

import app.maparound.USER_AGENT
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.download.tilesource.OnlineTileSource
import java.net.URL

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
