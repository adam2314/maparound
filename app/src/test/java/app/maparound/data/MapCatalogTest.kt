package app.maparound.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCatalogTest {
    private val html = javaClass.classLoader!!.getResource("europe_listing.html")!!.readText()

    @Test
    fun parsesFilesAndFoldersFromApacheListing() {
        val entries = MapCatalog.parseListing(html, "europe/")
        assertEquals(49, entries.count { !it.isDirectory })
        assertEquals(6, entries.count { it.isDirectory })
        assertTrue("parent directory link must be skipped", entries.none { it.path.contains("maps/v5") })

        val austria = entries.first { it.path == "europe/austria.map" }
        assertFalse(austria.isDirectory)
        assertEquals("Austria", austria.title)
        assertNotNull(austria.sizeBytes)
        assertNotNull(austria.modified)

        val germanyDir = entries.first { it.path == "europe/germany/" }
        assertTrue(germanyDir.isDirectory)
        assertNull(germanyDir.sizeBytes)
    }

    @Test
    fun parsesSizes() {
        assertEquals(43L shl 20, MapCatalog.parseSize("43M"))
        assertEquals((2.4 * (1 shl 20)).toLong(), MapCatalog.parseSize("2.4M"))
        assertEquals((3.0 * (1L shl 30)).toLong(), MapCatalog.parseSize("3.0G"))
        assertEquals(980L, MapCatalog.parseSize("980"))
    }

    @Test
    fun localNamesAreUniqueAndReadable() {
        val eu = MapNames.localFileName("europe/georgia.map")
        val us = MapNames.localFileName("north-america/us/georgia.map")
        assertEquals("europe__georgia.map", eu)
        assertEquals("north-america__us__georgia.map", us)
        assertEquals("Georgia", MapNames.title(us))
        assertEquals("North America › US", MapNames.region(us))
        assertEquals("Ireland and Northern Ireland", MapNames.pretty("ireland-and-northern-ireland.map"))
        assertTrue(MapNames.isWorld(MapNames.localFileName("world/world.map")))
    }
}
