package app.maparound.map

import app.maparound.map.maplibre.ThemeStyle
import app.maparound.shared.fileSystem
import app.maparound.shared.testEnv
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleMenuTest {
    /** The Android app's theme (THEME_FILE on the iOS simulator). */
    private fun topo(): String? {
        val path = (testEnv("THEME_FILE") ?: "../app/src/main/assets/themes/topo.xml").toPath()
        return if (fileSystem.exists(path)) fileSystem.read(path) { readUtf8() } else null
    }

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rendertheme xmlns="http://mapsforge.org/renderTheme" version="5">
          <stylemenu id="m" defaultvalue="b" defaultlang="de">
            <layer id="base"><cat id="always" /></layer>
            <layer id="o1" enabled="true"><name lang="de" value="Eins" /><cat id="c1" /></layer>
            <layer id="o2"><name lang="en" value="Two" /><name lang="de" value="Zwei" /><cat id="c2" /></layer>
            <layer id="a" parent="base" visible="true"><name lang="en" value="A" /><overlay id="o1" /></layer>
            <layer id="b" parent="a" visible="true"><cat id="b" /><overlay id="o2" /></layer>
          </stylemenu>
          <rule e="any" k="*" v="*" cat="c1"><line stroke="#000000" /></rule>
        </rendertheme>
    """.trimIndent()

    @Test
    fun readsStylesOverlaysAndInheritance() {
        val menu = assertNotNull(StyleMenu.parse(sample))
        assertEquals("m", menu.id)
        assertEquals(listOf("a", "b"), menu.styles.map { it.id })
        // No choice yet: the theme's default style.
        val b = menu.style(null)!!
        assertEquals("b", b.id)
        // b gets a's categories (and so base's) and overlays.
        assertEquals(setOf("always", "b"), b.categories)
        assertEquals(listOf("o1", "o2"), menu.overlays(b).map { it.id })
        assertEquals("Eins", menu.name(menu.layers.getValue("o1"))) // the default language when there's no English
        assertEquals("Two", menu.name(menu.layers.getValue("o2")))
        assertEquals("b", menu.name(b)) // no name at all: the id
        // A style that isn't there falls back to the default.
        assertEquals("b", menu.style("gone")!!.id)
    }

    @Test
    fun choicesBecomeCategories() {
        val menu = StyleMenu.parse(sample)!!
        assertEquals(setOf("always", "b", "c1"), menu.categories(StyleChoice()))
        assertEquals(setOf("always", "b", "c2"), menu.categories(StyleChoice(overlays = mapOf("o1" to false, "o2" to true))))
        assertEquals(setOf("always", "c1"), menu.categories(StyleChoice(style = "a")))
    }

    @Test
    fun noMenuMeansEverything() {
        assertNull(StyleMenu.parse("""<rendertheme version="5"><rule e="any" k="*" v="*" /></rendertheme>"""))
    }

    @Test
    fun topoHasItsOptions() {
        val xml = topo() ?: return
        val menu = assertNotNull(StyleMenu.parse(xml))
        val topo = menu.style(null)!!
        assertEquals("topo", topo.id)
        val overlays = menu.overlays(topo).map { it.id }
        assertEquals(listOf("landcover", "buildings", "boundaries", "transport", "waynames", "places", "natural", "pois"), overlays)
        // All on at first; each overlay's category is used by the theme's rules.
        val all = menu.categories(StyleChoice())
        assertEquals(overlays.toSet(), all)
        for (cat in all) assertTrue(xml.contains("<rule cat=\"$cat\""), cat)

        // On iPhone the options become fewer MapLibre layers; all on is the same as no filter.
        val full = ThemeStyle.convert(xml).layers.size
        assertEquals(full, ThemeStyle.convert(xml, all).layers.size)
        val noPois = ThemeStyle.convert(xml, menu.categories(StyleChoice(overlays = mapOf("pois" to false)))).layers.size
        assertTrue(noPois < full, "$noPois < $full")
        // The menu itself never becomes a layer.
        assertTrue(ThemeStyle.convert(xml).layers.none { it.toString().contains("stylemenu") || it.toString().contains("\"overlay\"") })
    }
}
