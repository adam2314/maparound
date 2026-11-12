package app.maparound.trails

import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import app.maparound.testing.assertEquals
import okio.Buffer
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test

class TrailCellTest {
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
    fun coloursFromOsmcSymbolOrColourTag() {
        assertEquals(TrailColour.RED, TrailColour.of("red:white:red_bar", null))
        assertEquals(TrailColour.BLUE, TrailColour.of(null, "#0011dd"))
        assertEquals(TrailColour.YELLOW, TrailColour.of("yellow:white:yellow_bar", "red")) // osmc wins
        assertEquals(TrailColour.OTHER, TrailColour.of(null, null))
    }

    @Test
    fun parseAndStoreCells() {
        val cell = TrailCellIO.parseOverpass(overpass)
        assertEquals(3, cell.routes.size)
        assertEquals(2, cell.ways.size)
        assertEquals(listOf(47_000_000, 9_000_000, 47_005_000, 9_005_000, 47_010_000, 9_010_000), cell.ways[0].coords.toList())
        assertEquals(listOf(0, 1), cell.ways[0].routes.toList())
        val file = (tempDir.toPath() / "N47E009-${Random.nextLong().toULong()}.trails").toString()
        try {
            TrailCellIO.write(cell, file)
            val back = TrailCellIO.read(file)
            assertEquals(cell.routes, back.routes)
            assertEquals(cell.ways[0].coords.toList(), back.ways[0].coords.toList())
            assertEquals(cell.ways[1].routes.toList(), back.ways[1].routes.toList())
        } finally {
            fileSystem.delete(file.toPath())
        }
    }

    @Test
    fun cliffsComeWithTheTrails() {
        // The extra Overpass statement's output: tagged ways, after the routes. Way 1 is a path
        // on a route; a cliff on a route would be both.
        val withCliffs = overpass.replace(
            "</osm>",
            """<way id="5"><nd ref="51" lat="47.100" lon="9.100"/><nd ref="52" lat="47.101" lon="9.102"/>
                 <tag k="natural" v="cliff"/></way>
               <way id="6"><nd ref="61" lat="47.200" lon="9.200"/><nd ref="62" lat="47.210" lon="9.210"/>
                 <tag k="natural" v="arete"/><tag k="name" v="Grat"/></way>
               <way id="7"><nd ref="71" lat="47.300" lon="9.300"/><nd ref="72" lat="47.301" lon="9.301"/>
                 <tag k="natural" v="tree_row"/></way>
               </osm>""",
        )
        val cell = TrailCellIO.parseOverpass(withCliffs)
        assertEquals(2, cell.ways.size) // the routes' ways only
        assertEquals(listOf(5L to TrailFeature.Kind.CLIFF, 6L to TrailFeature.Kind.ARETE), cell.features.map { it.id to it.kind })
        assertEquals(listOf(47_100_000, 9_100_000, 47_101_000, 9_102_000), cell.features[0].coords.toList())

        val file = (tempDir.toPath() / "N47E009-${Random.nextLong().toULong()}.trails").toString()
        try {
            TrailCellIO.write(cell, file)
            assertEquals(true, TrailCellIO.hasFeatures(file))
            val back = TrailCellIO.read(file)
            assertEquals(true, back.withFeatures)
            assertEquals(cell.features.map { it.kind }, back.features.map { it.kind })
            assertEquals(cell.features[1].coords.toList(), back.features[1].coords.toList())
            assertEquals(2, back.ways.size)
        } finally {
            fileSystem.delete(file.toPath())
        }
    }

    @Test
    fun cellsFromBeforeCliffsStillRead() {
        // Format 1, as written before: one route, one way, nothing after.
        val v1 = Buffer().writeInt(0x4D415452).writeInt(1).writeInt(1)
        TrailCellIO.writeUtf(v1, "Valley trail")
        TrailCellIO.writeUtf(v1, "")
        v1.writeByte(3).writeByte(TrailColour.YELLOW.ordinal)
        v1.writeInt(1).writeLong(1).writeShort(1).writeShort(0).writeInt(4)
        listOf(47_000_000, 9_000_000, 47_010_000, 9_010_000).forEach { v1.writeInt(it) }
        val file = (tempDir.toPath() / "N47E009-${Random.nextLong().toULong()}.trails").toString()
        try {
            fileSystem.write(file.toPath()) { writeAll(v1) }
            val cell = TrailCellIO.read(file)
            assertEquals("Valley trail", cell.routes.single().name)
            assertEquals(1, cell.ways.size)
            assertEquals(false, cell.withFeatures)
            // So the download fetches it again, cliffs and all.
            assertEquals(false, TrailCellIO.hasFeatures(file))
        } finally {
            fileSystem.delete(file.toPath())
        }
    }

    @Test
    fun stringsInJavasModifiedUtf8() {
        // DataOutputStream.writeUTF("Ein\u0000 Gedi ✓ 😀"): U+0000 as C0 80, the emoji as two 3-byte surrogates.
        val s = "Ein\u0000 Gedi ✓ 😀"
        val b = Buffer()
        TrailCellIO.writeUtf(b, s)
        assertEquals("001545696ec080204765646920e29c9320eda0bdedb880", b.copy().readByteString().hex())
        assertEquals(s, TrailCellIO.readUtf(b))
    }
}
