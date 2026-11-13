package app.maparound.data

import app.maparound.shared.util.urlDecode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

// Plain Kotlin (Android and iOS): the mapsforge map catalog and how downloaded maps are named.

/** One row of the remote map catalog: either a folder (continent / country split in regions) or a .map file. */
data class CatalogEntry(
    val path: String,          // relative to the catalog root, e.g. "europe/germany/" or "europe/austria.map"
    val isDirectory: Boolean,
    val sizeLabel: String?,    // as printed by the server, e.g. "528M"
    val sizeBytes: Long?,      // approximate, parsed from sizeLabel
    val modified: Long?,       // epoch millis of last server-side update
    /** Where to download it, when the catalog says; else the catalog's address + [path]. */
    val url: String? = null,
    /** Why MapAround's map can't be downloaded, or null. */
    val unavailable: Unavailable? = null,
    /**
     * The older map from download.mapsforge.org (no built-in trails), offered while MapAround's
     * isn't there ([unavailable]) or when MapAround's list has no such region.
     */
    val basic: Boolean = false,
) {
    val name: String get() = path.removeSuffix("/").substringAfterLast('/')
    val title: String get() = MapNames.pretty(name)
    val localFileName: String get() = MapNames.localFileName(path)

    enum class Unavailable {
        /** Too big for the free build machines: built once there's money for bigger ones. */
        NEEDS_FUNDING,

        /** Its build failed and there's no earlier one. */
        NOT_BUILT,
    }
}

/** Reading the catalog server's directory listings (https://download.mapsforge.org/maps/v5/). */
object Catalog {
    const val BASE_URL = "https://download.mapsforge.org/maps/v5/"

    // Apache autoindex row: <a href="name">..</a></td><td align="right">2026-08-21 15:48  </td><td align="right"> 43M</td>
    private val ROW = Regex(
        """<a href="([^"?/][^"]*)">[^<]*</a>\s*</td>\s*<td[^>]*>\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})?\s*</td>\s*<td[^>]*>\s*([\d.]+[KMGT]?|-)\s*</td>""",
    )

    fun parseListing(html: String, path: String): List<CatalogEntry> =
        ROW.findAll(html).mapNotNull { m ->
            val href = urlDecode(m.groupValues[1])
            val isDir = href.endsWith("/")
            if (!isDir && !href.endsWith(".map")) return@mapNotNull null
            val sizeLabel = m.groupValues[3].takeIf { it != "-" }
            CatalogEntry(
                path = path + href,
                isDirectory = isDir,
                sizeLabel = sizeLabel,
                sizeBytes = sizeLabel?.let(::parseSize),
                modified = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseUtcMinute),
            )
        }.toList()

    /** "2026-08-21 15:48" (UTC) in epoch milliseconds. */
    private fun parseUtcMinute(s: String): Long? =
        runCatching { LocalDateTime.parse(s.replace(' ', 'T')).toInstant(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()

    fun parseSize(label: String): Long? {
        val unit = label.last()
        val multiplier = when (unit) {
            'K' -> 1L shl 10
            'M' -> 1L shl 20
            'G' -> 1L shl 30
            'T' -> 1L shl 40
            else -> 1L
        }
        val number = if (unit.isDigit()) label else label.dropLast(1)
        return number.toDoubleOrNull()?.let { (it * multiplier).toLong() }
    }
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
                w in upper -> w.uppercase()
                i > 0 && w in lower -> w
                else -> w.replaceFirstChar { it.titlecase() }
            }
        }
        .joinToString(" ")
}
