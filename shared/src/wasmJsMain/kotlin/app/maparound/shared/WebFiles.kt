package app.maparound.shared

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Timeout
import okio.fakefilesystem.FakeFileSystem
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The web app's files. A browser has no file system the shared code could use directly, so files
 * live in memory (Okio's [FakeFileSystem]) and everything under [ROOT] is saved to the browser's
 * IndexedDB a moment after it changes, and read back at start ([load], before the screens open).
 * Big files (offline maps) will need the browser's file storage instead (next step).
 */
object WebFiles {
    /** What's kept between visits: places, tracks, settings files. */
    const val ROOT = "/data"

    private val clock = SettableClock()
    private val memory = FakeFileSystem(clock).apply { emulateUnix() }

    val fs: FileSystem = object : ForwardingFileSystem(memory) {
        override fun sink(file: Path, mustCreate: Boolean): Sink = watched(super.sink(file, mustCreate), file)

        override fun appendingSink(file: Path, mustExist: Boolean): Sink = watched(super.appendingSink(file, mustExist), file)

        override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
            super.openReadWrite(file, mustCreate, mustExist).also { changed(file) }

        override fun createDirectory(dir: Path, mustCreate: Boolean) {
            super.createDirectory(dir, mustCreate)
            changed(dir)
        }

        override fun delete(path: Path, mustExist: Boolean) {
            super.delete(path, mustExist)
            changed(path)
        }

        override fun atomicMove(source: Path, target: Path) {
            super.atomicMove(source, target)
            changed(source)
            changed(target)
        }
    }

    private fun watched(sink: Sink, file: Path): Sink = object : Sink {
        override fun write(source: Buffer, byteCount: Long) = sink.write(source, byteCount)
        override fun flush() = sink.flush()
        override fun timeout(): Timeout = sink.timeout()
        override fun close() {
            sink.close()
            changed(file)
        }
    }

    private val dirty = LinkedHashSet<String>()
    private var flushPending = false

    private fun changed(path: Path) {
        val p = path.normalized().toString()
        if (p != ROOT && !p.startsWith("$ROOT/")) return
        dirty += p
        if (!flushPending) {
            flushPending = true
            setTimeout(400) { flush() }
        }
    }

    /** Saves what changed to IndexedDB. */
    fun flush() {
        flushPending = false
        val paths = dirty.toList()
        dirty.clear()
        for (p in paths) {
            val path = p.toPath()
            val meta = memory.metadataOrNull(path)
            val time = (meta?.lastModifiedAtMillis ?: 0L).toDouble()
            when {
                meta == null -> idbDelete(p)
                meta.isDirectory -> idbPut(p, null, time, true)
                else -> idbPut(p, memory.read(path) { readByteArray() }.toUint8Array(), time, false)
            }
        }
    }

    /** Reads the saved files into memory. Call once, before anything uses [fs]. */
    suspend fun load() {
        idbInit()
        memory.createDirectories(ROOT.toPath())
        memory.createDirectories("/tmp".toPath())
        suspendCoroutine { cont ->
            idbLoadAll(
                onEntry = { path, bytes, time, dir ->
                    runCatching {
                        val p = path.toPath()
                        clock.fixed = Instant.fromEpochMilliseconds(time.toLong())
                        if (dir) {
                            memory.createDirectories(p)
                        } else {
                            p.parent?.let { memory.createDirectories(it) }
                            val data = bytes?.toByteArray() ?: ByteArray(0)
                            memory.write(p) { write(data) }
                        }
                    }
                    clock.fixed = null
                },
                onDone = { cont.resume(Unit) },
            )
        }
    }

    /** Asks the browser to keep the app's data even when storage runs low. */
    fun persist() = requestPersistentStorage()
}

private fun requestPersistentStorage(): Unit = js("{ if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); }")

/** The system clock, or a fixed time while saved files are restored (they keep their dates). */
private class SettableClock : Clock {
    var fixed: Instant? = null
    override fun now(): Instant = fixed ?: Clock.System.now()
}

// ---- IndexedDB: one store, path → { bytes, mtime, dir } ------------------------------------------

private fun idbInit(): Unit = js(
    """{
    if (!globalThis.__maparoundDb) {
        globalThis.__maparoundDb = new Promise((resolve, reject) => {
            const r = indexedDB.open('maparound-files', 1);
            r.onupgradeneeded = () => r.result.createObjectStore('files');
            r.onsuccess = () => resolve(r.result);
            r.onerror = () => reject(r.error);
        });
    }
}""",
)

private fun idbPut(path: String, bytes: JsAny?, mtime: Double, dir: Boolean): Unit = js(
    """{
    globalThis.__maparoundDb.then(db => {
        db.transaction('files', 'readwrite').objectStore('files').put({ bytes: bytes, mtime: mtime, dir: dir }, path);
    });
}""",
)

private fun idbDelete(path: String): Unit = js(
    """{
    globalThis.__maparoundDb.then(db => { db.transaction('files', 'readwrite').objectStore('files').delete(path); });
}""",
)

private fun idbLoadAll(onEntry: (String, JsAny?, Double, Boolean) -> Unit, onDone: () -> Unit): Unit = js(
    """{
    globalThis.__maparoundDb.then(db => {
        const req = db.transaction('files', 'readonly').objectStore('files').openCursor();
        req.onsuccess = () => {
            const c = req.result;
            if (c) {
                const v = c.value || {};
                onEntry(String(c.key), v.bytes || null, v.mtime || 0, !!v.dir);
                c.continue();
            } else {
                onDone();
            }
        };
        req.onerror = () => onDone();
    }).catch(() => onDone());
}""",
)

// ---- the shared code's platform functions ---------------------------------------------------------

actual val fileSystem: FileSystem get() = WebFiles.fs

actual val tempDir: String = "/tmp"

actual fun openFile(path: String): FileHandle = fileSystem.openReadOnly(path.toPath())

/** Read into memory (the browser can't map files). */
actual fun mapFile(path: String): MappedFile {
    val bytes = fileSystem.read(path.toPath()) { readByteArray() }
    require(bytes.isNotEmpty()) { "Empty file: $path" }
    return object : MappedFile {
        override val size: Long = bytes.size.toLong()
        override fun shortBE(pos: Long): Short {
            require(pos >= 0 && pos + 1 < size) { "Offset $pos outside the file" }
            val i = pos.toInt()
            return (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toShort()
        }
    }
}

/** The browser runs the app on one thread: "background" work runs between frames. */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
