package app.maparound.data

import android.content.Context
import android.net.ConnectivityManager
import app.maparound.shared.util.Bits
import app.maparound.terrain.CoastMask
import kotlinx.coroutines.flow.SharedFlow
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * Elevation data for contour lines and hill shading: the shared [TerrainStore] in the app's
 * files (`files/dem/`), cutting the elevation at the coastline of the installed maps.
 */
class TerrainRepository private constructor(private val store: TerrainStore) : TerrainData by store {
    constructor(context: Context, prefs: Prefs, maps: MapFilesRepository) : this(
        TerrainStore(
            demDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dem").path,
            prefs = prefs,
            coast = { AndroidCoastMasks.open(maps) },
            isMetered = { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false },
            freeBytes = { File(context.getExternalFilesDir(null) ?: context.filesDir, "dem").usableSpace },
        ),
    )

    val demDir: File = File(store.demDir)

    /** "Contours & hill shading ready" and other messages for snackbars. */
    val events: SharedFlow<String> get() = store.events

    fun refresh() = store.refresh()
}

/** The sea of the installed vector maps, read with mapsforge. */
private class AndroidCoastMasks(private val store: MultiMapDataStore) : CoastMasks {
    override fun build(lat: Int, lon: Int, size: Int): Bits? = CoastMask.build(store, lat, lon, size)
    override fun close() = store.close()

    companion object {
        fun open(maps: MapFilesRepository): CoastMasks? {
            val files = maps.files.value.filterNot { it.isWorld }
            if (files.isEmpty()) return null
            val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
            files.forEach { m -> runCatching { store.addMapDataStore(MapFile(m.file), false, false) } }
            return AndroidCoastMasks(store)
        }
    }
}
