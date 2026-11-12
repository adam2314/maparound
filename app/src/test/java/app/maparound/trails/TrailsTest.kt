package app.maparound.trails

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection

class TrailsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Overpass output shape: ways with geometry first, then relations with member refs.
    private val overpass = """<?xml version="1.0" encoding="UTF-8"?>
        <osm version="0.6">
          <way id="1"><bounds minlat="47" minlon="9" maxlat="47.01" maxlon="9.01"/>
            <nd ref="11" lat="47.000" lon="9.000"/><nd ref="12" lat="47.005" lon="9.005"/><nd ref="13" lat="47.010" lon="9.010"/>
            <tag k="highway" v="path"/></way>
          <way id="2"><nd ref="13" lat="47.010" lon="9.010"/><nd ref="14" lat="47.020" lon="9.010"/></way>
          <relation id="100"><member type="way" ref="1" role=""/><member type="way" ref="2" role=""/>
            <member type="node" ref="11" role="guidepost"/>
            <tag k="route" v="hiking"/><tag k="network" v="lwn"/><tag k="name" v="Valley trail"/>
            <tag k="osmc:symbol" v="yellow:white:yellow_bar"/></relation>
          <relation id="200"><member type="way" ref="1" role=""/>
            <tag k="route" v="hiking"/><tag k="network" v="iwn"/><tag k="name" v="Via Alpina"/><tag k="ref" v="R1"/>
            <tag k="osmc:symbol" v="red:white:red_bar"/></relation>
          <relation id="300"><member type="way" ref="2" role=""/>
            <tag k="route" v="hiking"/><tag k="colour" v="#0000ee"/></relation>
        </osm>"""

    @Test
    fun parseStoreAndRenderSharedWays() {
        val cell = TrailCellIO.parseOverpass(overpass)
        assertEquals(3, cell.routes.size)
        assertEquals(2, cell.ways.size)
        val file = tmp.newFile("N47E009.trails")
        TrailCellIO.write(cell, file.path)
        val back = TrailCellIO.read(file.path)
        assertEquals(cell.routes, back.routes)
        assertEquals(cell.ways[0].coords.toList(), back.ways[0].coords.toList())

        val index = TrailIndex(tmp.root)
        assertEquals(2, index.wayCount)
        val zoom: Byte = 15
        val tile = Tile(
            MercatorProjection.longitudeToTileX(9.005, zoom), MercatorProjection.latitudeToTileY(47.005, zoom), zoom, 256,
        )
        val ways = TrailDataStore(index).readMapData(tile).ways
        // Way 1 is on two routes with different colours: drawn twice, side by side.
        val way1 = ways.filter { w -> w.latLongs[0].size == 3 }
        assertEquals(2, way1.size)
        val first = way1.first { w -> w.tags.any { it.key == "trail_n" } }
        // International route (Via Alpina, red) comes first and names the line.
        assertEquals("red", first.tags.first { it.key == "trail_colour" }.value)
        assertEquals("Via Alpina", first.tags.first { it.key == "name" }.value)
        assertEquals(setOf("l05", "r05"), way1.map { w -> w.tags.first { it.key == "trail_offset" }.value }.toSet())
        assertEquals(true, index.hasDataIn(BoundingBox(47.0, 9.0, 47.02, 9.02)))
        assertEquals(false, index.hasDataIn(BoundingBox(46.0, 8.0, 46.1, 8.1)))
    }

    @Test
    fun cliffsAreDrawnLikeOsmEvenWithTheTrailsOff() {
        val cliff = """<way id="5"><nd ref="51" lat="47.004" lon="9.004"/><nd ref="52" lat="47.006" lon="9.007"/>
            <tag k="natural" v="cliff"/></way></osm>"""
        // The same cliff in two neighbouring cells' files: drawn once.
        val cell = TrailCellIO.parseOverpass(overpass.replace("</osm>", cliff))
        TrailCellIO.write(cell, tmp.newFile("N47E009.trails").path)
        TrailCellIO.write(cell, tmp.newFile("N47E008.trails").path)
        val index = TrailIndex(tmp.root)
        assertEquals(1, index.featureCount)
        assertEquals(2, index.wayCount)

        fun tileAt(zoom: Byte) = Tile(MercatorProjection.longitudeToTileX(9.005, zoom), MercatorProjection.latitudeToTileY(47.005, zoom), zoom, 256)
        val trailsOff = TrailDataStore(index, routes = false)
        val ways = trailsOff.readMapData(tileAt(15)).ways
        assertEquals(listOf("natural=cliff"), ways.map { w -> w.tags.joinToString { "${it.key}=${it.value}" } })
        assertEquals(true, trailsOff.supportsTile(tileAt(15)))
        // Below zoom 13 there's nothing to draw then; with the trails on, the lines come too.
        assertEquals(false, trailsOff.supportsTile(tileAt(12)))
        val trailsOn = TrailDataStore(index).readMapData(tileAt(15)).ways
        assertEquals(1, trailsOn.count { w -> w.tags.any { it.key == "natural" } })
        assertEquals(true, trailsOn.any { w -> w.tags.any { it.key == "trail" } })
    }
}
