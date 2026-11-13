package app.maparound.tiles

import app.maparound.map.BorderLines
import app.maparound.map.WorldData
import app.maparound.map.WorldTile
import app.maparound.map.maplibre.TileService
import app.maparound.shared.openFileOverride
import app.maparound.shared.toByteArray
import app.maparound.shared.toUint8Array
import okio.FileHandle

// The tile worker's Kotlin half: the shared TileService answering the map's tile requests from the
// maps tiles-worker.js keeps open in the browser's file storage. Paths of those maps start with
// [PREFIX]; their bytes are read synchronously through tiles-worker.js (__mrRead).

private const val PREFIX = "/opfs/"

private val service = TileService()

/** The open maps: path → size. */
private val sizes = HashMap<String, Long>()

private fun read(name: String, at: Double, n: Int): JsAny = js("self.__mrRead(name, at, n)")

private fun register(init: (JsAny?, JsAny?) -> Unit, setMaps: (String) -> Unit, tile: (String) -> JsAny?): Unit =
    js("{ self.__mrKotlin = { init: init, setMaps: setMaps, tile: tile }; if (self.__mrReady) self.__mrReady(); }")

/** A map file in the browser's file storage, read synchronously in pieces. */
private class OpfsFile(private val name: String, private val length: Long) : FileHandle(false) {
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (fileOffset >= length) return -1
        val n = minOf(byteCount.toLong(), length - fileOffset).toInt()
        val bytes = read(name, fileOffset.toDouble(), n).toByteArray()
        if (bytes.isEmpty()) return -1
        bytes.copyInto(array, arrayOffset)
        return bytes.size
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
        throw UnsupportedOperationException("read only")

    override fun protectedFlush() = Unit
    override fun protectedResize(size: Long) = throw UnsupportedOperationException("read only")
    override fun protectedSize(): Long = length
    override fun protectedClose() = Unit
}

fun main() {
    openFileOverride = { path -> sizes[path]?.let { OpfsFile(path.removePrefix(PREFIX), it) } }
    register(
        init = { world, borders ->
            val worldData = world?.toByteArray()?.let(WorldData::decode)
            val borderLines = borders?.toByteArray()?.let(BorderLines::decode)
            service.world = WorldTile({ worldData }, { borderLines })
            service.setMaps(sizes.keys.sorted())
        },
        setMaps = { list ->
            // "name<TAB>size" per line.
            sizes.clear()
            for (line in list.lines()) {
                val name = line.substringBefore('\t')
                val size = line.substringAfter('\t', "").toLongOrNull() ?: continue
                if (name.isNotEmpty()) sizes[PREFIX + name] = size
            }
            // Opened again even when the names are the same (a map imported anew).
            service.setMaps(emptyList())
            service.setMaps(sizes.keys.sorted())
        },
        tile = { path -> service.respond(path)?.bytes?.toUint8Array() },
    )
}
