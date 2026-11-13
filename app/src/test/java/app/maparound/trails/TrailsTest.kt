package app.maparound.trails

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.BoundingBox

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
        val found = ArrayList<Pair<TrailWay, List<TrailRoute>>>()
        index.query(BoundingBox(47.004, 9.004, 47.006, 9.006)) { w, r -> found += w to r }
        // Way 1 is on two routes with different colours: drawn twice, side by side.
        val way1 = found.first { it.first.coords.size == 6 }
        val copies = TrailTags.lines(way1.second)
        assertEquals(2, copies.size)
        val first = copies.first { "trail_n" in it }
        // International route (Via Alpina, red) comes first and names the line.
        assertEquals("red", first["trail_colour"])
        assertEquals("Via Alpina", first["name"])
        assertEquals(setOf("l05", "r05"), copies.map { it["trail_offset"] }.toSet())
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

        val features = ArrayList<TrailFeature>()
        index.queryFeatures(BoundingBox(47.0, 9.0, 47.01, 9.01)) { features += it }
        assertEquals(listOf("cliff"), features.map { it.kind.tag })
        assertEquals(true, index.hasDataIn(BoundingBox(47.0, 9.0, 47.01, 9.01), routes = false, features = true))
    }
}
