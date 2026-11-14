package app.maparound.data

import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

class ElevationSourcesTest {
    @Test
    fun theDefaultTiles() {
        assertEquals(
            "https://elevation-tiles-prod.s3.amazonaws.com/skadi/N47/N47E009.hgt.gz",
            ElevationSources.tileUrl(null, "N47E009"),
        )
        assertTrue(ElevationSources.isGzip(ElevationSources.tileUrl(null, "S01W071")))
        assertNull(ElevationSources.customHost(null))
        assertEquals(ElevationSources.DEFAULT_SITE, ElevationSources.site(null))
    }

    @Test
    fun anotherSource() {
        val t = ElevationSources.tilesTemplate("  https://dem.example.org/cop30/{name}.hgt?key=1 ")!!
        assertEquals("https://dem.example.org/cop30/N32E035.hgt?key=1", ElevationSources.tileUrl(t, "N32E035"))
        // Not gzipped: the ending before the query counts.
        assertFalse(ElevationSources.isGzip(ElevationSources.tileUrl(t, "N32E035")))
        assertTrue(ElevationSources.isGzip("https://x.example/{name}.hgt.GZ?x=1"))
        assertEquals("dem.example.org", ElevationSources.customHost(t))
        assertEquals("https://dem.example.org/", ElevationSources.site(t))
        assertEquals("tiles.example", ElevationSources.customHost("https://www.tiles.example:8443/{folder}/{name}.hgt.gz"))
    }

    @Test
    fun whatIsntATemplate() {
        assertNull(ElevationSources.tilesTemplate("https://dem.example.org/N47E009.hgt")) // no {name}
        assertNull(ElevationSources.tilesTemplate("ftp://dem.example.org/{name}.hgt"))
        assertNull(ElevationSources.tilesTemplate("https:///{name}.hgt"))
        assertNull(ElevationSources.tilesTemplate("https://dem.example.org/my tiles/{name}.hgt"))
        assertNull(ElevationSources.tiles3dTemplate("https://t.example/{z}/{x}.png"))
        assertEquals("https://t.example/{z}/{x}/{y}.webp", ElevationSources.tiles3dTemplate("https://t.example/{z}/{x}/{y}.webp"))
    }

    @Test
    fun the3dTilesAsKept() {
        val s = Terrain3dSource("https://api.example.com/terrain-rgb/{z}/{x}/{y}.png?key=abc", mapbox = true)
        assertEquals(s, Terrain3dSource.parse(s.encode()))
        val t = Terrain3dSource(ElevationSources.DEFAULT_3D, mapbox = false)
        assertEquals(t, Terrain3dSource.parse(t.encode()))
        assertNull(Terrain3dSource.parse(null))
        assertNull(Terrain3dSource.parse("mapbox https://broken.example/{z}.png"))
    }
}
