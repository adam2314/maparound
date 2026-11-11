package app.maparound.places

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Your saved places, kept in one GPX file (`files/places.gpx`) that other apps can read too.
 * Every change is written straight away (via a temp file, so a crash never leaves half a file).
 */
class PlaceRepository(private val context: Context) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val file: File = File(base, "places.gpx")

    private val _places = MutableStateFlow(load())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    /** A place picked in the list, for the map to show (consumed by the map). */
    val focus = MutableStateFlow<String?>(null)

    fun get(id: String): Place? = _places.value.firstOrNull { it.id == id }

    fun save(place: Place) {
        val list = _places.value
        _places.value = if (list.any { it.id == place.id }) list.map { if (it.id == place.id) place else it } else list + place
        persist()
    }

    fun delete(id: String) {
        _places.value = _places.value.filterNot { it.id == id }
        persist()
    }

    /** Adds the waypoints of a GPX file; returns how many were new. */
    fun importGpx(uri: Uri): Int {
        val incoming = context.contentResolver.openInputStream(uri)?.use { Places.readGpx(it) }
            ?: throw IllegalArgumentException("Can't open the file")
        return addAll(incoming)
    }

    fun addAll(incoming: List<Place>): Int {
        // Imported places get fresh ids unless they are our own (round trip of an export).
        val ownIds = _places.value.map { it.id }.toSet()
        val fixed = incoming.map { if (it.id in ownIds) it.copy(id = Place.newId()) else it }
        val (merged, added) = Places.merge(_places.value, fixed)
        if (added > 0) {
            _places.value = merged
            persist()
        }
        return added
    }

    private fun load(): List<Place> = try {
        if (file.isFile) file.inputStream().use { Places.readGpx(it) } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read ${file.name}; keeping a copy", e)
        file.copyTo(File(base, "places-unreadable-${System.currentTimeMillis()}.gpx"), overwrite = true)
        emptyList()
    }

    private fun persist() {
        val tmp = File(base, "places.gpx.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { Places.writeGpx(_places.value, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "PlaceRepository"
    }
}
