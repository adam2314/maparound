package app.maparound.tracks

import app.maparound.routing.GeoPoint
import app.maparound.routing.TrackStats
import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingJournalTest {
    private val dir = (tempDir.toPath() / "journal-test-${Random.nextLong().toULong()}").also { fileSystem.createDirectories(it) }
    private val path = (dir / "recording.journal").toString()

    @AfterTest
    fun cleanUp() {
        runCatching { fileSystem.deleteRecursively(dir) }
    }

    private val a = GeoPoint(31.7681234, 35.1584321, 721.5, 1_700_000_000_000)
    private val b = GeoPoint(-33.9, -70.6, null, 1_700_000_001_000)
    private val c = GeoPoint(31.77, 35.16, 730.0, 1_700_000_002_000)

    @Test
    fun keepsThePointsAcrossARestart() {
        val j = RecordingJournal(path)
        assertTrue(j.append(a))
        assertTrue(j.append(b))
        // The app is killed here: no close. The points are in the file all the same.
        assertEquals(listOf(a, b), RecordingJournal.read(path))
        // Restarted: the recording goes on in the same file.
        val again = RecordingJournal(path)
        assertTrue(again.append(c))
        again.close()
        assertEquals(listOf(a, b, c), RecordingJournal.read(path))
    }

    @Test
    fun aLineCutShortIsLeftOut() {
        // Killed mid-line: the next point starts on a line of its own, and the broken one is left out.
        fileSystem.write(path.toPath()) { writeUtf8(RecordingJournal.line(a) + "31.77,35.1") }
        RecordingJournal(path).apply { append(c); close() }
        assertEquals(listOf(a, c), RecordingJournal.read(path))
    }

    @Test
    fun aTimeCutShortIsDropped() {
        fileSystem.write(path.toPath()) { writeUtf8("31.5,35.3,12.3,170000\n91.0,35.3,,1700000000000\n") }
        val points = RecordingJournal.read(path)
        assertEquals(1, points.size) // latitude 91 isn't a place
        assertNull(points[0].time)
        assertEquals(12.3, points[0].ele)
    }

    @Test
    fun syncsOnItsClockAndSaysWhenItCantWrite() {
        var now = 0L
        val j = RecordingJournal(path, syncEveryMs = 30_000) { now }
        assertTrue(j.append(a))
        now = 31_000
        j.syncIfDue() // forced onto the storage (nothing to see but that it goes on)
        assertTrue(j.append(b))
        j.close()
        assertEquals(listOf(a, b), RecordingJournal.read(path))
        // Nowhere to write: the recording goes on in memory, and says so.
        assertFalse(RecordingJournal((dir / "missing" / "recording.journal").toString()).append(a))
    }

    @Test
    fun recorderStatsFollowThePoints() {
        TrackRecorder.begin(a.time!!, listOf(a, c))
        TrackRecorder.append(b, 5f)
        assertEquals(TrackStats.of(listOf(a, c, b)), TrackRecorder.state.value.stats)
        // A new recording starts from nothing.
        TrackRecorder.end()
        TrackRecorder.begin(c.time!!, emptyList())
        assertEquals(TrackStats.EMPTY, TrackRecorder.state.value.stats)
        TrackRecorder.append(c, 5f)
        assertEquals(TrackStats.of(listOf(c)), TrackRecorder.state.value.stats)
        TrackRecorder.end()
    }
}
