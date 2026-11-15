package app.maparound.shared.stream

import app.maparound.shared.fileSystem
import app.maparound.shared.net.HttpException
import app.maparound.shared.net.httpGet
import app.maparound.shared.util.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.FileHandle
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** A streamed map can't be read now (no network, or the server didn't answer): ask again later. */
class StreamUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Map files read over the internet in pieces, as the map needs them ([open]): HTTP range requests
 * for [CHUNK]-sized pieces, kept on disk under [cacheDir] (at most [maxCacheBytes]; the oldest
 * pieces go first) and the latest in memory. Each file is checked against the server once a run
 * (the size and date in its header), so a map replaced there drops the pieces kept of the old one.
 * Without a network the pieces on disk are still read; the others throw [StreamUnavailable] (and
 * for [RETRY_MS] every read that needs the network does, without trying).
 *
 * Thread safe: the map's tile makers read from several threads.
 */
object MapStream {
    const val CHUNK = 64 * 1024
    private const val MEMORY_BYTES = 24L shl 20
    private const val RETRY_MS = 10_000L
    private const val REDO_MS = 30_000L

    /** Where the pieces are kept; null: no streaming (the platform sets it at start). */
    var cacheDir: String? = null

    var maxCacheBytes: Long = 512L shl 20

    /** Reads [length] bytes from [from] of [url]: exactly those, or it throws (tests replace it). */
    var fetch: (url: String, from: Long, length: Int) -> ByteArray = ::httpGetRange

    val enabled: Boolean get() = cacheDir != null

    /** The map file at [url], for reading like one on disk. */
    fun open(url: String): FileHandle = RemoteFile(url)

    private val _generation = MutableStateFlow(0)

    /**
     * Goes up when maps can be read again after tiles were made without them ([missed]): the map's
     * tile addresses carry it, so it asks for those tiles again (MapLibre doesn't by itself).
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** A tile was made without a map that couldn't be read. */
    fun missed() = synchronized(lock) { missedAny = true }

    /** The device has a network again (the platform tells): try at once, and redo what was missed. */
    fun networkBack() {
        synchronized(lock) { offlineUntil = 0 }
        recovered()
    }

    /** Redoes what was missed, at most every [REDO_MS] (a flaky network doesn't reload the map all the time). */
    private fun recovered() {
        val again = synchronized(lock) {
            val now = nowMillis()
            (missedAny && now - lastRedo >= REDO_MS).also {
                if (it) {
                    missedAny = false
                    lastRedo = now
                }
            }
        }
        if (again) _generation.value += 1
    }

    private val lock = SynchronizedObject()
    private val memory = LinkedHashMap<String, ByteArray>()
    private var memoryBytes = 0L
    private val checked = HashSet<String>()
    private var diskBytes = -1L
    private var offlineUntil = 0L
    private var missedAny = false
    private var lastRedo = 0L

    /** The file's size, from its header; checks the file against the server first (once a run). */
    internal fun fileSize(url: String): Long {
        val size = readLong(first(url), SIZE_AT)
        if (size < HEADER_BYTES) throw IOException("Not a map file: $url")
        return size
    }

    /** Copies [n] bytes from [offset] of [url] (whose size is [size]) into [out] at [outOffset]. */
    internal fun read(url: String, size: Long, offset: Long, out: ByteArray, outOffset: Int, n: Int) {
        if (n <= 0) return
        val key = key(url)
        val from = (offset / CHUNK).toInt()
        val to = ((offset + n - 1) / CHUNK).toInt()
        val pieces = Array(to - from + 1) { cached(key, from + it) }
        // What's missing, one request per run of pieces.
        var i = from
        while (i <= to) {
            if (pieces[i - from] != null) {
                i++
                continue
            }
            var j = i
            while (j < to && pieces[j + 1 - from] == null) j++
            val start = i.toLong() * CHUNK
            val length = (minOf((j + 1).toLong() * CHUNK, size) - start).toInt()
            val bytes = download(url, start, length)
            for (k in i..j) {
                val s = (k - i) * CHUNK
                val piece = bytes.copyOfRange(s, minOf(s + CHUNK, length))
                store(key, k, piece)
                pieces[k - from] = piece
            }
            i = j + 1
        }
        var done = 0
        while (done < n) {
            val at = offset + done
            val piece = pieces[(at / CHUNK).toInt() - from]!!
            val within = (at % CHUNK).toInt()
            val m = minOf(n - done, piece.size - within)
            if (m <= 0) throw IOException("Map piece cut short: $url")
            piece.copyInto(out, outOffset + done, within, within + m)
            done += m
        }
    }

    /** Forgets what was checked and kept in memory (tests). */
    internal fun reset() = synchronized(lock) {
        memory.clear()
        memoryBytes = 0
        checked.clear()
        diskBytes = -1
        offlineUntil = 0
        missedAny = false
        lastRedo = 0
    }

    // ---- pieces ------------------------------------------------------------------------------------

    /** The first piece (with the header), checked against the server's once a run. */
    private fun first(url: String): ByteArray {
        val key = key(url)
        val kept = cached(key, 0)
        if (kept != null && synchronized(lock) { url in checked }) return kept
        val fresh = try {
            download(url, 0, CHUNK)
        } catch (e: StreamUnavailable) {
            // Offline: what's kept is good enough until it can be checked.
            return kept ?: throw e
        }
        if (kept == null || !sameFile(kept, fresh)) drop(key)
        store(key, 0, fresh)
        synchronized(lock) { checked += url }
        return fresh
    }

    private fun download(url: String, from: Long, length: Int): ByteArray {
        if (synchronized(lock) { nowMillis() < offlineUntil }) throw StreamUnavailable("Offline")
        return try {
            fetch(url, from, length).also { recovered() }
        } catch (e: HttpException) {
            // The server is there but hasn't got it (4xx): not a network matter, that map is no good.
            if (e.status in 400..499) throw IOException("$url: ${e.message}", e)
            synchronized(lock) { offlineUntil = nowMillis() + RETRY_MS }
            throw StreamUnavailable(e.message ?: "Can't read $url", e)
        } catch (e: Exception) {
            synchronized(lock) { offlineUntil = nowMillis() + RETRY_MS }
            throw StreamUnavailable(e.message ?: "Can't read $url", e)
        }
    }

    private fun cached(key: String, index: Int): ByteArray? {
        val name = "$key/$index"
        synchronized(lock) {
            memory.remove(name)?.let {
                memory[name] = it
                return it
            }
        }
        val file = dir()?.resolve(name) ?: return null
        val bytes = runCatching { fileSystem.read(file) { readByteArray() } }.getOrNull() ?: return null
        remember(name, bytes)
        return bytes
    }

    private fun store(key: String, index: Int, bytes: ByteArray) {
        remember("$key/$index", bytes)
        val dir = dir() ?: return
        runCatching {
            val folder = dir.resolve(key)
            fileSystem.createDirectories(folder)
            // Written whole, then put in place: never a piece cut short by a crash.
            val part = folder.resolve("$index.part${nowMillis() % 100_000}")
            fileSystem.write(part) { write(bytes) }
            fileSystem.atomicMove(part, folder.resolve("$index"))
        }
        val trim = synchronized(lock) {
            if (diskBytes >= 0) diskBytes += bytes.size
            diskBytes < 0 || diskBytes > maxCacheBytes
        }
        if (trim) trimDisk(dir)
    }

    private fun remember(name: String, bytes: ByteArray) = synchronized(lock) {
        memory.remove(name)?.let { memoryBytes -= it.size }
        memory[name] = bytes
        memoryBytes += bytes.size
        val it = memory.entries.iterator()
        while (memoryBytes > MEMORY_BYTES && it.hasNext()) {
            memoryBytes -= it.next().value.size
            it.remove()
        }
    }

    /** Drops the pieces kept of a file (replaced on the server). */
    private fun drop(key: String) {
        synchronized(lock) {
            val it = memory.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.startsWith("$key/")) {
                    memoryBytes -= e.value.size
                    it.remove()
                }
            }
            diskBytes = -1
        }
        dir()?.let { runCatching { fileSystem.deleteRecursively(it.resolve(key)) } }
    }

    /** Counts what's on disk; over the limit, deletes the oldest pieces down to three quarters of it. */
    private fun trimDisk(dir: Path) {
        val files = runCatching {
            fileSystem.listRecursively(dir).mapNotNull { p ->
                fileSystem.metadataOrNull(p)?.takeIf { it.isRegularFile }?.let { p to it }
            }.toList()
        }.getOrNull() ?: return
        var total = files.sumOf { it.second.size ?: 0L }
        if (total > maxCacheBytes) {
            for ((p, m) in files.sortedBy { it.second.lastModifiedAtMillis ?: 0L }) {
                if (total <= maxCacheBytes / 4 * 3) break
                runCatching { fileSystem.delete(p) }
                total -= m.size ?: 0L
                val name = p.parent?.name + "/" + p.name
                synchronized(lock) { memory.remove(name)?.let { memoryBytes -= it.size } }
            }
        }
        synchronized(lock) { diskBytes = total }
    }

    private fun dir(): Path? = cacheDir?.toPath()

    // ---- the header ----------------------------------------------------------------------------------

    /** A mapsforge header: magic (20), header size (4), version (4), file size (8), date (8). */
    private const val SIZE_AT = 28
    private const val HEADER_BYTES = 44L

    private fun sameFile(a: ByteArray, b: ByteArray): Boolean =
        a.size >= HEADER_BYTES && b.size >= HEADER_BYTES && (0 until HEADER_BYTES.toInt()).all { a[it] == b[it] }

    private fun readLong(b: ByteArray, at: Int): Long {
        if (b.size < at + 8) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** A folder name for [url] (FNV-1a, 64 bits). */
    private fun key(url: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in url) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}

/**
 * [length] bytes of [url] from [from], by an HTTP range request: exactly those, or it throws (a
 * server that sends the whole file instead is no good for streaming).
 */
fun httpGetRange(url: String, from: Long, length: Int, timeoutMs: Int = 20_000): ByteArray {
    val bytes = httpGet(url, mapOf("Range" to "bytes=$from-${from + length - 1}", "Accept-Encoding" to "identity"), timeoutMs)
    if (bytes.size != length) throw IOException("Asked $url for $length bytes, got ${bytes.size}")
    return bytes
}

/** A map file on a server, read through [MapStream]. */
private class RemoteFile(private val url: String) : FileHandle(false) {
    private var length = -1L

    private fun length(): Long {
        if (length < 0) length = MapStream.fileSize(url)
        return length
    }

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        val size = length()
        if (fileOffset >= size) return -1
        val n = minOf(byteCount.toLong(), size - fileOffset).toInt()
        MapStream.read(url, size, fileOffset, array, arrayOffset, n)
        return n
    }

    override fun protectedSize(): Long = length()

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
        throw UnsupportedOperationException("read only")

    override fun protectedFlush() = Unit
    override fun protectedResize(size: Long) = throw UnsupportedOperationException("read only")
    override fun protectedClose() = Unit
}
