package app.maparound.map.gl

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.maparound.AppGraph
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.TileService
import app.maparound.shared.mvt.MvtTile
import app.maparound.shared.net.userAgent
import app.maparound.tools.Viewshed
import app.maparound.trails.TrailTags
import app.maparound.trails.TrailIndex
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.mapsforge.core.model.BoundingBox
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.atan
import kotlin.math.PI

/**
 * The tiles the MapLibre map asks the app for, made on the phone: the shared [TileService] (the
 * offline maps, the built-in world, contours, hill shading, label fonts), and Android's own: raster
 * maps (`raster/<id>/z/x/y.png`), the downloaded marked trails (`trails/<version>/z/x/y.pbf`, and
 * their cliffs and ridges in the map's own tiles) and the viewshed image (`viewshed/<key>.png`).
 *
 * MapLibre fetches everything with OkHttp; [Answer] replies to requests for [HOST] itself, without
 * a network, and gives the app's User-Agent to the online tile servers.
 */
object GlTiles {
    /** Never resolves (.invalid): requests for it never leave the phone. */
    private const val HOST = "tiles.maparound.invalid"
    private const val TAG = "GlTiles"

    /** The tile service the map's style is made with; it also answers fonts, trails and images. */
    lateinit var service: TileService
        private set

    /**
     * Makers of the map's own tiles (offline maps, contours, hill shading), one request each at a
     * time: the readers aren't thread safe, and MapLibre asks from several threads at once. They
     * follow [service]'s maps and terrain.
     */
    private lateinit var workers: ArrayBlockingQueue<TileService>

    // The trail index is built under this; raster maps are read under [rasterLock].
    private val lock = Any()
    private val rasterLock = Any()

    fun init(context: Context) {
        val assets = context.applicationContext.assets
        service = TileService(demDir = AppGraph.terrain.demDir.path).apply {
            baseUrl = "http://$HOST"
            world = AppGraph.worldTile
            glyphSource = { font, range -> runCatching { assets.open("fonts/$font/$range").use { it.readBytes() } }.getOrNull() }
            extra = ::extra
            osmExtra = ::trailFeatures
        }
        val count = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        workers = ArrayBlockingQueue(count)
        repeat(count) {
            workers.put(TileService(demDir = AppGraph.terrain.demDir.path).apply {
                world = AppGraph.worldTile
                osmExtra = ::trailFeatures
            })
        }
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor(Answer).build())
        stayConnected(context.applicationContext)
    }

    /**
     * MapLibre holds back every request while the phone has no network, also those for the app's
     * own tiles (they go through its HTTP client to [HOST]), so with no signal the offline map,
     * contours and trails didn't draw. It's told it's always connected (online tiles just fail
     * then); and when a network comes up, it's told again, which makes it retry them at once.
     */
    private fun stayConnected(context: Context) {
        MapLibre.setConnected(true)
        val main = Handler(Looper.getMainLooper())
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        main.post { MapLibre.setConnected(true) }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "No network callback", it) }
    }

    /** A request for the map's own tiles, on a free worker brought up to date with [service]. */
    private fun onWorker(path: String): TileService.Response? {
        val w = workers.take()
        try {
            if (w.version != service.version) w.setMaps(service.mapPaths)
            val terrain = service.terrainVersion
            if (terrain != null && w.terrainVersion != terrain) w.refreshTerrain()
            return w.respond(path)
        } finally {
            workers.put(w)
        }
    }

    // ---- trails ------------------------------------------------------------------------------------

    @Volatile
    private var trailIndex: TrailIndex? = null
    private var trailKey: Int = -1

    /**
     * The downloaded trail data, [version] (it changes with the data), in the tiles: the trail
     * lines when [routes], and the cliffs and ridges in any case, for styles that draw them.
     */
    fun setTrails(version: Int, routes: Boolean, drawsTerrain: Boolean) {
        val index = synchronized(lock) {
            if (version != trailKey) {
                trailKey = version
                trailIndex = TrailIndex(AppGraph.trails.dir).takeUnless { it.isEmpty }
            }
            trailIndex
        }
        service.trailsVersion = if (index != null && routes && drawsTerrain && index.wayCount > 0) "$version" else null
        service.osmExtraVersion = if (index != null && drawsTerrain && index.featureCount > 0) "t$version" else null
    }

    /** The trails' cliffs, arêtes, ridges and earth banks, in the map's own tiles (as in OSM). */
    private fun trailFeatures(t: MvtTile, z: Int, x: Int, y: Int) {
        if (service.osmExtraVersion == null || z + 1 < TrailTags.FEATURE_MIN_ZOOM) return
        val index = trailIndex ?: return
        val line = t.layer("line")
        index.queryFeatures(tileBox(z, x, y)) { f -> line.line(deg(f.coords), mapOf("natural" to f.kind.tag)) }
    }

    /** The marked trail lines, one per waymark colour ([TrailTags]). */
    private fun trailTile(z: Int, x: Int, y: Int): ByteArray {
        val t = MvtTile(z, x, y)
        val index = trailIndex ?: return t.encode()
        val layer = t.layer("trails")
        index.query(tileBox(z, x, y)) { way, routes ->
            val coords = deg(way.coords)
            for (tags in TrailTags.lines(routes)) layer.line(coords, tags)
        }
        return t.encode()
    }

    // ---- requests ------------------------------------------------------------------------------------

    private fun extra(parts: List<String>): TileService.Response? = when (parts.firstOrNull()) {
        "raster" -> if (parts.size == 5) raster(parts[1], parts[2], parts[3], parts[4]) else null
        "trails" -> if (parts.size == 5) zxy(parts, 2)?.let { (z, x, y) -> TileService.Response(trailTile(z, x, y), PBF) } else null
        "viewshed" -> service.viewshed?.takeIf { parts.size == 2 && parts[1] == "${MapStyle.viewshedKey(it)}.png" }
            ?.let { TileService.Response(png(viewshedBitmap(it)), "image/png") }
        else -> null
    }

    /** A tile of a raster map (RMAP, MBTiles, SQLiteDB), 256 px. */
    private fun raster(id: String, zs: String, xs: String, ys: String): TileService.Response? {
        val (z, x, y) = zxy(listOf("", zs, xs, ys), 1) ?: return null
        val renderer = AppGraph.rasters.renderer(id) ?: return null
        val size = 256
        val pixels = IntArray(size * size)
        if (!renderer.render(z, x, y, size, pixels)) return null
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return TileService.Response(png(bmp).also { bmp.recycle() }, "image/png")
    }

    private fun viewshedBitmap(vs: Viewshed): Bitmap {
        val visible = android.graphics.Color.argb(80, 67, 160, 71)
        val hidden = android.graphics.Color.argb(115, 198, 40, 40)
        val pixels = IntArray(vs.size * vs.size) { i -> when (vs.cells[i].toInt()) { 1 -> visible; 0 -> hidden; else -> 0 } }
        return Bitmap.createBitmap(pixels, vs.size, vs.size, Bitmap.Config.ARGB_8888)
    }

    private fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    /** z/x/y from path parts (y with its extension), starting at [from]. */
    private fun zxy(parts: List<String>, from: Int): Triple<Int, Int, Int>? {
        val z = parts.getOrNull(from)?.toIntOrNull() ?: return null
        val x = parts.getOrNull(from + 1)?.toIntOrNull() ?: return null
        val y = parts.getOrNull(from + 2)?.substringBefore('.')?.toIntOrNull() ?: return null
        if (z !in 0..22 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        return Triple(z, x, y)
    }

    private fun tileBox(z: Int, x: Int, y: Int): BoundingBox {
        val n = 1 shl z
        fun lat(row: Int) = atan(kotlin.math.sinh(PI * (1 - 2.0 * row / n))) * 180 / PI
        return BoundingBox(lat(y + 1), x * 360.0 / n - 180, lat(y), (x + 1) * 360.0 / n - 180)
    }

    private fun deg(c: IntArray) = DoubleArray(c.size) { c[it] / 1e6 }

    private const val PBF = "application/x-protobuf"

    /** Answers MapLibre's requests for the app's own tiles; passes the rest on with the app's User-Agent. */
    private object Answer : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.host != HOST) {
                return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
            }
            val path = request.url.encodedPath
            val answer = try {
                when (path.trim('/').substringBefore('/')) {
                    "osm", "contours", "dem" -> onWorker(path)
                    "raster" -> synchronized(rasterLock) { service.respond(path) }
                    // Fonts, trails and the viewshed: read only, any thread.
                    else -> service.respond(path)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No tile for $path", e)
                null
            }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            return if (answer == null) {
                builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody(null)).build()
            } else {
                // Tile URLs carry a key of their data, so a tile never changes: MapLibre may keep it.
                builder.code(200).message("OK")
                    .header("Cache-Control", "max-age=31536000")
                    .body(answer.bytes.toResponseBody(answer.contentType.toMediaType()))
                    .build()
            }
        }
    }
}
