import app.maparound.terrain.ContourDataStore
import app.maparound.terrain.DemIndex
import okio.buffer
import okio.source
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.awt.graphics.AwtGraphicFactory
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading
import org.mapsforge.map.layer.hills.DemFolderFS
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.imageio.ImageIO

/** Same contract as the app's TopoTheme: XML from a file, symbols from the mapsforge-themes jar. */
class FileTheme(private val file: File) : XmlRenderTheme {
    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = null
    override fun getRelativePathPrefix(): String = "/assets/"
    override fun getRenderThemeAsStream(): InputStream = FileInputStream(file)
    override fun getResourceProvider(): XmlThemeResourceProvider? = null
    override fun setMenuCallback(menuCallback: XmlRenderThemeMenuCallback?) = Unit
    override fun setResourceProvider(resourceProvider: XmlThemeResourceProvider?) = Unit
}

/**
 * Usage (paths are examples):
 *   prep <rawHgtDir> <outDir> high|std [mapsDir]   - process raw .hgt files like the app does
 *   trailprep <overpass.xml> <out.trails>             - convert an Overpass trail download
 *   <theme.xml> <mapsDir> <demDir|-> <outDir> [trails=<dir>] name:lat:lon:zoom[:cols:rows] ...
 *                                                  - render PNG mosaics ("-" = no terrain)
 */
fun main(args: Array<String>) {
    if (args[0] == "trailprep") { // trailprep overpass.xml out.trails
        val t0 = System.nanoTime()
        val cell = File(args[1]).source().buffer().use { app.maparound.trails.TrailCellIO.parseOverpass(it) }
        app.maparound.trails.TrailCellIO.write(cell, args[2])
        println("trailprep: ${cell.routes.size} routes, ${cell.ways.size} ways -> ${File(args[2]).length()} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
        System.exit(0)
    }
    if (args[0] == "prep") { // prep rawDir outDir high|std [mapsDir]
        val outDir = File(args[2]).apply { mkdirs() }
        val high = args[3] == "high"
        val maps = args.getOrNull(4)?.let { dir ->
            MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).also { s ->
                File(dir).listFiles { f -> f.name.endsWith(".map") }!!.forEach { s.addMapDataStore(MapFile(it), false, false) }
            }
        }
        File(args[1]).listFiles { f -> f.name.endsWith(".hgt") }!!.forEach { f ->
            val (lat, lon) = app.maparound.terrain.DemIndex.parseName(f.name)!!
            val size = app.maparound.terrain.HgtProcessor.sizeForLength(f.length())!!
            val t0 = System.nanoTime()
            val outSize = app.maparound.terrain.HgtProcessor.outputSize(size, high)
            val mask = maps?.let { app.maparound.terrain.CoastMask.build(it, lat, lon, outSize) }
            val t1 = System.nanoTime()
            f.source().use {
                app.maparound.terrain.HgtProcessor.convert(
                    it, size, File(outDir, f.name).path, high,
                    app.maparound.terrain.HgtProcessor.keepsNegativeHeights(lat, lon), mask,
                )
            }
            println(
                "prep ${f.name} -> ${File(outDir, f.name).length()} bytes; mask ${mask?.cardinality() ?: 0} sea px " +
                    "in ${(t1 - t0) / 1_000_000} ms, convert ${(System.nanoTime() - t1) / 1_000_000} ms",
            )
        }
        System.exit(0)
    }
    val themeFile = File(args[0])
    val maps = File(args[1]).listFiles { f -> f.name.endsWith(".map") }!!.sorted()
    val demDir = File(args[2])
    val out = File(args[3]).apply { mkdirs() }
    val withTerrain = args[2] != "-"

    val gf = AwtGraphicFactory.INSTANCE
    DisplayModel.setDeviceScaleFactor(2f) // like a typical phone screen
    val displayModel = DisplayModel()
    val themeFuture = RenderThemeFuture(gf, FileTheme(themeFile), displayModel)
    themeFuture.run()
    themeFuture.get() // surfaces theme parse errors

    // Same wiring as the app's MapController: vector maps, plus contours that use the maps' coastline.
    val vector = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
    maps.forEach { vector.addMapDataStore(MapFile(it), false, false) }
    val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
    store.addMapDataStore(vector, false, false)
    var hills: HillsRenderConfig? = null
    if (withTerrain) {
        val dem = DemIndex(demDir.path)
        store.addMapDataStore(ContourDataStore(dem, vector), false, false)
        hills = HillsRenderConfig(
            MemoryCachingHgtReaderTileSource(DemFolderFS(demDir), AdaptiveClasyHillShading(true), gf),
        ).also { it.indexOnThread() }
    }
    val renderer = DatabaseRenderer(store, gf, InMemoryTileCache(256), TileBasedLabelStore(256), true, false, hills)
    val tileSize = displayModel.tileSize

    // Optional "trails=<dir>" argument adds marked hiking trails.
    args.drop(4).firstOrNull { it.startsWith("trails=") }?.let { opt ->
        val index = app.maparound.trails.TrailIndex(File(opt.removePrefix("trails=")))
        store.addMapDataStore(app.maparound.trails.TrailDataStore(index), false, false)
        println("trails: ${index.wayCount} ways")
    }
    for (view in args.drop(4).filterNot { it.startsWith("trails=") }) {
        val p = view.split(':')
        val name = p[0]
        val lat = p[1].toDouble()
        val lon = p[2].toDouble()
        val zoom = p[3].toByte()
        val cols = p.getOrNull(4)?.toInt() ?: 3
        val rows = p.getOrNull(5)?.toInt() ?: 2
        val cx = MercatorProjection.longitudeToTileX(lon, zoom)
        val cy = MercatorProjection.latitudeToTileY(lat, zoom)
        val img = BufferedImage(cols * tileSize, rows * tileSize, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val started = System.nanoTime()
        for (r in 0 until rows) for (c in 0 until cols) {
            val tile = Tile(cx - cols / 2 + c, cy - rows / 2 + r, zoom, tileSize)
            val job = RendererJob(tile, store, themeFuture, displayModel, 1f, false, false)
            val bmp = renderer.executeJob(job)
            val bytes = ByteArrayOutputStream().also { bmp.compress(it) }.toByteArray()
            g.drawImage(ImageIO.read(bytes.inputStream()), c * tileSize, r * tileSize, null)
        }
        g.dispose()
        val ms = (System.nanoTime() - started) / 1_000_000
        val file = File(out, "$name.png")
        ImageIO.write(img, "png", file)
        println("$name z$zoom ${cols}x$rows tiles in ${ms} ms -> ${file.name}")
    }
    hills?.interruptAndDestroy()
    store.close()
    System.exit(0)
}
