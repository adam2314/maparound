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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import app.maparound.data.InstalledMaps
import app.maparound.data.ImportedMap
import app.maparound.i18n.T

/** The map's file (Android code works with files). */
val LocalMap.file: File get() = File(path)

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

class MapFilesRepository(private val context: Context) : InstalledMaps {
    val dir: File = (context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")).apply { mkdirs() }

    private val _files = MutableStateFlow<List<LocalMap>>(emptyList())
    override val files: StateFlow<List<LocalMap>> = _files.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _files.value = dir.listFiles { f -> f.isFile && f.name.endsWith(".map") }
            .orEmpty()
            .map { LocalMap(it.path, it.length(), it.lastModified()) }
            .sortedWith(compareBy<LocalMap> { !it.isWorld }.thenBy { it.title })
    }

    override fun freeBytes(): Long = dir.usableSpace

    @Composable
    override fun rememberImporter(onStart: () -> Unit, onResult: (Result<LocalMap>) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val start = rememberUpdatedState(onStart)
        val done = rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    start.value()
                    done.value(import(uri))
                }
            }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    override val importsRasters: Boolean get() = true

    /** One picker for both: a raster map (.tif, .rmap, .mbtiles, .sqlitedb) is read where it is, anything else imported as a .map. */
    @Composable
    override fun rememberAnyImporter(onStart: () -> Unit, onResult: (Result<ImportedMap>) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val start = rememberUpdatedState(onStart)
        val done = rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    start.value()
                    val name = withContext(Dispatchers.IO) { displayName(uri) }.orEmpty().lowercase()
                    val raster = RASTER_ENDINGS.any { name.endsWith(it) }
                    done.value(
                        if (raster) withContext(Dispatchers.IO) { runCatching { ImportedMap.Raster(app.maparound.AppGraph.rasters.add(uri)) } }
                        else import(uri).map { ImportedMap.Vector(it) },
                    )
                }
            }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun delete(map: LocalMap) {
        map.file.delete()
        refresh()
    }

    /** Copies a user-picked .map file (e.g. from OpenAndroMaps) into the app's map folder. */
    suspend fun import(uri: Uri): Result<LocalMap> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = displayName(uri) ?: "imported.map"
            // A second download of the same map ("europe__liechtenstein (1).map") is still that map.
            name = name.replace(Regex("""\s*\(\d+\)(?=\.map$)""", RegexOption.IGNORE_CASE), "")
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".map")) name += ".map"
            val tmp = File(dir, "$name.import")
            val input = resolver.openInputStream(uri) ?: error(T.offline.cannotOpenFile)
            input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 1 shl 16) } }
            if (!isValidMapFile(tmp)) {
                tmp.delete()
                error(T.offline.notMapsforgeFile)
            }
            val target = File(dir, name)
            target.delete()
            if (!tmp.renameTo(target)) error(T.offline.couldNotStoreFile)
            refresh()
            LocalMap(target.path, target.length(), target.lastModified())
        }
    }

    private companion object {
        /** Raster maps the picker hands to the raster library instead. */
        val RASTER_ENDINGS = listOf(".rmap", ".mbtiles", ".sqlitedb", ".tif", ".tiff", ".gtif", ".ecw", ".jp2")
    }
}
