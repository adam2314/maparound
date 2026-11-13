package app.maparound.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapAroundCatalogTest {
    /** As my-pipeline's maps.py writes it. */
    private val json = """
        {
         "version": 1,
         "generated": "2026-11-16T09:12:00Z",
         "attribution": "Map data (c) OpenStreetMap contributors, ODbL (openstreetmap.org/copyright)",
         "maps": [
          {"region": "europe/austria", "path": "europe/austria", "url": "https://github.com/o/r/releases/download/maps-2026-11/europe__austria.map",
           "size": 754974720, "sha256": "ab", "date": "2026-11-12T21:23:05Z", "bbox": [46.27, 9.43, 49.12, 17.26]},
          {"region": "europe/germany", "path": "europe/germany", "status": "needs-funding", "osmBytes": 4400000000},
          {"region": "europe/germany/bayern", "path": "europe/germany/bayern", "url": "https://x/europe__germany__bayern.map",
           "size": 900000000, "sha256": "cd", "date": "2026-11-12T21:23:05Z", "bbox": [47.17, 8.87, 50.66, 13.94]},
          {"region": "europe/malta", "path": "europe/malta", "status": "unavailable"},
          {"region": "russia/far-eastern-fed-district", "path": "russia/far-eastern-fed-district-1", "url": "https://x/a-1.map",
           "size": 1000, "sha256": "ef", "date": "2026-11-12T21:23:05Z", "bbox": [0, 0, 1, 1]}
         ]
        }
    """.trimIndent()

    @Test
    fun readsTheCatalog() {
        val all = MapAroundCatalog.parse(json)
        assertEquals(5, all.size)
        val austria = all[0]
        assertEquals("europe/austria.map", austria.path)
        assertEquals("europe__austria.map", austria.localFileName)
        assertEquals("720M", austria.sizeLabel)
        assertEquals(1794518585000, austria.modified)
        assertNull(austria.unavailable)
        assertEquals(CatalogEntry.Unavailable.NEEDS_FUNDING, all[1].unavailable)
        assertNull(all[1].url)
        assertEquals(CatalogEntry.Unavailable.NOT_BUILT, all[3].unavailable)
        assertEquals("Far Eastern Fed District 1", all[4].title)
    }

    @Test
    fun foldersLikeTheMapsforgeServer() {
        val all = MapAroundCatalog.parse(json)
        assertEquals(listOf("europe/", "russia/"), MapAroundCatalog.listing(all, "").map { it.path })
        assertTrue(MapAroundCatalog.listing(all, "").all { it.isDirectory })
        // A country that's too big as a whole, and its regions in a folder beside it.
        assertEquals(
            listOf("europe/austria.map", "europe/germany.map", "europe/germany/", "europe/malta.map"),
            MapAroundCatalog.listing(all, "europe/").map { it.path },
        )
        assertEquals(listOf("europe/germany/bayern.map"), MapAroundCatalog.listing(all, "europe/germany/").map { it.path })
    }

    @Test
    fun sizes() {
        assertEquals("850K", MapAroundCatalog.sizeLabel(850L * 1024))
        assertEquals("3.1M", MapAroundCatalog.sizeLabel((3.15 * 1024 * 1024).toLong()))
        assertEquals("43M", MapAroundCatalog.sizeLabel(43L * 1024 * 1024))
        assertEquals("1.2G", MapAroundCatalog.sizeLabel((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    private class FakeSource(val name: String) : CatalogSource {
        override suspend fun list(path: String, forceRefresh: Boolean) =
            listOf(CatalogEntry("$path$name.map", false, null, null, null))
        override fun urlFor(entry: CatalogEntry) = "https://fallback/${entry.path}"
    }

    @Test
    fun theMapsforgeMapsWhileTheListCantBeRead() = runTest {
        // An address that can't be reached: the fallback's maps, and its addresses.
        val catalog = MapAroundCatalog(catalogUrl = "http://127.0.0.1:9/catalog.json", fallback = FakeSource("andorra"))
        val list = catalog.list("europe/")
        assertEquals(listOf("europe/andorra.map"), list.map { it.path })
        assertEquals("https://fallback/europe/andorra.map", catalog.urlFor(list[0]))
        assertEquals("OpenStreetMap data via mapsforge.org", catalog.attribution)
    }
}
