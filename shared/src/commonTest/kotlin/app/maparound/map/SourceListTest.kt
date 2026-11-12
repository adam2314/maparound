package app.maparound.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceListTest {
    private val mine = OnlineSource(
        id = "custom_1", name = "My topo", urlTemplate = "https://{s}.tiles.example.org/topo/{z}/{x}/{y}.png",
        maxZoom = 17, attribution = "© Example", subdomains = listOf("a", "b"), custom = true,
    )

    @Test
    fun exportThenImportGivesTheSameSources() {
        val text = SourceList.encode(listOf(mine, TileSources.builtIn.first { it.id == "ign_plan" }))
        assertTrue(text.contains(SourceList.TYPE))
        val back = SourceList.decode(text).getOrThrow()
        assertEquals(listOf("My topo", "Plan IGN"), back.map { it.name })
        assertEquals(mine.copy(id = ""), back[0])
        assertEquals(TileSources.builtIn.first { it.id == "ign_plan" }.urlTemplate, back[1].urlTemplate)
    }

    @Test
    fun readsPlainListsAndOtherJson() {
        val text = """
            # my sources
            Hiking map | https://hike.example.com/{z}/{x}/{y}.png
            https://tiles.example.net/{z}/{x}/{-y}.jpg

            not a source: https://example.com/page
        """.trimIndent()
        val found = SourceList.decode(text).getOrThrow()
        assertEquals(listOf("Hiking map", "tiles.example.net"), found.map { it.name })
        assertEquals("https://tiles.example.net/{z}/{x}/{-y}.jpg", found[1].urlTemplate)

        val array = """[{"title": "A", "urlTemplate": "https://a.example/{z}/{x}/{y}.png", "max_zoom": 15}, {"name": "broken"}]"""
        val a = SourceList.decode(array).getOrThrow().single()
        assertEquals("A", a.name)
        assertEquals(15, a.maxZoom)
    }

    @Test
    fun saysWhenThereIsNothing() {
        assertTrue(SourceList.decode("hello").isFailure)
        assertTrue(SourceList.decode("""{"sources": []}""").isFailure)
    }

    @Test
    fun theBlockedOsmServerIsReplaced() {
        assertTrue(TileSources.builtIn.none { "tile.openstreetmap.org" in it.urlTemplate })
        assertEquals(TileSources.DEFAULT_ID, TileSources.current("osm"))
        assertEquals("opentopomap", TileSources.current("opentopomap"))
        assertTrue(TileSources.builtIn.any { it.id == TileSources.DEFAULT_ID })
    }
}
