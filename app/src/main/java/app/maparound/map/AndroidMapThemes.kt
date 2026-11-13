package app.maparound.map

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.caverock.androidsvg.SVG
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.Context
import app.maparound.i18n.T
import app.maparound.shared.util.Xml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The offline maps' styles on Android: MapAround's Topo, the themes that come with mapsforge,
 * and render themes the user imports (`files/themes/<folder>/…`, an `.xml` or a `.zip` with the
 * theme and its symbols). The map draws them with MapLibre (map/gl/GlStyles).
 */
class AndroidMapThemes(context: Context) : MapThemeLibrary {
    private val assets = context.applicationContext.assets
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "themes").apply { mkdirs() }

    // Their descriptions (and Classic's name) in the app's language: MapThemes.about / title.
    private val builtIn = listOf(
        MapTheme(MapThemes.TOPO, "Topo", ""),
        MapTheme(MapThemes.CLASSIC, "Classic", ""),
        MapTheme(OSMARENDER, "Osmarender", ""),
        MapTheme(BIKER, "Biker", ""),
        MapTheme(MOTORIDER, "Motorider", ""),
    )

    private val _themes = MutableStateFlow(builtIn + scan())
    override val themes: StateFlow<List<MapTheme>> = _themes.asStateFlow()

    override val canImport = true

    private class Menu(val menu: StyleMenu?)
    private val menus = HashMap<String, Menu>()

    override fun menu(themeId: String): StyleMenu? = synchronized(menus) {
        menus.getOrPut(themeId) { Menu(runCatching { StyleMenu.parse(Xml.decode(open(themeId).use { it.readBytes() })) }.getOrNull()) }
    }.menu

    /** The style's XML (falls back to Topo when it's gone), for the MapLibre map. */
    fun xml(themeId: String): String {
        val id = themeId.takeIf { exists(it) } ?: MapThemes.TOPO
        return open(id).use { Xml.decode(it.readBytes()) }
    }

    override fun themeXml(themeId: String): String? = runCatching { xml(themeId) }.getOrNull()

    override suspend fun symbol(themeId: String, path: String, heightPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val svg = openSymbol(themeId, path)?.use { SVG.getFromInputStream(it) } ?: return@runCatching null
            app.maparound.map.gl.svgBitmap(svg, heightPx.coerceAtLeast(1).toFloat()).asImageBitmap()
        }.getOrNull()
    }

    /** One of the style's symbol files ("symbols/peak.svg"), or null. */
    fun openSymbol(themeId: String, path: String): InputStream? {
        if (".." in path) return null
        val id = themeId.takeIf { exists(it) } ?: MapThemes.TOPO
        val prefix = prefix(id)
        return if (prefix.startsWith("/")) {
            // The built-in styles' symbols are in mapsforge's jar.
            AndroidMapThemes::class.java.getResourceAsStream(prefix + path)
        } else {
            File(prefix + path).takeIf { it.isFile }?.inputStream()
        }
    }

    private fun exists(id: String) = _themes.value.any { it.id == id }

    /** Reads the imported styles again; the map makes their MapLibre styles anew. */
    private fun refresh() {
        _themes.value = builtIn + scan()
        app.maparound.map.gl.GlStyles.forgetImported()
    }

    private fun open(id: String): InputStream = when (id) {
        MapThemes.TOPO -> assets.open("themes/topo.xml")
        MapThemes.CLASSIC -> MapsforgeThemes.DEFAULT.renderThemeAsStream
        OSMARENDER -> MapsforgeThemes.OSMARENDER.renderThemeAsStream
        BIKER -> MapsforgeThemes.BIKER.renderThemeAsStream
        MOTORIDER -> MapsforgeThemes.MOTORIDER.renderThemeAsStream
        else -> fileOf(id)?.inputStream() ?: throw IOException("No map style $id")
    }

    /** Where the theme's own symbol files are found. */
    private fun prefix(id: String): String = when (id) {
        MapThemes.TOPO -> "/assets/"
        MapThemes.CLASSIC -> MapsforgeThemes.DEFAULT.relativePathPrefix
        OSMARENDER -> MapsforgeThemes.OSMARENDER.relativePathPrefix
        BIKER -> MapsforgeThemes.BIKER.relativePathPrefix
        MOTORIDER -> MapsforgeThemes.MOTORIDER.relativePathPrefix
        else -> (fileOf(id)?.parentFile?.absolutePath ?: dir.absolutePath) + "/"
    }

    private fun fileOf(id: String): File? =
        id.removePrefix(MapThemes.IMPORTED).takeIf { id.startsWith(MapThemes.IMPORTED) }
            ?.let { File(dir, it) }?.takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath) }

    // ---- imported themes ------------------------------------------------------------------------

    /** Every render theme under [dir]: one folder per import, themes up to three levels down. */
    private fun scan(): List<MapTheme> =
        dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }.flatMap { folder ->
            folder.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension.equals("xml", true) && isRenderTheme(it) }
                .sortedBy { it.path.lowercase() }
                .map { f ->
                    MapTheme(
                        id = MapThemes.IMPORTED + f.relativeTo(dir).invariantSeparatorsPath,
                        name = f.nameWithoutExtension.replace('_', ' '),
                        // Shown as "Imported · <folder>" (MapThemes.about).
                        description = folder.name,
                        imported = true,
                    )
                }.toList()
        }

    private fun isRenderTheme(f: File): Boolean = runCatching {
        f.inputStream().use { val head = ByteArray(4096); val n = it.read(head); n > 0 && String(head, 0, n).contains("<rendertheme") }
    }.getOrDefault(false)

    override fun import(name: String?, bytes: ByteArray): Result<List<MapTheme>> = runCatching {
        val zip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val base = (name ?: "style").substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "style" }
        val folder = generateSequence(0) { it + 1 }.map { File(dir, if (it == 0) base else "$base-$it") }.first { !it.exists() }
        folder.mkdirs()
        try {
            if (zip) unzip(bytes, folder) else {
                if (!Xml.decode(bytes.copyOf(minOf(bytes.size, 8192))).contains("<rendertheme")) error(T.map.notAStyle)
                File(folder, "$base.xml").writeBytes(bytes)
            }
            val before = _themes.value.map { it.id }.toSet()
            refresh()
            val added = _themes.value.filter { it.id !in before }
            if (added.isEmpty()) error(T.map.noStyleInFile)
            // Keep only what the map can draw; the error of the first that fails tells why.
            var problem: Throwable? = null
            val good = added.filter { t ->
                runCatching { app.maparound.map.maplibre.ThemeStyle.convert(xml(t.id)) }
                    .onFailure { problem = problem ?: it }.isSuccess
            }
            (added - good.toSet()).forEach { fileOf(it.id)?.delete() }
            if (good.isEmpty()) error(T.map.styleUnreadable(problem?.message ?: T.map.unknownError))
            refresh()
            good
        } catch (e: Throwable) {
            refresh()
            if (_themes.value.none { it.id.startsWith(MapThemes.IMPORTED + folder.name + "/") }) folder.deleteRecursively()
            throw e
        }
    }

    /** Unpacks a zip into [folder], refusing entries that would land outside it. */
    private fun unzip(bytes: ByteArray, folder: File) {
        val root = folder.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(folder, entry.name)
                if (!out.canonicalPath.startsWith(root)) error(T.map.zipFileOutside(entry.name))
                if (entry.isDirectory) { out.mkdirs(); continue }
                if (++count > MAX_ENTRIES) error(T.map.zipTooManyFiles)
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNZIPPED) error(T.map.zipTooLarge)
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    override fun delete(themeId: String) {
        val f = fileOf(themeId) ?: return
        f.delete()
        // The import's folder goes too once it holds no style.
        val folder = generateSequence(f.parentFile) { it.parentFile }.firstOrNull { it.parentFile?.canonicalPath == dir.canonicalPath }
        refresh()
        if (folder != null && _themes.value.none { it.id.startsWith(MapThemes.IMPORTED + folder.name + "/") }) folder.deleteRecursively()
        synchronized(menus) { menus.remove(themeId) }
    }

    companion object {
        const val OSMARENDER = MapThemes.OSMARENDER
        const val BIKER = MapThemes.BIKER
        const val MOTORIDER = MapThemes.MOTORIDER
        private const val MAX_ENTRIES = 5000
        private const val MAX_UNZIPPED = 200L shl 20
    }
}
