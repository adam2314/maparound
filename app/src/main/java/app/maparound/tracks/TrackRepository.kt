package app.maparound.tracks

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.maparound.routing.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A track/route in the library; [id] is its GPX file name. */
data class SavedTrack(val id: String, val track: Track)

/**
 * The library of routes and tracks: one GPX file each in `files/tracks/`, so they can be shared
 * with or imported from any other app.
 */
class TrackRepository(private val context: Context) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val dir: File = File(base, "tracks").apply { mkdirs() }
    private val sp = context.getSharedPreferences("tracks", Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<SavedTrack>>(emptyList())
    val tracks: StateFlow<List<SavedTrack>> = _tracks.asStateFlow()

    /** Ids of the tracks drawn on the map. */
    private val _visible = MutableStateFlow(sp.getStringSet(KEY_VISIBLE, emptySet())!!.toSet())
    val visible: StateFlow<Set<String>> = _visible.asStateFlow()

    private val cache = HashMap<String, Pair<Long, Track>>() // id -> (lastModified, parsed)

    init {
        refresh()
    }

    @Synchronized
    fun refresh() {
        val files = dir.listFiles { f -> f.name.endsWith(".gpx") }.orEmpty()
        val list = files.mapNotNull { f ->
            val cached = cache[f.name]
            val track = if (cached != null && cached.first == f.lastModified()) {
                cached.second
            } else {
                runCatching { f.inputStream().use { Gpx.read(it, f.nameWithoutExtension, f.lastModified()) } }
                    .getOrNull()?.also { cache[f.name] = f.lastModified() to it }
            }
            track?.let { SavedTrack(f.name, it) }
        }.sortedByDescending { it.track.created }
        _tracks.value = list
        val ids = list.map { it.id }.toSet()
        if (!ids.containsAll(_visible.value)) setVisibleIds(_visible.value intersect ids)
    }

    fun get(id: String): SavedTrack? = _tracks.value.firstOrNull { it.id == id }

    fun save(track: Track, show: Boolean = true): SavedTrack {
        val file = uniqueFile(track.name)
        file.outputStream().use { Gpx.write(track, it) }
        refresh()
        if (show) setVisible(file.name, true)
        return SavedTrack(file.name, track)
    }

    fun rename(id: String, name: String) {
        val t = get(id)?.track ?: return
        File(dir, id).outputStream().use { Gpx.write(t.copy(name = name), it) }
        refresh()
    }

    fun delete(id: String) {
        File(dir, id).delete()
        cache.remove(id)
        refresh()
    }

    fun setVisible(id: String, visible: Boolean) =
        setVisibleIds(if (visible) _visible.value + id else _visible.value - id)

    private fun setVisibleIds(ids: Set<String>) {
        _visible.value = ids
        sp.edit { putStringSet(KEY_VISIBLE, ids) }
    }

    suspend fun import(uri: Uri): Result<SavedTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.substringBeforeLast('.') ?: "Imported"
            val track = resolver.openInputStream(uri)?.use { Gpx.read(it, name, System.currentTimeMillis()) }
                ?: error("Cannot open file")
            save(track)
        }
    }

    private fun uniqueFile(name: String): File {
        val slug = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(' ', '_').take(60).ifEmpty { "track" }
        var f = File(dir, "$slug.gpx")
        var n = 2
        while (f.exists()) f = File(dir, "${slug}_${n++}.gpx")
        return f
    }

    // ---- crash safety for recordings ----

    /** File the recording service appends points to, one "lat,lon,ele,time" line each. */
    val journal: File get() = File(dir, "recording.journal")

    /**
     * If a recording was cut off (app killed and not restarted by the system), keep what was
     * recorded as a track. Called on app start when nothing is recording, and before a new recording.
     */
    fun recoverInterruptedRecording() {
        if (!journal.isFile || TrackRecorder.state.value.recording) return
        val points = readJournal()
        if (points.size >= 2) {
            val track = Track(defaultName(points.first().time ?: journal.lastModified()) + " (recovered)", TrackKind.RECORDED, points, points.first().time ?: journal.lastModified())
            File(dir, uniqueFile(track.name).name).outputStream().use { Gpx.write(track, it) }
        }
        journal.delete()
        refresh()
    }

    fun readJournal(): List<GeoPoint> = journal.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
        val p = line.split(',')
        if (p.size < 4) return@mapNotNull null
        val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
        val lon = p[1].toDoubleOrNull() ?: return@mapNotNull null
        GeoPoint(lat, lon, p[2].toDoubleOrNull(), p[3].toLongOrNull())
    }

    companion object {
        private const val KEY_VISIBLE = "visible"

        fun defaultName(time: Long): String =
            "Track " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }
}
