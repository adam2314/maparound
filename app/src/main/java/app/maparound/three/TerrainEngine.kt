package app.maparound.three

import android.graphics.Bitmap
import android.util.Log
import app.maparound.AppGraph
import app.maparound.data.MapNames
import app.maparound.terrain.DemIndex
import app.maparound.tools.Elevation
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import app.maparound.data.file

/**
 * Loads 3D tiles in the background, most important first: the map picture and terrain mesh for
 * each tile, and buildings (from the offline maps) for nearby street-level tiles. Results queue up
 * for the GL thread to upload.
 */
class TerrainEngine(val frame: LocalFrame, val textures: TileTextures, private val onReady: () -> Unit) {
    class LoadedTile(val id: TileId, val vertices: FloatArray, val n: Int, val bitmap: Bitmap?)
    class LoadedBuildings(val id: TileId, val vertices: FloatArray, val count: Int)

    val readyTiles = ConcurrentLinkedQueue<LoadedTile>()
    val readyBuildings = ConcurrentLinkedQueue<LoadedBuildings>()

    private val dem: DemIndex? = DemIndex(AppGraph.terrain.demDir.path).takeUnless { it.isEmpty }
    val hasTerrain: Boolean get() = dem != null
    val elevation = Elevation { lat, lon -> dem?.elevation(lat, lon) ?: Float.NaN }

    private val mapFiles = AppGraph.mapFiles.files.value.map { it.file }.filterNot { MapNames.isWorld(it.name) }
    val hasBuildings: Boolean get() = mapFiles.isNotEmpty()
    private var buildingStores: List<MapFile>? = null // only touched by the building worker

    private val tileQueue = WorkQueue<TileId>("3d-tiles", textures.threads, ::loadTile)
    private val buildingQueue = WorkQueue<TileId>("3d-buildings", 1, ::loadBuildings)

    /** Ground height (no exaggeration), 0 where unknown. */
    fun groundAt(lat: Double, lon: Double): Float = elevation.at(lat, lon).takeUnless { it.isNaN() } ?: 0f

    /** What the view needs now: tile → priority (lower loads first). Replaces the previous wish list. */
    fun want(tiles: Map<TileId, Double>, buildings: Map<TileId, Double>) {
        tileQueue.setWanted(tiles)
        buildingQueue.setWanted(buildings)
    }

    /** The GL side dropped a tile; it may be loaded again later. */
    fun forgetTile(id: TileId) = tileQueue.forget(id)
    fun forgetBuildings(id: TileId) = buildingQueue.forget(id)

    val pending: Int get() = tileQueue.pending + buildingQueue.pending

    fun close() {
        tileQueue.close()
        buildingQueue.close()
        runCatching { textures.close() }
        buildingStores?.forEach { runCatching { it.close() } }
    }

    private fun loadTile(id: TileId) {
        val n = if (id.z >= 12) 33 else 17
        val (vertices, _) = TileMesh.build(elevation, id, frame, n)
        val bitmap = textures.load(id) ?: if (textures is OnlineTileTextures) throw IllegalStateException("no tile") else null
        readyTiles += LoadedTile(id, vertices, n, bitmap)
        onReady()
    }

    /** Buildings whose middle lies in this zoom-16 tile. */
    private fun loadBuildings(id: TileId) {
        val stores = buildingStores ?: mapFiles.mapNotNull { runCatching { MapFile(it) }.getOrNull() }.also { buildingStores = it }
        val tile = Tile(id.x, id.y, id.z.toByte(), 256)
        val footprints = ArrayList<Footprint>()
        val seen = HashSet<Long>()
        for (store in stores) {
            if (!store.supportsTile(tile)) continue
            val data = runCatching { store.readMapData(tile) }.getOrNull() ?: continue
            for (way in data.ways) {
                var building: String? = null
                val tags = HashMap<String, String>()
                for (t in way.tags) {
                    tags[t.key] = t.value
                    if (t.key == "building") building = t.value
                }
                if (building == null || building == "no") continue
                val outer = way.latLongs.firstOrNull() ?: continue
                if (outer.size < 4) continue
                var cLat = 0.0
                var cLon = 0.0
                for (p in outer) { cLat += p.latitude; cLon += p.longitude }
                cLat /= outer.size
                cLon /= outer.size
                if (cLat !in id.south..id.north || cLon !in id.west..id.east) continue
                val key = outer[0].latitude.toBits() * 31 + outer[0].longitude.toBits() * 17 + outer.size
                if (!seen.add(key)) continue
                footprints += Footprint(
                    way.latLongs.map { ring ->
                        FloatArray(ring.size * 2).also { a ->
                            ring.forEachIndexed { i, p -> a[2 * i] = frame.x(p.longitude); a[2 * i + 1] = frame.y(p.latitude) }
                        }
                    },
                    Buildings.heightOf(tags),
                )
            }
        }
        val vertices = Buildings.extrude(footprints) { x, y -> groundAt(frame.lat(y.toDouble()), frame.lon(x.toDouble())) }
        readyBuildings += LoadedBuildings(id, vertices, footprints.size)
        onReady()
    }

    /**
     * A wish list worked through by a few threads: each picks the lowest-priority-number key that
     * isn't loaded or loading. Failed keys are retried after a pause.
     */
    private class WorkQueue<K>(name: String, threads: Int, private val work: (K) -> Unit) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var wanted: Map<K, Double> = emptyMap()
        private val busy = HashSet<K>()
        private val done = HashSet<K>()
        private val failedAt = HashMap<K, Long>()
        @Volatile private var running = true

        init {
            repeat(threads) { i ->
                Thread({ loop() }, "$name-$i").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                    start()
                }
            }
        }

        fun setWanted(map: Map<K, Double>) = lock.withLock {
            wanted = map
            changed.signalAll()
        }

        fun forget(key: K) = lock.withLock { done -= key }

        val pending: Int get() = lock.withLock { busy.size + wanted.keys.count { it !in done && it !in busy } }

        fun close() {
            running = false
            lock.withLock { changed.signalAll() }
        }

        private fun next(): K? = lock.withLock {
            while (running) {
                val now = System.currentTimeMillis()
                var best: K? = null
                var bestP = Double.MAX_VALUE
                for ((k, p) in wanted) {
                    if (p < bestP && k !in done && k !in busy && (failedAt[k]?.let { now - it > 15_000 } != false)) {
                        best = k
                        bestP = p
                    }
                }
                if (best != null) {
                    busy += best
                    return best
                }
                changed.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            null
        }

        private fun loop() {
            while (running) {
                val key = next() ?: return
                var ok = false
                try {
                    work(key)
                    ok = true
                } catch (e: Throwable) {
                    Log.w("TerrainEngine", "Loading $key failed: ${e.message}")
                } finally {
                    lock.withLock {
                        busy -= key
                        if (ok) {
                            done += key
                            failedAt -= key
                        } else {
                            failedAt[key] = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }
}
