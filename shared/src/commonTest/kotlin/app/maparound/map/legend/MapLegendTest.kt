package app.maparound.map.legend

import app.maparound.i18n.I18n
import app.maparound.shared.fileSystem
import app.maparound.shared.testEnv
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLegendTest {
    @AfterTest
    fun english() = I18n.apply("en")

    @Test
    fun aSmallThemesLegend() {
        val xml = """<?xml version="1.0"?>
            <rendertheme xmlns="http://mapsforge.org/renderTheme" version="5" map-background="#F8F4F0">
              <stylemenu id="m" defaultvalue="all"><layer id="all"><cat id="pois"/></layer></stylemenu>
              <hillshading zoom-min="9" zoom-max="16" magnitude="80"/>
              <rule e="way" k="highway" v="track" zoom-min="13">
                <line stroke="#8D6E63" stroke-width="2" />
                <rule e="way" k="tracktype" v="grade4|grade5"><line stroke="#FFFFFF" stroke-width="1" stroke-dasharray="4,3" /></rule>
              </rule>
              <rule e="way" k="highway" v="path" zoom-min="14"><line stroke="#C62828" stroke-width="1.2" stroke-dasharray="3,2" /></rule>
              <rule e="way" k="natural" v="water" closed="yes"><area fill="#AAD3DF" /></rule>
              <rule e="node" k="natural" v="spring" zoom-min="14" cat="pois"><symbol src="assets:symbols/spring.svg" /></rule>
              <rule e="node" k="natural" v="peak" zoom-min="20"><symbol src="assets:symbols/peak.svg" /></rule>
            </rendertheme>"""
        val legend = MapLegend.build(xml)
        val byTitle = legend.associateBy { it.title }

        // Track grades the style draws apart show; those drawn as the plain track don't.
        val tracks = byTitle.getValue("Tracks").entries
        assertEquals(listOf("Track", "Track, mostly soft", "Track, soft (sand, grass)"), tracks.map { it.label })
        assertEquals(2, tracks[1].sample.lines.size)
        assertEquals(listOf(4f, 3f), tracks[1].sample.lines[1].dash)
        assertEquals(0xFF8D6E63, tracks[0].sample.lines[0].color)

        // A path whose difficulty this style doesn't show: just "Path".
        assertEquals(listOf("Path"), byTitle.getValue("Paths").entries.map { it.label })

        val water = byTitle.getValue("Water").entries
        assertEquals(0xFFAAD3DF, water.first { it.label == "Lake, pond" }.sample.areas.single().fill)
        assertEquals("symbols/spring.svg", water.first { it.label == "Spring" }.sample.symbol)

        // Terrain: hill shading, and no peak (this style draws it only past zoom 20).
        val terrain = byTitle.getValue("Terrain").entries
        assertTrue(terrain.single().sample.hillshade)
        assertNull(byTitle["Roads"])

        // A style option that's off: its symbols go.
        val noPois = MapLegend.build(xml, categories = emptySet())
        assertFalse(noPois.flatMap { it.entries }.any { it.sample.symbol != null })

        // In Hebrew.
        I18n.apply("he")
        assertEquals("דרך עפר", MapLegend.build(xml).first { it.title == "דרכי עפר" }.entries.first().label)

        assertTrue(MapLegend.build("not a theme").isEmpty())
        assertEquals(0xFF112233, MapLegend.color("#123"))
        assertEquals(0x80FF0000, MapLegend.color("#80FF0000"))
        assertNull(MapLegend.color("#00FFFFFF"))
    }

    @Test
    fun topoLegend() {
        val path = (testEnv("THEME_FILE") ?: "../app/src/main/assets/themes/topo.xml").toPath()
        if (!fileSystem.exists(path)) return
        val legend = MapLegend.build(fileSystem.read(path) { readUtf8() })
        val titles = legend.map { it.title }
        for (t in listOf("Roads", "Tracks", "Paths", "Marked trails", "Water", "Land", "Terrain")) assertTrue(t in titles, "$t in $titles")
        val all = legend.flatMap { it.entries }
        // Topo tells track grades apart and draws marked trails in their colours.
        assertTrue(all.count { it.label.startsWith("Track,") } >= 3)
        val red = all.first { it.label == "Trail marked red" }.sample.lines
        assertTrue(red.any { it.color == 0xFFD32F2F })
        assertNotNull(all.firstOrNull { it.label == "Contour line" })
        assertTrue(all.any { it.sample.symbol != null })
    }
}
