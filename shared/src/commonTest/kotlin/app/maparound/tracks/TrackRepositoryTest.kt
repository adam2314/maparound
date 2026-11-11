package app.maparound.tracks

import app.maparound.data.KeyValueStore
import app.maparound.routing.GeoPoint
import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackRepositoryTest {
    private val base = (tempDir.toPath() / "tracks-test-${Random.nextLong().toULong()}").also { fileSystem.createDirectories(it) }

    private class Store : KeyValueStore {
        val m = HashMap<String, Any>()
        override fun contains(key: String) = key in m
        override fun getString(key: String) = m[key] as String?
        override fun getBoolean(key: String, default: Boolean) = default
        override fun getFloat(key: String, default: Float) = default
        override fun getInt(key: String, default: Int) = default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putFloat(key: String, value: Float) {}
        override fun putInt(key: String, value: Int) {}
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String) = m[key] as Set<String>?
        override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @AfterTest
    fun cleanUp() {
        runCatching { fileSystem.deleteRecursively(base) }
    }

    private val line = listOf(GeoPoint(47.0, 9.0, 500.0, 1_700_000_000_000), GeoPoint(47.01, 9.01, 650.0, 1_700_000_600_000))

    @Test
    fun savesRenamesHidesAndDeletes() {
        val store = Store()
        val repo = TrackRepository(base.toString(), store)
        val a = repo.save(Track("Ridge walk", TrackKind.RECORDED, line, 1_700_000_000_000))
        val b = repo.save(Track("Ridge walk", TrackKind.PLANNED, line, 1_700_000_100_000), show = false)
        assertEquals("Ridge_walk.gpx", a.id)
        assertEquals("Ridge_walk_2.gpx", b.id)
        assertEquals(listOf(b.id, a.id), repo.tracks.value.map { it.id }) // newest first
        assertEquals(setOf(a.id), repo.visible.value)

        repo.rename(a.id, "Morning ridge")
        val again = TrackRepository(base.toString(), store)
        assertEquals("Morning ridge", again.get(a.id)?.track?.name)
        assertEquals(setOf(a.id), again.visible.value)

        again.delete(a.id)
        assertEquals(listOf(b.id), again.tracks.value.map { it.id })
        assertEquals(emptySet(), again.visible.value) // a deleted track isn't shown any more
    }

    @Test
    fun importsAndRecovers() {
        val repo = TrackRepository(base.toString(), Store())
        val gpx = Gpx.toXml(Track("x", TrackKind.IMPORTED, line, 1_700_000_000_000)).encodeToByteArray()
        assertEquals("x", repo.importGpx(gpx, "Somewhere.gpx").getOrThrow().track.name) // the file's own name wins
        assertTrue(repo.importGpx("nonsense".encodeToByteArray(), "bad.gpx").isFailure)

        fileSystem.write(repo.journalPath.toPath()) {
            writeUtf8("47.0,9.0,500.0,1700000000000\n47.01,9.01,,1700000600000\nbroken line\n")
        }
        assertEquals(2, repo.readJournal().size)
        repo.recoverInterruptedRecording()
        assertTrue(repo.tracks.value.any { it.track.name.endsWith("(recovered)") && it.track.points.size == 2 })
        assertTrue(!fileSystem.exists(repo.journalPath.toPath()))
    }

    @Test
    fun defaultNames() {
        assertTrue(Regex("""Track \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(TrackRepository.defaultName(1_700_000_000_000)))
    }
}
