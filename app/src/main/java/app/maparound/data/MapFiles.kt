package app.maparound.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.util.Locale

/** A mapsforge vector map installed on the device. */
data class LocalMap(val file: File, val sizeBytes: Long, val modified: Long) {
    val title: String get() = MapNames.title(file.name)
    val region: String? get() = MapNames.region(file.name)
    val isWorld: Boolean get() = MapNames.isWorld(file.name)
}

/**
 * Downloaded maps are stored flat as `<continent>__<country>[__<region>].map`
 * so equal names in different folders (europe/georgia vs. us/georgia) never clash.
 */
object MapNames {
    private const val SEP = "__"
    private val upper = setOf("us", "uk", "usa", "dc", "dach")
    private val lower = setOf("and", "of", "the", "de", "del", "la", "le", "da", "do")

    fun localFileName(remotePath: String): String =
        remotePath.removeSuffix(".map").split('/').filter { it.isNotEmpty() }.joinToString(SEP) + ".map"

    fun title(fileName: String): String = pretty(fileName.removeSuffix(".map").substringAfterLast(SEP))

    fun region(fileName: String): String? {
        val base = fileName.removeSuffix(".map")
        if (!base.contains(SEP)) return null
        return base.substringBeforeLast(SEP).split(SEP).joinToString(" › ") { pretty(it) }
    }

    fun isWorld(fileName: String) = fileName.startsWith("world$SEP")

    fun pretty(slug: String): String = slug.removeSuffix("/").removeSuffix(".map")
        .split('-', '_').filter { it.isNotEmpty() }
        .mapIndexed { i, w ->
            when {
                w in upper -> w.uppercase(Locale.ROOT)
                i > 0 && w in lower -> w
                else -> w.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        .joinToString(" ")
}

fun isValidMapFile(file: File): Boolean = try {
    MapFile(file).close()
    true
} catch (e: Exception) {
    false
}

fun readBoundingBox(file: File): BoundingBox? = try {
    val mf = MapFile(file)
    try {
        mf.boundingBox()
    } finally {
        mf.close()
    }
} catch (e: Exception) {
    null
}

class MapFilesRepository(private val context: Context) {
    val dir: File = (context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")).apply { mkdirs() }

    private val _files = MutableStateFlow<List<LocalMap>>(emptyList())
    val files: StateFlow<List<LocalMap>> = _files.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _files.value = dir.listFiles { f -> f.isFile && f.name.endsWith(".map") }
            .orEmpty()
            .map { LocalMap(it, it.length(), it.lastModified()) }
            .sortedWith(compareBy<LocalMap> { !it.isWorld }.thenBy { it.title })
    }

    fun delete(map: LocalMap) {
        map.file.delete()
        refresh()
    }

    /** Copies a user-picked .map file (e.g. from OpenAndroMaps) into the app's map folder. */
    suspend fun import(uri: Uri): Result<LocalMap> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "imported.map"
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".map")) name += ".map"
            val tmp = File(dir, "$name.import")
            val input = resolver.openInputStream(uri) ?: error("Cannot open file")
            input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 1 shl 16) } }
            if (!isValidMapFile(tmp)) {
                tmp.delete()
                error("Not a mapsforge .map file")
            }
            val target = File(dir, name)
            target.delete()
            if (!tmp.renameTo(target)) error("Could not store file")
            refresh()
            LocalMap(target, target.length(), target.lastModified())
        }
    }
}
