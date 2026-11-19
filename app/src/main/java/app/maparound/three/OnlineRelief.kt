package app.maparound.three

import android.graphics.BitmapFactory
import android.util.Log
import app.maparound.shared.net.httpGet
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The 3D view's relief where no elevation has been downloaded (see [ReliefTiles]): image tiles from
 * [template] (Terrarium, or Mapbox Terrain-RGB when [mapbox]), fetched when a tile's shape needs
 * them, kept on disk in [dir] (they don't change) and the latest few in memory.
 */
class OnlineRelief(private val dir: File, private val template: String, private val mapbox: Boolean) {
    /** Decoded tiles, the most recently used last. */
    private val memory = object : LinkedHashMap<TileId, ShortArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, ShortArray>?) = size > MEMORY_TILES
    }

    /** Tiles being fetched, so threads asking for the same one wait for it rather than fetch it again. */
    private val fetching = ConcurrentHashMap<TileId, CountDownLatch>()

    /** Tiles that couldn't be had lately (offline, or not on the server), and when. */
    private val failedAt = ConcurrentHashMap<TileId, Long>()

    /** Fetches started by [peek] (the camera's ground, names), off the GL thread. */
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "3d-relief").apply { isDaemon = true } }

    init {
        background.execute { trim() }
    }

    /** The height at [lat], [lon] from the relief tile at [z], fetching it if need be (blocks: call off the GL thread); NaN when it can't be had. */
    fun at(lat: Double, lon: Double, z: Int): Float {
        val t = ReliefTiles.tileAt(lat, lon, z)
        val h = tile(t) ?: return Float.NaN
        return ReliefTiles.sample(h, t, lat, lon)
    }

    /**
     * The height at [lat], [lon] from what's in memory (the finest relief there is), without waiting;
     * NaN when there's none yet (the finest one is then fetched in the background).
     */
    fun peek(lat: Double, lon: Double): Float {
        for (z in ReliefTiles.MAX_ZOOM downTo MIN_PEEK_ZOOM) {
            val t = ReliefTiles.tileAt(lat, lon, z)
            val h = synchronized(memory) { memory[t] } ?: continue
            if (z < ReliefTiles.MAX_ZOOM) wantSoon(ReliefTiles.tileAt(lat, lon, ReliefTiles.MAX_ZOOM))
            return ReliefTiles.sample(h, t, lat, lon)
        }
        wantSoon(ReliefTiles.tileAt(lat, lon, ReliefTiles.MAX_ZOOM))
        return Float.NaN
    }

    fun close() {
        background.shutdownNow()
    }

    private fun wantSoon(t: TileId) {
        if (synchronized(memory) { t in memory } || fetching.containsKey(t) || recentlyFailed(t)) return
        runCatching { background.execute { tile(t) } }
    }

    private fun recentlyFailed(t: TileId) = failedAt[t]?.let { System.currentTimeMillis() - it < RETRY_MS } == true

    /** The tile's heights: from memory, the disk, or the server (once at a time); null when it can't be had now. */
    private fun tile(t: TileId): ShortArray? {
        synchronized(memory) { memory[t] }?.let { return it }
        if (recentlyFailed(t)) return null
        val mine = CountDownLatch(1)
        val other = fetching.putIfAbsent(t, mine)
        if (other != null) {
            other.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return synchronized(memory) { memory[t] }
        }
        try {
            val heights = read(t)
            if (heights == null) failedAt[t] = System.currentTimeMillis() else synchronized(memory) { memory[t] = heights }
            return heights
        } finally {
            fetching.remove(t)
            mine.countDown()
        }
    }

    private fun read(t: TileId): ShortArray? {
        val file = File(dir, "${t.z}/${t.x}/${t.y}.png")
        val bytes = file.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: runCatching { httpGet(ReliefTiles.url(template, t), timeoutMs = FETCH_TIMEOUT_MS.toInt()) }
                .onFailure { Log.w(TAG, "No relief for $t: ${it.message}") }
                .getOrNull()?.also { save(file, it) }
            ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            if (bmp.width != ReliefTiles.SIZE || bmp.height != ReliefTiles.SIZE) return null
            val px = IntArray(ReliefTiles.SIZE * ReliefTiles.SIZE)
            bmp.getPixels(px, 0, ReliefTiles.SIZE, 0, 0, ReliefTiles.SIZE, ReliefTiles.SIZE)
            return ReliefTiles.decode(px, mapbox, t)
        } finally {
            bmp.recycle()
        }
    }

    private fun save(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    /** Keeps the disk's copies within [MAX_DISK_BYTES], the oldest going first. */
    private fun trim() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val TAG = "OnlineRelief"
        const val MEMORY_TILES = 96 // 128 kB each
        const val MIN_PEEK_ZOOM = 7
        const val FETCH_TIMEOUT_MS = 10_000L
        const val RETRY_MS = 60_000L
        const val MAX_DISK_BYTES = 80L shl 20
    }
}
