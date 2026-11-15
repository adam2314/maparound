package app.maparound.map

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamPlanTest {
    private fun box(lat: Int, lon: Int, h: Int = 1, w: Int = 1) =
        GeoBox(lat * 1_000_000, lon * 1_000_000, (lat + h) * 1_000_000, (lon + w) * 1_000_000)

    private val regions = mapOf(
        "asia/israel-and-palestine" to box(31, 34),
        "asia/jordan" to box(31, 36),
        "europe/germany" to box(50, 10),
        "europe/germany/bayern" to box(48, 11),
        "europe/germany/berlin" to box(52, 13),
        "europe/netherlands" to box(52, 5),
        "europe/netherlands/drenthe" to box(52, 6),
        "europe/netherlands/utrecht" to box(52, 5),
        "europe/poland" to box(50, 18),
        "europe/poland/slaskie" to box(50, 18),
        "australia-oceania/new-zealand" to box(-45, 170),
        "europe/alps" to box(44, 5, h = 5, w = 12),
    )

    /** What mapsforge.org has: not Poland's provinces, New Zealand in two parts, not the Alps. */
    private val mapsforge = regions.keys - setOf("europe/poland/slaskie", "australia-oceania/new-zealand", "europe/alps") +
        setOf("australia-oceania/new-zealand-1", "australia-oceania/new-zealand-2")

    private fun url(path: String) = "https://download.mapsforge.org/maps/v5/$path.map"

    private val basic = regions.keys.associateWith { p ->
        when {
            p in mapsforge -> listOf(url(p))
            else -> (1..3).map { "$p-$it" }.filter { it in mapsforge }.map(::url)
        }
    }

    private fun maps(built: Map<String, String> = emptyMap(), installed: Set<String> = emptySet(), others: List<GeoBox> = emptyList()) =
        StreamPlan.choose(regions, built, basic, installed, others)

    private fun plan(built: Map<String, String> = emptyMap(), installed: Set<String> = emptySet(), others: List<GeoBox> = emptyList()) =
        maps(built, installed, others).associate { it.path to it.url }

    @Test
    fun theMostDetailedMapsFromMapsforgeOrg() {
        assertEquals(
            listOf(
                "asia/israel-and-palestine", "asia/jordan", "australia-oceania/new-zealand",
                "europe/germany/bayern", "europe/germany/berlin", "europe/netherlands/drenthe", "europe/netherlands/utrecht",
                // Its province isn't there: the whole country.
                "europe/poland",
            ),
            maps().map { it.path }.distinct(),
        )
        assertEquals(url("asia/jordan"), plan()["asia/jordan"])
        // Split in two there: both parts.
        assertEquals(
            listOf(url("australia-oceania/new-zealand-1"), url("australia-oceania/new-zealand-2")),
            maps().filter { it.path == "australia-oceania/new-zealand" }.map { it.url },
        )
    }

    @Test
    fun notTheAreaOfSeveralCountriesOverTheirOwnMaps() {
        // The Alps built by MapAround: still not over Bavaria etc.… unless little else is streamed there.
        val withAlps = regions + mapOf("europe/austria" to box(46, 9, h = 3, w = 8), "europe/switzerland" to box(45, 5, h = 3, w = 6))
        val all = basic + mapOf("europe/austria" to listOf(url("europe/austria")), "europe/switzerland" to listOf(url("europe/switzerland")))
        val p = StreamPlan.choose(withAlps, mapOf("europe/alps" to "https://github.com/x/alps.map"), all, emptySet()).map { it.path }
        assertEquals(false, "europe/alps" in p, p.toString())
        assertEquals(true, "europe/austria" in p)
    }

    @Test
    fun mapAroundsMapsFirstAndItsCountryOverBasicStates() {
        val built = mapOf(
            "asia/israel-and-palestine" to "https://github.com/x/il.map",
            "europe/netherlands" to "https://github.com/x/nl.map",
            "europe/germany" to "https://github.com/x/de.map",
            "europe/germany/bayern" to "https://github.com/x/by.map",
            "europe/germany/berlin" to "https://github.com/x/be.map",
        )
        val p = plan(built)
        assertEquals("https://github.com/x/il.map", p["asia/israel-and-palestine"])
        // The Netherlands built whole, not its provinces: the country.
        assertEquals("https://github.com/x/nl.map", p["europe/netherlands"])
        assertEquals(null, p["europe/netherlands/utrecht"])
        // Germany's states all built: the states, smaller.
        assertEquals(listOf("europe/germany/bayern", "europe/germany/berlin"), p.keys.filter { it.startsWith("europe/germany") })
        assertEquals(true, maps(built).first { it.path == "asia/israel-and-palestine" }.trails)
        assertEquals(false, maps(built).first { it.path == "asia/jordan" }.trails)
    }

    @Test
    fun notWhereAMapIsInstalled() {
        val built = mapOf("europe/netherlands" to "https://github.com/x/nl.map")
        val p = plan(built, installed = setOf("asia__israel-and-palestine.map", "europe__netherlands__utrecht.map"))
        assertEquals(null, p["asia/israel-and-palestine"])
        // A province installed: the other provinces, not the whole country over it.
        assertEquals(null, p["europe/netherlands"])
        assertEquals(null, p["europe/netherlands/utrecht"])
        assertEquals(url("europe/netherlands/drenthe"), p["europe/netherlands/drenthe"])
        // The whole country installed: none of its parts.
        val noEurope = plan(installed = setOf("europe__germany.map", "europe__netherlands.map", "europe__poland.map")).keys
        assertEquals(setOf("asia/israel-and-palestine", "asia/jordan", "australia-oceania/new-zealand"), noEurope)
        // An imported map over Jordan.
        assertEquals(null, plan(others = listOf(GeoBox(30_000_000, 35_000_000, 33_000_000, 38_000_000)))["asia/jordan"])
    }
}
