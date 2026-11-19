package app.maparound.stats

import app.maparound.data.CatalogEntry
import app.maparound.data.MapAroundCatalog
import app.maparound.map.MapRegions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapBuildsTest {
    /** regions.bin as tools/regions/make_regions.py writes it: (path, rings of (lat, lon) in degrees). */
    private fun pack(vararg regions: Pair<String, List<List<Pair<Double, Double>>>>): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v: Long) {
            var n = v
            while (true) {
                val b = (n and 0x7F).toInt()
                n = n ushr 7
                if (n != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); return }
            }
        }
        fun zigzag(n: Int) = varint(((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL)
        "MARG".encodeToByteArray().forEach { out += it }
        out += 1
        varint(regions.size.toLong())
        for ((path, rings) in regions) {
            val name = path.encodeToByteArray()
            varint(name.size.toLong())
            name.forEach { out += it }
            varint(rings.size.toLong())
            for (ring in rings) {
                varint(ring.size.toLong())
                var pLat = 0
                var pLon = 0
                for ((la, lo) in ring) {
                    val lat = kotlin.math.round(la * 1000).toInt()
                    val lon = kotlin.math.round(lo * 1000).toInt()
                    zigzag(lat - pLat)
                    zigzag(lon - pLon)
                    pLat = lat
                    pLon = lon
                }
            }
        }
        return out.toByteArray()
    }

    private fun box(s: Double, w: Double, n: Double, e: Double) = listOf(s to w, s to e, n to e, n to w)

    // A country split in two states; Switzerland with Liechtenstein in it; New Zealand in two parts
    // in the list; a group of countries (the Alps) over Switzerland and Austria.
    private val regions = MapRegions.decode(
        pack(
            "europe/germany" to listOf(box(47.0, 6.0, 55.0, 15.0)),
            "europe/germany/north" to listOf(box(51.0, 6.0, 55.0, 15.0)),
            "europe/germany/south" to listOf(box(47.0, 6.0, 51.0, 15.0)),
            "europe/switzerland" to listOf(box(45.8, 5.9, 47.8, 10.5)),
            "europe/liechtenstein" to listOf(box(47.0, 9.4, 47.3, 9.7)),
            "europe/austria" to listOf(box(45.9, 10.4, 47.8, 17.0)),
            "europe/alps" to listOf(box(46.0, 6.0, 47.7, 16.9)),
            "australia-oceania/new-zealand" to listOf(box(-47.0, 166.0, -34.0, 179.9), box(-44.5, -177.0, -43.5, -176.0)),
        ),
    )!!

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val latest = now - 3 * day

    private fun map(path: String, at: Long?, built: Boolean = true, why: CatalogEntry.Unavailable? = null) = CatalogEntry(
        path = "$path.map", isDirectory = false, sizeLabel = null, sizeBytes = if (built) 100L else null, modified = at,
        url = if (built) "https://example.org/$path.map" else null, unavailable = if (built) null else why,
    )

    private val catalog = listOf(
        map("europe/germany", null, built = false, why = CatalogEntry.Unavailable.NEEDS_FUNDING),
        map("europe/germany/north", latest),
        // From the run before.
        map("europe/germany/south", latest - 30 * day),
        map("europe/switzerland", latest - day),
        map("europe/liechtenstein", null, built = false, why = CatalogEntry.Unavailable.NOT_BUILT),
        map("europe/alps", null, built = false, why = CatalogEntry.Unavailable.NEEDS_FUNDING),
        map("australia-oceania/new-zealand-1", latest),
        map("australia-oceania/new-zealand-2", null, built = false, why = CatalogEntry.Unavailable.NOT_BUILT),
    )

    @Test
    fun eachRegionsMapStands() {
        val b = MapBuilds.builds(regions.all.map { it.path }, catalog, now)
        assertEquals(BuildState.CURRENT, b.getValue("europe/germany/north").state)
        assertEquals(latest, b.getValue("europe/germany/north").builtAt)
        assertEquals(100L, b.getValue("europe/germany/north").sizeBytes)
        assertEquals(BuildState.OLD, b.getValue("europe/germany/south").state)
        assertEquals(BuildState.CURRENT, b.getValue("europe/switzerland").state)
        assertEquals(BuildState.MISSING, b.getValue("europe/germany").state)
        assertEquals(CatalogEntry.Unavailable.NEEDS_FUNDING, b.getValue("europe/germany").why)
        assertEquals(CatalogEntry.Unavailable.NOT_BUILT, b.getValue("europe/liechtenstein").why)
        // Not in the list at all.
        assertEquals(BuildState.MISSING, b.getValue("europe/austria").state)
        assertNull(b.getValue("europe/austria").why)
        // In parts: the worst of them, the built part's day and size.
        val nz = b.getValue("australia-oceania/new-zealand")
        assertEquals(BuildState.MISSING, nz.state)
        assertEquals(latest, nz.builtAt)
        assertEquals(CatalogEntry.Unavailable.NOT_BUILT, nz.why)
    }

    @Test
    fun oldWhenTheBuildsStopped() {
        val m = map("europe/switzerland", latest)
        assertEquals(BuildState.CURRENT, MapBuilds.stateOf(m, latest, now))
        assertEquals(BuildState.OLD, MapBuilds.stateOf(m, latest, latest + 46 * day))
        assertEquals(BuildState.OLD, MapBuilds.stateOf(map("europe/switzerland", latest - 21 * day), latest, now))
        assertEquals(BuildState.CURRENT, MapBuilds.stateOf(map("europe/switzerland", latest - 5 * day), latest, now))
        assertEquals(BuildState.MISSING, MapBuilds.stateOf(map("x", null, built = false), latest, now))
    }

    @Test
    fun aCountryIsShownThroughItsStates() {
        val shown = MapBuilds.shown(regions.all).map { it.path }.toSet()
        assertTrue("europe/germany/north" in shown)
        assertTrue("europe/germany/south" in shown)
        assertFalse("europe/germany" in shown)
        // Covered by Switzerland and Austria.
        assertFalse("europe/alps" in shown)
        // Liechtenstein covers little of it.
        assertTrue("europe/switzerland" in shown)
        assertTrue("europe/liechtenstein" in shown)
        assertTrue("australia-oceania/new-zealand" in shown)
    }

    @Test
    fun theWholesARegionIsPartOf() {
        val data = MapBuilds.of(regions.all, catalog, now)
        val south = data.region("europe/germany/south")!!
        assertEquals(listOf("europe/germany"), data.wholesAround(south).map { it.path })
        val swiss = data.region("europe/switzerland")!!
        assertEquals(listOf("europe/alps"), data.wholesAround(swiss).map { it.path })
        assertEquals(4, data.built)
        assertEquals(catalog.size, data.total)
        assertEquals(latest, data.latest)
    }

    @Test
    fun ringsAsPolygons() {
        fun ring(vararg p: Int) = p
        val outer = ring(0, 0, 0, 10, 10, 10, 10, 0)
        val hole = ring(2, 2, 2, 4, 4, 4, 4, 2)
        val island = ring(20, 20, 20, 22, 22, 22, 22, 20)
        val polygons = MapBuilds.polygons(listOf(hole, outer, island))
        assertEquals(2, polygons.size)
        assertEquals(listOf(outer, hole), polygons.first { it.size == 2 })
        assertEquals(listOf(island), polygons.first { it.size == 1 })
    }

    @Test
    fun geoJsonOfTheShownRegions() {
        val data = MapBuilds.of(regions.all, catalog, now)
        val json = MapBuilds.geoJson(data)
        assertTrue("\"tap\":\"data:m:europe/germany/north\"" in json)
        assertFalse("\"tap\":\"data:m:europe/germany\"" in json)
        assertTrue("\"color\":\"${MapBuilds.colors[BuildState.OLD.ordinal]}\"" in json)
        // The biggest first: smaller ones are drawn over it where they meet.
        assertTrue(json.indexOf("germany/north") < json.indexOf("liechtenstein"))
        val withoutMissing = MapBuilds.geoJson(data, hidden = setOf(BuildState.MISSING.ordinal))
        assertFalse("liechtenstein" in withoutMissing)
        assertTrue("switzerland" in withoutMissing)
    }

    @Test
    fun fromTheListAsPublished() {
        val list = """
            {"version":1,"generated":"2026-11-17T01:38:20Z","maps":[
              {"region":"europe/switzerland","path":"europe/switzerland","url":"https://example.org/ch.map","size":5,"date":"2026-11-15T21:22:28Z"},
              {"region":"europe/germany","path":"europe/germany","status":"needs-funding"},
              {"region":"europe/liechtenstein","path":"europe/liechtenstein","status":"unavailable"}
            ]}
        """.trimIndent()
        val data = MapBuilds.of(regions.all, MapAroundCatalog.parse(list), now)
        assertEquals(1, data.built)
        assertEquals(3, data.total)
        assertEquals(CatalogEntry.Unavailable.NEEDS_FUNDING, data.builds.getValue("europe/germany").why)
        assertEquals(CatalogEntry.Unavailable.NOT_BUILT, data.builds.getValue("europe/liechtenstein").why)
    }
}
