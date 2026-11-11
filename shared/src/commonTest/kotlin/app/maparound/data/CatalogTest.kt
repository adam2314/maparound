package app.maparound.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val html = """
        <tr><td valign="top"><img src="/icons/back.gif" alt="[PARENTDIR]"></td><td><a href="/maps/v5/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td><td>&nbsp;</td></tr>
        <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="austria.map">austria.map</a>            </td><td align="right">2026-08-21 20:21  </td><td align="right">528M</td><td>&nbsp;</td></tr>
        <tr><td valign="top"><img src="/icons/folder.gif" alt="[DIR]"></td><td><a href="germany/">germany/</a>               </td><td align="right">2026-08-21 21:02  </td><td align="right">  - </td><td>&nbsp;</td></tr>
        <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="bosnia-herzegovina.map">bosnia-herzegovina.map</a></td><td align="right">2026-08-21 20:25  </td><td align="right"> 43M</td><td>&nbsp;</td></tr>
        <tr><td valign="top"><img src="/icons/text.gif" alt="[TXT]"></td><td><a href="readme.txt">readme.txt</a></td><td align="right">2026-08-21 20:25  </td><td align="right">1.2K</td><td>&nbsp;</td></tr>
    """

    @Test
    fun parsesApacheListing() {
        val e = Catalog.parseListing(html, "europe/")
        assertEquals(listOf("europe/austria.map", "europe/germany/", "europe/bosnia-herzegovina.map"), e.map { it.path })
        val austria = e[0]
        assertEquals("Austria", austria.title)
        assertEquals(528L shl 20, austria.sizeBytes)
        assertEquals(1_787_343_660_000, austria.modified) // 2026-08-21 20:21 UTC
        assertTrue(e[1].isDirectory)
        assertNull(e[1].sizeBytes)
        assertEquals("Bosnia Herzegovina", e[2].title)
        assertEquals("europe__bosnia-herzegovina.map", e[2].localFileName)
        assertEquals(1228L, Catalog.parseSize("1.2K")) // 1.2 × 1024, rounded down
    }

    @Test
    fun localNames() {
        val us = MapNames.localFileName("north-america/us/georgia.map")
        assertEquals("north-america__us__georgia.map", us)
        assertEquals("Georgia", MapNames.title(us))
        assertEquals("North America › US", MapNames.region(us))
        assertEquals("Ireland and Northern Ireland", MapNames.pretty("ireland-and-northern-ireland.map"))
        assertTrue(MapNames.isWorld(MapNames.localFileName("world/world.map")))
    }
}
