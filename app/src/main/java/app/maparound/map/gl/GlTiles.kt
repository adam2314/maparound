package app.maparound.map.gl

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.maparound.AppGraph
import app.maparound.map.maplibre.TileService
import app.maparound.shared.net.userAgent
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue

/**
 * The tiles the MapLibre map asks the app for, made on the phone: the shared [TileService] (the
 * offline maps, the built-in world, contours, hill shading, label fonts, the viewshed image), and
 * Android's own raster maps (`raster/<id>/z/x/y.png`).
 *
 * MapLibre fetches everything with OkHttp; [Answer] replies to requests for [HOST] itself, without
 * a network, and gives the app's User-Agent to the online tile servers.
 */
object GlTiles {
    /** Never resolves (.invalid): requests for it never leave the phone. */
    private const val HOST = "tiles.maparound.invalid"
    private const val TAG = "GlTiles"

    /** The tile service the map's style is made with; it also answers fonts and images. */
    lateinit var service: TileService
        private set

    /**
     * Makers of the map's own tiles (offline maps, contours, hill shading), one request each at a
     * time: the readers aren't thread safe, and MapLibre asks from several threads at once. They
     * follow [service]'s maps and terrain.
     */
    private lateinit var workers: ArrayBlockingQueue<TileService>

    // Raster maps are read under this.
    private val rasterLock = Any()

    fun init(context: Context) {
        val assets = context.applicationContext.assets
        service = TileService(demDir = AppGraph.terrain.demDir.path).apply {
            baseUrl = "http://$HOST"
            world = AppGraph.worldTile
            glyphSource = { font, range -> runCatching { assets.open("fonts/$font/$range").use { it.readBytes() } }.getOrNull() }
            extra = ::extra
        }
        val count = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        workers = ArrayBlockingQueue(count)
        repeat(count) {
            workers.put(TileService(demDir = AppGraph.terrain.demDir.path).apply {
                world = AppGraph.worldTile
            })
        }
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor(Answer).build())
        stayConnected(context.applicationContext)
    }

    /**
     * MapLibre holds back every request while the phone has no network, also those for the app's
     * own tiles (they go through its HTTP client to [HOST]), so with no signal the offline map and
     * its contours didn't draw. It's told it's always connected (online tiles just fail
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
                        // Streamed maps: read at once again, and the tiles made without them redone.
                        app.maparound.shared.stream.MapStream.networkBack()
                    }
                },
            )
        }.onFailure { Log.w(TAG, "No network callback", it) }
    }

    /** A request for the map's own tiles, on a free worker brought up to date with [service]. */
    private fun onWorker(path: String): TileService.Response? {
        val w = workers.take()
        try {
            if (w.version != service.version) w.setMaps(service.mapPaths, service.remoteMaps)
            val terrain = service.terrainVersion
            if (terrain != null && w.terrainVersion != terrain) w.refreshTerrain()
            return w.respond(path)
        } finally {
            workers.put(w)
        }
    }

    // ---- requests ------------------------------------------------------------------------------------

    private fun extra(parts: List<String>): TileService.Response? = when (parts.firstOrNull()) {
        // raster/<id>.<revision>/z/x/y.png
        "raster" -> if (parts.size == 5) raster(parts[1].substringBefore('.'), parts[2], parts[3], parts[4]) else null
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

    /** How soon a tile made without a streamed map's data is asked for again, in seconds. */
    private const val RETRY_S = 20

    /**
     * Tiles answered so far that aren't what they'll be (made without a streamed map's data, or
     * failed): a picture drawn while this went up may be missing something, so it isn't kept.
     */
    private val incomplete = java.util.concurrent.atomic.AtomicLong()

    /** See [incomplete]. */
    val incompleteTiles: Long get() = incomplete.get()

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
                    // Fonts and the viewshed: read only, any thread.
                    else -> service.respond(path)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No tile for $path", e)
                incomplete.incrementAndGet()
                null
            }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            return if (answer == null) {
                builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody(null)).build()
            } else {
                if (answer.temporary) incomplete.incrementAndGet()
                // Tile URLs carry a key of their data, so a tile never changes: MapLibre may keep it.
                // One made without a streamed map it couldn't read (no signal) is asked for again soon.
                builder.code(200).message("OK")
                    .header("Cache-Control", if (answer.temporary) "max-age=$RETRY_S" else "max-age=31536000")
                    .body(answer.bytes.toResponseBody(answer.contentType.toMediaType()))
                    .build()
            }
        }
    }
}
