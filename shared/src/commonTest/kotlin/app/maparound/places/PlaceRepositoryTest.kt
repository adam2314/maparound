package app.maparound.places

import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceRepositoryTest {
    private val dir = (tempDir.toPath() / "places-test-${Random.nextLong().toULong()}").also { fileSystem.createDirectories(it) }

    @AfterTest
    fun cleanUp() {
        runCatching { fileSystem.deleteRecursively(dir) }
    }

    @Test
    fun savesEditsDeletesAndReloads() {
        val repo = PlaceRepository(dir.toString())
        assertEquals(emptyList(), repo.places.value)
        val spring = Place("s1", "Ein Prat spring", 31.832, 35.315, 120.0, PlaceCategory.WATER, "cold", 1_700_000_000_000)
        val camp = Place("c1", "Night camp", 31.80, 35.30, created = 1_700_000_100_000)
        repo.save(spring)
        repo.save(camp)
        repo.save(spring.copy(note = "cold & clear"))
        assertEquals(listOf("cold & clear", ""), repo.places.value.map { it.note })
        repo.delete("c1")

        val again = PlaceRepository(dir.toString())
        assertEquals(listOf(spring.copy(note = "cold & clear")), again.places.value)
        assertTrue(fileSystem.exists(again.path.toPath()))
        assertTrue(!fileSystem.exists(dir / "places.gpx.tmp"))
    }

    @Test
    fun importsWaypointsOnce() {
        val repo = PlaceRepository(dir.toString())
        val gpx = """<gpx><wpt lat="47.1" lon="9.5"><name>Hut</name><sym>Campground</sym></wpt>
            <wpt lat="47.2" lon="9.6"><name>Spring</name></wpt></gpx>""".encodeToByteArray()
        assertEquals(2, repo.importGpx(gpx))
        assertEquals(0, repo.importGpx(gpx)) // same names at the same spots
        assertEquals(PlaceCategory.CAMP, repo.places.value.first { it.name == "Hut" }.category)
    }

    @Test
    fun keepsAnUnreadableFileAside() {
        fileSystem.write(dir / "places.gpx") { writeUtf8("not a gpx file") }
        val repo = PlaceRepository(dir.toString())
        assertEquals(emptyList(), repo.places.value)
        assertTrue(fileSystem.list(dir).any { it.name.startsWith("places-unreadable-") })
    }
}
