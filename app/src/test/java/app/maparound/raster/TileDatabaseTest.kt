package app.maparound.raster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * MBTiles and SQLiteDB files written by real SQLite (JDBC), the way the tools that make them do,
 * then read back through [SqliteFile] and [TileDatabase].
 */
class TileDatabaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun connect(f: File): Connection = DriverManager.getConnection("jdbc:sqlite:${f.path}")

    private fun sqlite(f: File): Pair<SqliteFile, AutoCloseable> {
        val raf = RandomAccessFile(f, "r")
        val read = RandomRead { pos, len ->
            synchronized(raf) {
                val b = ByteArray(len)
                raf.seek(pos)
                var n = 0
                while (n < len) { val r = raf.read(b, n, len - n); if (r < 0) break; n += r }
                if (n == len) b else b.copyOf(n)
            }
        }
        return SqliteFile(read, f.length()) to raf
    }

    private fun open(f: File): TileDatabase = sqlite(f).let { (s, c) -> TileDatabase(s, c) }

    /** A tile's bytes that say which tile it is. */
    private fun marker(z: Int, x: Int, y: Int) = "tile $z/$x/$y".encodeToByteArray()

    // Vaduz at zoom 12: XYZ x 2155, y 1435.
    private val x12 = 2155
    private val y12 = 1435

    @Test
    fun mbtilesWithMetadata() {
        val f = tmp.newFile("a.mbtiles")
        connect(f).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE metadata (name text, value text)")
                s.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
                s.execute("INSERT INTO metadata VALUES ('name', 'Vaduz test'), ('format', 'png'), ('minzoom', '11'), ('maxzoom', '13'), ('bounds', '9.4,47.0,9.7,47.3')")
            }
            c.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { p ->
                for (z in 11..13) {
                    val x = x12 shr (12 - minOf(z, 12)) shl (maxOf(z, 12) - 12)
                    val y = y12 shr (12 - minOf(z, 12)) shl (maxOf(z, 12) - 12)
                    p.setInt(1, z); p.setInt(2, x); p.setInt(3, (1 shl z) - 1 - y); p.setBytes(4, marker(z, x, y)); p.executeUpdate()
                }
            }
        }
        open(f).use { db ->
            assertEquals(TileDatabase.Kind.MBTILES, db.kind)
            assertEquals("Vaduz test", db.name)
            assertEquals(11, db.minZoom)
            assertEquals(13, db.maxZoom)
            assertEquals(9.4, db.west, 1e-9)
            assertEquals(47.3, db.north, 1e-9)
            // XYZ in, TMS rows in the file.
            assertArrayEquals(marker(12, x12, y12), db.tile(12, x12, y12))
            assertArrayEquals(marker(13, 2 * x12, 2 * y12), db.tile(13, 2 * x12, 2 * y12))
            assertNull(db.tile(12, x12 + 1, y12))
            assertNull("outside its zooms", db.tile(14, 4 * x12, 4 * y12))
        }
    }

    @Test
    fun mbtilesAsAViewWithoutMetadataZooms() {
        // mbutil / TileMill: deduplicated images behind a "tiles" view, and no zooms or bounds given.
        val f = tmp.newFile("b.mbtiles")
        connect(f).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE metadata (name text, value text)")
                s.execute("INSERT INTO metadata VALUES ('format', 'jpg')")
                s.execute("CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)")
                s.execute("CREATE TABLE images (tile_data blob, tile_id text)")
                s.execute(
                    "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, " +
                        "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = map.tile_id",
                )
            }
            val n = 1 shl 12
            c.prepareStatement("INSERT INTO map VALUES (12, ?, ?, ?)").use { p ->
                for ((dx, dy) in listOf(0 to 0, 1 to 0, 0 to 1)) {
                    p.setInt(1, x12 + dx); p.setInt(2, n - 1 - (y12 + dy)); p.setString(3, "t${dx}_$dy"); p.executeUpdate()
                }
            }
            c.prepareStatement("INSERT INTO images VALUES (?, ?)").use { p ->
                for ((dx, dy) in listOf(0 to 0, 1 to 0, 0 to 1)) {
                    p.setBytes(1, marker(12, x12 + dx, y12 + dy)); p.setString(2, "t${dx}_$dy"); p.executeUpdate()
                }
            }
        }
        open(f).use { db ->
            assertEquals(12, db.minZoom)
            assertEquals(12, db.maxZoom)
            assertNull(db.name)
            // Bounds are the stored tiles' edges: two tiles wide, two high.
            assertEquals(TileDatabase.lonOf(x12, 12), db.west, 1e-9)
            assertEquals(TileDatabase.lonOf(x12 + 2, 12), db.east, 1e-9)
            assertEquals(TileDatabase.latOf(y12, 12), db.north, 1e-9)
            assertEquals(TileDatabase.latOf(y12 + 2, 12), db.south, 1e-9)
            assertArrayEquals(marker(12, x12 + 1, y12), db.tile(12, x12 + 1, y12))
        }
    }

    @Test
    fun sqlitedbNumberings() {
        // RMaps / Locus: z = 17 − zoom, no info about it ("BigPlanet").
        val bigPlanet = sqlitedb("c.sqlitedb", info = null) { z -> 17 - z }
        open(bigPlanet).use { db ->
            assertEquals(TileDatabase.Kind.SQLITEDB, db.kind)
            assertEquals(11, db.minZoom)
            assertEquals(13, db.maxZoom)
            assertArrayEquals(marker(12, x12, y12), db.tile(12, x12, y12))
        }
        // OsmAnd with plain numbering, and rows counted from the south.
        val simple = sqlitedb("d.sqlitedb", info = "'simple', 1") { z -> z }
        open(simple).use { db ->
            assertEquals(11, db.minZoom)
            assertEquals(13, db.maxZoom)
            assertArrayEquals(marker(13, 2 * x12, 2 * y12), db.tile(13, 2 * x12, 2 * y12))
            assertTrue(db.north > db.south)
            assertEquals(TileDatabase.latOf(y12, 12), db.north, 1e-6)
        }
    }

    /** A SQLiteDB with Vaduz at zooms 11–13; [info] = "tilenumbering, inverted_y" values. */
    private fun sqlitedb(name: String, info: String?, fileZ: (Int) -> Int): File {
        val f = tmp.newFile(name)
        val invertedY = info?.endsWith("1") == true
        connect(f).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x, y, z, s))")
                if (info != null) {
                    s.execute("CREATE TABLE info (maxzoom int, minzoom int, tilenumbering text, inverted_y int)")
                    s.execute("INSERT INTO info VALUES (13, 11, $info)")
                }
            }
            c.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, 0, ?)").use { p ->
                for (z in 11..13) {
                    val x = x12 shr (12 - minOf(z, 12)) shl (maxOf(z, 12) - 12)
                    val y = y12 shr (12 - minOf(z, 12)) shl (maxOf(z, 12) - 12)
                    p.setInt(1, x); p.setInt(2, if (invertedY) (1 shl z) - 1 - y else y); p.setInt(3, fileZ(z)); p.setBytes(4, marker(z, x, y)); p.executeUpdate()
                }
            }
        }
        return f
    }

    @Test
    fun bigTablesOverflowPagesAndNoIndex() {
        // 3000 tiles of 6 KB (more than a 4 KB page: overflow chains), several b-tree levels; once
        // with the usual index and once without (then a key map is built on first use).
        for (indexed in listOf(true, false)) {
            val f = File(tmp.root, "big-$indexed.mbtiles")
            val rnd = java.util.Random(7)
            val images = HashMap<Triple<Int, Int, Int>, ByteArray>()
            connect(f).use { c ->
                c.autoCommit = false
                c.createStatement().use { s ->
                    s.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
                    if (indexed) s.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
                }
                c.prepareStatement("INSERT INTO tiles VALUES (14, ?, ?, ?)").use { p ->
                    for (i in 0 until 3000) {
                        val x = 8600 + i % 60
                        val row = 10600 + i / 60
                        val bytes = ByteArray(6000 + rnd.nextInt(200)).also(rnd::nextBytes)
                        images[Triple(14, x, row)] = bytes
                        p.setInt(1, x); p.setInt(2, row); p.setBytes(3, bytes); p.executeUpdate()
                    }
                }
                c.commit()
            }
            open(f).use { db ->
                assertEquals(14, db.minZoom)
                assertEquals(14, db.maxZoom)
                assertEquals(TileDatabase.lonOf(8600, 14), db.west, 1e-9)
                assertEquals(TileDatabase.lonOf(8660, 14), db.east, 1e-9)
                val n = 1 shl 14
                for ((k, bytes) in images.entries.shuffled(java.util.Random(1)).take(200)) {
                    assertArrayEquals("$indexed $k", bytes, db.tile(14, k.second, n - 1 - k.third))
                }
                assertNull(db.tile(14, 8599, n - 1 - 10600))
            }
        }
    }

    @Test
    fun readsTheSchemaAndRowsLikeSqlite() {
        val f = tmp.newFile("s.db")
        connect(f).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE \"t\" (id INTEGER PRIMARY KEY, name TEXT, n REAL, b BLOB)")
                s.execute("CREATE INDEX t_name ON t (name)")
                s.execute("INSERT INTO t VALUES (5, 'Ein Gedi ✓', -2.5, x'00ff'), (9, NULL, 1e10, NULL), (12, 'Sataf', 0, x'')")
            }
        }
        val (db, close) = sqlite(f)
        close.use {
            assertEquals(listOf("id", "name", "n", "b") to 0, db.columns("t"))
            val rows = ArrayList<SqliteFile.Row>()
            db.scan(db.entry("t")!!.rootPage) { rows += it; true }
            assertEquals(listOf(5L, 9L, 12L), rows.map { it.rowid })
            assertEquals("Ein Gedi ✓", rows[0].values[1])
            assertEquals(-2.5, rows[0].values[2] as Double, 0.0)
            assertArrayEquals(byteArrayOf(0, -1), rows[0].values[3] as ByteArray)
            assertNull(rows[1].values[1])
            assertEquals("Sataf", db.row(db.entry("t")!!.rootPage, 12)!!.values[1])
            assertNull(db.row(db.entry("t")!!.rootPage, 6))
            // The index finds the row by name.
            var found: Long? = null
            db.seek(db.indexOn("t", listOf("name"))!!.rootPage, listOf("Sataf")) { e -> found = e.last() as Long; false }
            assertEquals(12L, found)
        }
    }

    @Test
    fun refusesWhatIsNotARasterTileDatabase() {
        val vector = tmp.newFile("v.mbtiles")
        connect(vector).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE metadata (name text, value text)")
                s.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
                s.execute("INSERT INTO metadata VALUES ('format', 'pbf')")
                s.execute("INSERT INTO tiles VALUES (0, 0, 0, x'00')")
            }
        }
        expectFailure("vector") { open(vector) }
        val other = tmp.newFile("o.db")
        connect(other).use { c -> c.createStatement().use { it.execute("CREATE TABLE places (name text)") } }
        expectFailure("No tiles") { open(other) }
        assertTrue(TileDatabase.isSqlite(vector.readBytes()))
        assertTrue(!TileDatabase.isSqlite("MapAround".encodeToByteArray() + ByteArray(16)))
    }

    private fun expectFailure(text: String, block: () -> Unit) {
        try {
            block()
            fail("expected a failure mentioning \"$text\"")
        } catch (e: Exception) {
            if (e !is TileDatabaseException && e !is SqliteException) throw e
            assertTrue(e.message, e.message!!.contains(text))
        }
    }

    /**
     * Writes sample databases over Liechtenstein for trying the app (FIXTURES_OUT=dir): tiles
     * with their numbers and a grid, half transparent, so they line up with the map below.
     */
    @Test
    fun writeSampleFiles() {
        val out = System.getenv("FIXTURES_OUT")?.let(::File) ?: return
        out.mkdirs()
        fun tileRange(z: Int): Pair<IntRange, IntRange> {
            fun tx(lon: Double) = floor((lon + 180) / 360 * (1 shl z)).toInt()
            fun ty(lat: Double) = floor((1 - ln(tan(Math.toRadians(lat)) + 1 / kotlin.math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 shl z)).toInt()
            return tx(9.47)..tx(9.64) to ty(47.27)..ty(47.04)
        }
        /** A 256² RGBA PNG: [rgb] at low opacity with a solid 4-pixel border and a cross in the middle. */
        fun png(rgb: Int): ByteArray {
            val raw = java.io.ByteArrayOutputStream()
            for (py in 0 until 256) {
                raw.write(0) // filter: none
                for (px in 0 until 256) {
                    val edge = px < 4 || py < 4 || px >= 252 || py >= 252 || (px in 126..129 && py in 96..159) || (py in 126..129 && px in 96..159)
                    raw.write(rgb shr 16 and 0xFF); raw.write(rgb shr 8 and 0xFF); raw.write(rgb and 0xFF); raw.write(if (edge) 255 else 70)
                }
            }
            val out = java.io.ByteArrayOutputStream()
            fun chunk(type: String, data: ByteArray) {
                val crc = java.util.zip.CRC32().apply { update(type.encodeToByteArray()); update(data) }
                out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
                out.write(type.encodeToByteArray()); out.write(data)
                out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
            }
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(256).putInt(256).put(8).put(6).put(0).put(0).put(0).array())
            chunk("IDAT", java.io.ByteArrayOutputStream().also { b -> java.util.zip.DeflaterOutputStream(b).use { it.write(raw.toByteArray()) } }.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
        val blue = png(0x005AC8)
        val red = png(0xC82828)
        File(out, "sample.mbtiles").also { it.delete() }.let { f ->
            connect(f).use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE TABLE metadata (name text, value text)")
                    s.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
                    s.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
                    s.execute("INSERT INTO metadata VALUES ('name', 'Sample MBTiles'), ('format', 'png'), ('minzoom', '10'), ('maxzoom', '14')")
                }
                c.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { p ->
                    for (z in 10..14) {
                        val (xs, ys) = tileRange(z)
                        for (x in xs) for (y in ys) {
                            p.setInt(1, z); p.setInt(2, x); p.setInt(3, (1 shl z) - 1 - y); p.setBytes(4, blue); p.executeUpdate()
                        }
                    }
                }
            }
        }
        File(out, "sample.sqlitedb").also { it.delete() }.let { f ->
            connect(f).use { c ->
                c.createStatement().use { it.execute("CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x, y, z, s))") }
                c.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, 0, ?)").use { p ->
                    for (z in 12..13) {
                        val (xs, ys) = tileRange(z)
                        for (x in xs) for (y in ys) {
                            p.setInt(1, x); p.setInt(2, y); p.setInt(3, 17 - z); p.setBytes(4, red); p.executeUpdate()
                        }
                    }
                }
            }
        }
    }
}
