package app.maparound.three

import android.graphics.Bitmap
import android.util.Log
import app.maparound.AppGraph
import app.maparound.data.MapNames
import app.maparound.terrain.DemIndex
import app.maparound.tools.Elevation
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import app.maparound.data.file

/**
 * Loads 3D tiles in the background, most important first: the map picture and terrain mesh (on
 * the globe) for each tile, buildings (from the offline maps) for nearby street-level tiles, and
 * the names to stand up over the terrain. Results queue up for the GL thread to upload.
 */
class TerrainEngine(val textures: TileTextures, private val onReady: () -> Unit) {
    class LoadedTile(val id: TileId, val mesh: GlobeTileMesh, val bitmap: Bitmap?)

    /** Buildings of a zoom-16 tile: vertices in metres east/north/up of [center] (see [Buildings]). */
    class LoadedBuildings(val id: TileId, val center: Vec3, val lat: Double, val lon: Double, val vertices: FloatArray, val count: Int)

    val readyTiles = ConcurrentLinkedQueue<LoadedTile>()
    val readyBuildings = ConcurrentLinkedQueue<LoadedBuildings>()

    private val dem: DemIndex? = DemIndex(AppGraph.terrain.demDir.path).takeUnless { it.isEmpty }
    val hasTerrain: Boolean get() = dem != null
    val elevation = Elevation { lat, lon -> dem?.elevation(lat, lon) ?: Float.NaN }

    private val mapFiles = AppGraph.mapFiles.files.value.map { it.file }.filterNot { MapNames.isWorld(it.name) }
    val hasBuildings: Boolean get() = mapFiles.isNotEmpty()
    private var buildingStores: List<MapFile>? = null // only touched by the building worker
    private var labelStores: List<MapFile>? = null // only touched by the label worker

    private val tileQueue = WorkQueue<TileId>("3d-tiles", textures.threads, ::loadTile)
    private val buildingQueue = WorkQueue<TileId>("3d-buildings", 1, ::loadBuildings)
    private val labelQueue = WorkQueue<TileId>("3d-labels", 1, ::loadLabels)

    /** Names per tile, once loaded. */
    private val labels = ConcurrentHashMap<TileId, List<Label3D>>()

    /** Ground height (no exaggeration), 0 where unknown. */
    fun groundAt(lat: Double, lon: Double): Float = elevation.at(lat, lon).takeUnless { it.isNaN() } ?: 0f

    /** What the view needs now: tile → priority (lower loads first). Replaces the previous wish list. */
    fun want(tiles: Map<TileId, Double>, buildings: Map<TileId, Double>, labelTiles: Map<TileId, Double>) {
        tileQueue.setWanted(tiles)
        buildingQueue.setWanted(buildings)
        labelQueue.setWanted(labelTiles.filterKeys { !labels.containsKey(it) })
    }

    /** The names loaded for a tile, or null. */
    fun labelsOf(id: TileId): List<Label3D>? = labels[id]

    /** The GL side dropped a tile; it may be loaded again later. */
    fun forgetTile(id: TileId) = tileQueue.forget(id)
    fun forgetBuildings(id: TileId) = buildingQueue.forget(id)

    val pending: Int get() = tileQueue.pending + buildingQueue.pending

    fun close() {
        tileQueue.close()
        buildingQueue.close()
        labelQueue.close()
        runCatching { textures.close() }
        buildingStores?.forEach { runCatching { it.close() } }
        labelStores?.forEach { runCatching { it.close() } }
    }

    private fun loadTile(id: TileId) {
        val mesh = GlobeMesh.build(elevation, id)
        val bitmap = textures.load(id) ?: if (textures.needsPicture) throw IllegalStateException("no picture") else null
        readyTiles += LoadedTile(id, mesh, bitmap)
        onReady()
    }

    /** Buildings whose middle lies in this zoom-16 tile. */
    private fun loadBuildings(id: TileId) {
        val stores = buildingStores ?: mapFiles.mapNotNull { runCatching { MapFile(it) }.getOrNull() }.also { buildingStores = it }
        val tile = Tile(id.x, id.y, id.z.toByte(), 256)
        val lat = (id.north + id.south) / 2
        val lon = (id.west + id.east) / 2
        val frame = LocalFrame(lat, lon)
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
        readyBuildings += LoadedBuildings(id, Wgs84.ecef(lat, lon, 0.0), lat, lon, vertices, footprints.size)
        onReady()
    }

    /** Names in a tile: the world's (countries, seas, cities) and the offline maps' places, peaks and huts. */
    private fun loadLabels(id: TileId) {
        val out = ArrayList<Label3D>()
        AppGraph.world?.let { w ->
            val box = app.maparound.map.GeoBox(
                (id.south * 1e6).toInt(), (id.west * 1e6).toInt(), (id.north * 1e6).toInt(), (id.east * 1e6).toInt(),
            )
            w.labels(id.z + 1, box) { l -> Label3D.fromWorld(l)?.let(out::add) }
        }
        if (id.z >= Label3D.MAP_LABELS_FROM_ZOOM) {
            val stores = labelStores ?: mapFiles.mapNotNull { runCatching { MapFile(it) }.getOrNull() }.also { labelStores = it }
            val tile = Tile(id.x, id.y, id.z.toByte(), 256)
            for (store in stores) {
                if (!store.supportsTile(tile)) continue
                val data = runCatching { store.readPoiData(tile) }.getOrNull() ?: continue
                for (p in data.pois) {
                    val lat = p.position.latitude
                    val lon = p.position.longitude
                    if (lat !in id.south..id.north || lon !in id.west..id.east) continue
                    Label3D.fromTags(lat, lon, p.tags.associate { it.key to it.value })?.let(out::add)
                }
            }
        }
        labels[id] = out
        // Keep memory bounded: forget names of tiles far from what's wanted (they load again).
        if (labels.size > 600) labels.keys.take(labels.size - 500).forEach { labels.remove(it) }
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
