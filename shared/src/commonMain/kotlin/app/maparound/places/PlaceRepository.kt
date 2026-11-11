package app.maparound.places

import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.shared.fileSystem
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path.Companion.toPath

/**
 * Your saved places, kept in one GPX file (`places.gpx` in [dir]) that other apps can read too.
 * Every change is written straight away (via a temp file, so a crash never leaves half a file).
 */
class PlaceRepository(dir: String) {
    private val base = dir.toPath()
    private val file = base / "places.gpx"
    val path: String get() = file.toString()

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

    /** Adds the waypoints of a GPX file's bytes; returns how many were new. */
    fun importGpx(bytes: ByteArray): Int = addAll(Places.readGpx(bytes))

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
        if (fileSystem.exists(file)) Places.readGpx(fileSystem.read(file) { readByteArray() }) else emptyList()
    } catch (e: Exception) {
        // Unreadable: keep a copy for the user and start empty.
        runCatching { fileSystem.copy(file, base / "places-unreadable-${nowMillis()}.gpx") }
        emptyList()
    }

    private fun persist() {
        val tmp = base / "places.gpx.tmp"
        val text = StringBuilder().also { Places.writeGpx(_places.value, it) }.toString()
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.atomicMove(tmp, file)
    }
}

/** The saved places, provided at the root of the shared UI. */
val LocalPlaces = staticCompositionLocalOf<PlaceRepository> { error("No PlaceRepository provided") }
