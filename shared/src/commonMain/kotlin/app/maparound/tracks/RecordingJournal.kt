package app.maparound.tracks

import app.maparound.routing.GeoPoint
import app.maparound.shared.fileSystem
import app.maparound.shared.util.fixed
import app.maparound.shared.util.nowMillis
import okio.FileHandle
import okio.Path.Companion.toPath

/**
 * A recording's points on storage as they come, one "lat,lon,ele,time" line each, so a recording
 * outlives the app being closed or killed (see [TrackRepository.recoverInterruptedRecording]).
 *
 * Each point is handed to the system at once (an app that's killed loses nothing), and every
 * [syncEveryMs] the file is forced onto the storage itself, so a phone that dies, crashes or
 * restarts loses at most that last stretch. Not thread safe: use it from one thread (the
 * forcing waits on the storage, so not the main one).
 */
class RecordingJournal(
    private val path: String,
    private val syncEveryMs: Long = SYNC_EVERY_MS,
    private val now: () -> Long = ::nowMillis,
) {
    private var handle: FileHandle? = null

    /** Where the next line goes: the file's end. */
    private var end = 0L
    private var lastSync = 0L
    private var unsynced = false

    /**
     * Writes [p] after the others; false when it couldn't be (storage full?): it's tried again
     * with the next point, and the recording goes on in memory meanwhile.
     */
    fun append(p: GeoPoint): Boolean = try {
        // Straight to the file: okio's sinks force the storage at every flush.
        write(handle ?: open(), line(p).encodeToByteArray())
        unsynced = true
        syncIfDue()
        true
    } catch (e: Exception) {
        // Broken (the storage filled up, the file went): opened afresh next time.
        drop()
        false
    }

    /**
     * Forces what was written onto the storage when the last time was [syncEveryMs] ago; call it
     * now and then without points too (standing still), so the last ones get there in time.
     */
    fun syncIfDue() {
        if (unsynced && now() - lastSync >= syncEveryMs) sync()
    }

    /** Forces what was written onto the storage. */
    fun sync() {
        val h = handle ?: return
        if (!unsynced) return
        runCatching { h.flush() }
        unsynced = false
        lastSync = now()
    }

    /** Forces the rest onto the storage and closes the file. */
    fun close() {
        sync()
        drop()
    }

    private fun open(): FileHandle {
        val h = fileSystem.openReadWrite(path.toPath())
        handle = h
        lastSync = now()
        end = h.size()
        // A line cut short when the app was killed mid-write: the next one starts on its own line.
        val last = ByteArray(1)
        if (end > 0 && h.read(end - 1, last, 0, 1) == 1 && last[0] != NEWLINE) write(h, byteArrayOf(NEWLINE))
        return h
    }

    private fun write(h: FileHandle, bytes: ByteArray) {
        h.write(end, bytes, 0, bytes.size)
        end += bytes.size
    }

    private fun drop() {
        runCatching { handle?.close() }
        handle = null
    }

    companion object {
        /** How often the journal is forced onto the storage: what a phone that dies may lose. */
        const val SYNC_EVERY_MS = 30_000L

        fun line(p: GeoPoint): String =
            "${fixed(p.lat, 7)},${fixed(p.lon, 7)},${p.ele?.let { fixed(it, 1) } ?: ""},${p.time ?: 0L}\n"

        /**
         * The points of the journal at [path]; lines that aren't whole points (one cut short by a
         * crash) are left out.
         */
        fun read(path: String): List<GeoPoint> {
            val file = path.toPath()
            if (!fileSystem.exists(file)) return emptyList()
            return fileSystem.read(file) { readUtf8() }.lines().mapNotNull { line ->
                val p = line.split(',')
                if (p.size != 4) return@mapNotNull null
                val lat = p[0].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
                val lon = p[1].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
                // A time cut short reads as one long ago: none rather than a wrong one.
                val time = p[3].toLongOrNull()?.takeIf { it > MIN_TIME }
                GeoPoint(lat, lon, p[2].toDoubleOrNull(), time)
            }
        }

        /** 2001-09-09: any real fix is later. */
        private const val MIN_TIME = 1_000_000_000_000L
        private const val NEWLINE = '\n'.code.toByte()
    }
}
