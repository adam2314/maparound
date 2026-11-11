package app.maparound.tracks

import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.data.KeyValueStore
import app.maparound.routing.GeoPoint
import app.maparound.shared.fileSystem
import app.maparound.shared.util.nowMillis
import app.maparound.shared.util.padded
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Path
import okio.Path.Companion.toPath

/** A track/route in the library; [id] is its GPX file name. */
data class SavedTrack(val id: String, val track: Track)

/**
 * The library of routes and tracks: one GPX file each in `tracks/` under [baseDir], so they can
 * be shared with or imported from any other app. Which are shown on the map is kept in [store].
 */
class TrackRepository(baseDir: String, private val store: KeyValueStore) {
    private val dirPath: Path = (baseDir.toPath() / "tracks").also { fileSystem.createDirectories(it) }
    val dir: String get() = dirPath.toString()

    private val _tracks = MutableStateFlow<List<SavedTrack>>(emptyList())
    val tracks: StateFlow<List<SavedTrack>> = _tracks.asStateFlow()

    /** Ids of the tracks drawn on the map. */
    private val _visible = MutableStateFlow(store.getStringSet(KEY_VISIBLE).orEmpty())
    val visible: StateFlow<Set<String>> = _visible.asStateFlow()

    private val lock = SynchronizedObject()
    private val cache = HashMap<String, Pair<Long, Track>>() // id -> (lastModified, parsed)

    init {
        refresh()
    }

    fun refresh() = synchronized(lock) {
        val files = runCatching { fileSystem.list(dirPath) }.getOrDefault(emptyList()).filter { it.name.endsWith(".gpx") }
        val list = files.mapNotNull { f ->
            val modified = runCatching { fileSystem.metadata(f).lastModifiedAtMillis }.getOrNull() ?: 0L
            val cached = cache[f.name]
            val track = if (cached != null && cached.first == modified) {
                cached.second
            } else {
                runCatching { Gpx.read(fileSystem.read(f) { readByteArray() }, f.name.removeSuffix(".gpx"), modified) }
                    .getOrNull()?.also { cache[f.name] = modified to it }
            }
            track?.let { SavedTrack(f.name, it) }
        }.sortedByDescending { it.track.created }
        _tracks.value = list
        val ids = list.map { it.id }.toSet()
        if (!ids.containsAll(_visible.value)) setVisibleIds(_visible.value intersect ids)
    }

    fun get(id: String): SavedTrack? = _tracks.value.firstOrNull { it.id == id }

    /** The GPX file of a saved track. */
    fun pathOf(id: String): String = (dirPath / id).toString()

    fun save(track: Track, show: Boolean = true): SavedTrack {
        val file = uniqueFile(track.name)
        write(file, track)
        refresh()
        if (show) setVisible(file.name, true)
        return SavedTrack(file.name, track)
    }

    fun rename(id: String, name: String) {
        val t = get(id)?.track ?: return
        write(dirPath / id, t.copy(name = name))
        refresh()
    }

    fun delete(id: String) {
        runCatching { fileSystem.delete(dirPath / id) }
        synchronized(lock) { cache.remove(id) }
        refresh()
    }

    fun setVisible(id: String, visible: Boolean) =
        setVisibleIds(if (visible) _visible.value + id else _visible.value - id)

    private fun setVisibleIds(ids: Set<String>) {
        _visible.value = ids
        store.putStringSet(KEY_VISIBLE, ids)
    }

    /** Adds a GPX file's track (or route) under [fileName]'s name. Blocking. */
    fun importGpx(bytes: ByteArray, fileName: String?): Result<SavedTrack> = runCatching {
        val name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported"
        save(Gpx.read(bytes, name, nowMillis()))
    }

    private fun write(file: Path, track: Track) {
        val text = Gpx.toXml(track)
        fileSystem.write(file) { writeUtf8(text) }
    }

    private fun uniqueFile(name: String): Path {
        // Letters, numbers, space, '_' and '-' (as the regex [^\p{L}\p{N} _-] did; Kotlin/Native has no \p{N}).
        val slug = name.filter { keepInFileName(it) }.trim().replace(' ', '_').take(60).ifEmpty { "track" }
        var f = dirPath / "$slug.gpx"
        var n = 2
        while (fileSystem.exists(f)) f = dirPath / "${slug}_${n++}.gpx"
        return f
    }

    // ---- crash safety for recordings ----

    /** File the recording service appends points to, one "lat,lon,ele,time" line each. */
    val journalPath: String get() = (dirPath / "recording.journal").toString()

    /**
     * If a recording was cut off (app killed and not restarted by the system), keep what was
     * recorded as a track. Called on app start when nothing is recording, and before a new recording.
     */
    fun recoverInterruptedRecording() {
        val journal = journalPath.toPath()
        if (!fileSystem.exists(journal) || TrackRecorder.state.value.recording) return
        val points = readJournal()
        val modified = runCatching { fileSystem.metadata(journal).lastModifiedAtMillis }.getOrNull() ?: nowMillis()
        if (points.size >= 2) {
            val start = points.first().time ?: modified
            write(uniqueFile(defaultName(start) + " (recovered)"), Track(defaultName(start) + " (recovered)", TrackKind.RECORDED, points, start))
        }
        runCatching { fileSystem.delete(journal) }
        refresh()
    }

    fun readJournal(): List<GeoPoint> {
        val journal = journalPath.toPath()
        if (!fileSystem.exists(journal)) return emptyList()
        return fileSystem.read(journal) { readUtf8() }.lines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 4) return@mapNotNull null
            val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lon, p[2].toDoubleOrNull(), p[3].toLongOrNull())
        }
    }

    companion object {
        private const val KEY_VISIBLE = "visible"

        private val NUMBERS = setOf(CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER)

        private fun keepInFileName(c: Char) = c.isLetter() || c.category in NUMBERS || c == ' ' || c == '_' || c == '-'

        /** "Track 2026-11-11 14:05" in local time. */
        fun defaultName(time: Long): String {
            val t = Instant.fromEpochMilliseconds(time).toLocalDateTime(TimeZone.currentSystemDefault())
            return "Track ${t.year}-${padded(t.monthNumber, 2)}-${padded(t.dayOfMonth, 2)} ${padded(t.hour, 2)}:${padded(t.minute, 2)}"
        }
    }
}

/** The routes & tracks library, provided at the root of the shared UI. */
val LocalTracks = staticCompositionLocalOf<TrackRepository> { error("No TrackRepository provided") }
