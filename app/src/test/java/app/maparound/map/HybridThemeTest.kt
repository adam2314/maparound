package app.maparound.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HybridThemeTest {
    @Test
    fun hybridThemeDropsFillsAndKeepsLinesAndLabels() {
        val xml = File("src/main/assets/themes/topo.xml").readText()
        val h = TopoTheme.hybridOf(xml)
        assertFalse("no area fills", h.contains("<area"))
        assertTrue(h.contains("map-background=\"#00000000\""))
        assertTrue(h.contains("map-background-outside=\"#00000000\""))
        // Lines, labels and symbols are all still there.
        for (tag in listOf("<line ", "<caption ", "<pathText ", "<symbol ")) {
            assertEquals(tag, Regex(Regex.escape(tag)).findAll(xml).count(), Regex(Regex.escape(tag)).findAll(h).count())
        }
        // Still well-formed: every <rule> is closed, nothing left empty.
        assertEquals(Regex("<rule\\b").findAll(h).count(), Regex("</rule>").findAll(h).count())
        assertFalse(Regex("<rule\\b[^>]*>\\s*</rule>").containsMatchIn(h))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(h.byteInputStream())
        System.getenv("HYBRID_THEME_OUT")?.let { File(it).writeText(h) }
    }
}
