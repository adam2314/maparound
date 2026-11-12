package app.maparound.map

import android.content.Context
import app.maparound.shared.util.Xml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The offline maps' styles on Android: MapAround's Topo, the themes that come with mapsforge,
 * and render themes the user imports (`files/themes/<folder>/…`, an `.xml` or a `.zip` with the
 * theme and its symbols). [renderTheme] gives the renderer a theme with the user's options.
 */
class AndroidMapThemes(context: Context) : MapThemeLibrary {
    private val assets = context.applicationContext.assets
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "themes").apply { mkdirs() }

    private val builtIn = listOf(
        MapTheme(MapThemes.TOPO, "Topo", "MapAround's hiking style: contours, trails, peaks"),
        MapTheme(MapThemes.CLASSIC, "Classic", "mapsforge's default style"),
        MapTheme(OSMARENDER, "Osmarender", "The classic OpenStreetMap look"),
        MapTheme(BIKER, "Biker", "Cycle routes and cycleways first"),
        MapTheme(MOTORIDER, "Motorider", "Roads by type, for riding and driving"),
    )

    private val _themes = MutableStateFlow(builtIn + scan())
    override val themes: StateFlow<List<MapTheme>> = _themes.asStateFlow()

    override val canImport = true

    private class Menu(val menu: StyleMenu?)
    private val menus = HashMap<String, Menu>()

    override fun menu(themeId: String): StyleMenu? = synchronized(menus) {
        menus.getOrPut(themeId) { Menu(runCatching { StyleMenu.parse(Xml.decode(open(themeId).use { it.readBytes() })) }.getOrNull()) }
    }.menu

    /** The theme for the renderer: [hybrid] over imagery, drawing only [categories] (null: all). */
    fun renderTheme(themeId: String, hybrid: Boolean, categories: Set<String>?): XmlRenderTheme {
        val id = themeId.takeIf { exists(it) } ?: MapThemes.TOPO
        return Theme(id, prefix(id), { open(id) }, hybrid, categories)
    }

    private fun exists(id: String) = _themes.value.any { it.id == id }

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
                        description = "Imported · ${folder.name}",
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
                if (!Xml.decode(bytes.copyOf(minOf(bytes.size, 8192))).contains("<rendertheme")) error("That isn't a mapsforge map style (render theme XML)")
                File(folder, "$base.xml").writeBytes(bytes)
            }
            val before = _themes.value.map { it.id }.toSet()
            _themes.value = builtIn + scan()
            val added = _themes.value.filter { it.id !in before }
            if (added.isEmpty()) error("No map style (render theme XML) in that file")
            // Keep only what mapsforge can read; the error of the first that fails tells why.
            var problem: Throwable? = null
            val good = added.filter { t ->
                runCatching { RenderThemeHandler.getRenderTheme(AndroidGraphicFactory.INSTANCE, DisplayModel(), renderTheme(t.id, false, null)) }
                    .onFailure { problem = problem ?: it }.isSuccess
            }
            (added - good.toSet()).forEach { fileOf(it.id)?.delete() }
            if (good.isEmpty()) error("The map style can't be read: ${problem?.message ?: "unknown error"}")
            _themes.value = builtIn + scan()
            good
        } catch (e: Throwable) {
            _themes.value = builtIn + scan()
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
                if (!out.canonicalPath.startsWith(root)) error("The zip has a file outside its folder: ${entry.name}")
                if (entry.isDirectory) { out.mkdirs(); continue }
                if (++count > MAX_ENTRIES) error("The zip has too many files")
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNZIPPED) error("The zip is too large once unpacked")
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
        _themes.value = builtIn + scan()
        if (folder != null && _themes.value.none { it.id.startsWith(MapThemes.IMPORTED + folder.name + "/") }) folder.deleteRecursively()
        synchronized(menus) { menus.remove(themeId) }
    }

    /** A theme as mapsforge reads it; equal themes share cached tiles. */
    private class Theme(
        val id: String,
        private val prefix: String,
        private val open: () -> InputStream,
        val hybrid: Boolean,
        val categories: Set<String>?,
    ) : XmlRenderTheme {
        private var callback: XmlRenderThemeMenuCallback? = categories?.let { cats -> XmlRenderThemeMenuCallback { cats.toMutableSet() } }

        override fun getMenuCallback(): XmlRenderThemeMenuCallback? = callback
        override fun setMenuCallback(menuCallback: XmlRenderThemeMenuCallback?) {
            callback = menuCallback
        }
        override fun getRelativePathPrefix(): String = prefix
        override fun getRenderThemeAsStream(): InputStream {
            if (!hybrid) return open()
            val xml = open().use { Xml.decode(it.readBytes()) }
            return ByteArrayInputStream(TopoTheme.hybridOf(xml).toByteArray(Charsets.UTF_8))
        }
        override fun getResourceProvider(): XmlThemeResourceProvider? = null
        override fun setResourceProvider(resourceProvider: XmlThemeResourceProvider?) = Unit

        override fun equals(other: Any?) = other is Theme && other.id == id && other.hybrid == hybrid && other.categories == categories
        override fun hashCode() = (id.hashCode() * 31 + hybrid.hashCode()) * 31 + categories.hashCode()
    }

    companion object {
        const val OSMARENDER = "osmarender"
        const val BIKER = "biker"
        const val MOTORIDER = "motorider"
        private const val MAX_ENTRIES = 5000
        private const val MAX_UNZIPPED = 200L shl 20
    }
}
