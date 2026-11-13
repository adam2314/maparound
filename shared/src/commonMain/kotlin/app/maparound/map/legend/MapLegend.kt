package app.maparound.map.legend

import app.maparound.i18n.T
import app.maparound.map.PoiKinds
import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler

// Plain Kotlin: a map style's legend, made from the style itself. Unit-tested.

/**
 * The legend of an offline map style (a mapsforge render theme): sample features — a road of each
 * kind, a track of each grade, a spring, a hut… — put through the theme's rules the way the map
 * draws them, close up; what the theme draws for each is its sample. What the style doesn't draw
 * is left out, and a variant (a track grade, a path's difficulty) only shows when the style draws
 * it differently from the plain kind. So the legend always matches the style, imported ones too,
 * and the style options (categories) that are on.
 */
object MapLegend {
    /** A line the style draws: colour (ARGB), width and dashes in the theme's pixels, offset sideways. */
    data class Line(val color: Long, val width: Float, val dash: List<Float>?, val offset: Float)

    /** A filled area, maybe outlined. */
    data class Area(val fill: Long?, val stroke: Long?, val strokeWidth: Float)

    /** A dot. */
    data class Circle(val radius: Float, val fill: Long?, val stroke: Long?, val strokeWidth: Float)

    /** What the style draws for a feature, in drawing order; [symbol]: its icon ("symbols/peak.svg"). */
    data class Sample(
        val areas: List<Area> = emptyList(),
        val lines: List<Line> = emptyList(),
        val circles: List<Circle> = emptyList(),
        val symbol: String? = null,
        /** Hill shading (a note, not a feature). */
        val hillshade: Boolean = false,
    ) {
        val isEmpty: Boolean get() = areas.isEmpty() && lines.isEmpty() && circles.isEmpty() && symbol == null && !hillshade
    }

    class Entry(val label: String, val sample: Sample)
    class Section(val title: String, val entries: List<Entry>)

    /**
     * The legend of the style [themeXml] with the options [categories] on (null: all), in the app's
     * language. Empty sections are left out.
     */
    fun build(themeXml: String, categories: Set<String>? = null): List<Section> {
        val theme = runCatching { parse(themeXml, categories) }.getOrNull() ?: return emptyList()
        return LegendCatalog.sections.mapNotNull { spec ->
            val entries = ArrayList<Entry>()
            // A variant's sample next to its group's plain one: shown only when they differ.
            val plain = HashMap<String, Sample>()
            for (item in spec.items) {
                val sample = if (item.hillshade) {
                    if (theme.hillshade) Sample(hillshade = true) else continue
                } else {
                    theme.sample(item.element, item.tags, item.closed, item.zoom)
                }
                if (sample.isEmpty) continue
                val group = item.group
                if (group != null) {
                    val base = plain[group]
                    if (base == null) plain[group] = sample else if (base == sample) continue
                }
                // The same drawing twice in a section (two names for one thing): once.
                if (entries.any { it.sample == sample && it.sample.symbol == null && item.group == null }) continue
                entries += Entry(item.label(), sample)
            }
            if (entries.isEmpty()) null else Section(spec.title(), entries)
        }
    }

    // ---- the theme's rules, as mapsforge reads them -------------------------------------------------

    private sealed class Node
    private class Rule(val attrs: Map<String, String>) : Node() {
        val children = ArrayList<Node>()
    }
    private class Instruction(val name: String, val attrs: Map<String, String>) : Node()

    private class Theme(val children: List<Node>, val hillshade: Boolean) {
        /** What the rules draw for a feature at [zoom]: every matching instruction, in order. */
        fun sample(element: String, tags: Map<String, String>, closed: Boolean, zoom: Int): Sample {
            val areas = ArrayList<Area>()
            val lines = ArrayList<Line>()
            val circles = ArrayList<Circle>()
            var symbol: String? = null
            fun visit(nodes: List<Node>) {
                for (n in nodes) {
                    when (n) {
                        is Rule -> if (n.matches(element, tags, closed, zoom)) visit(n.children)
                        is Instruction -> {
                            val a = n.attrs
                            if (a["display"] == "never") continue
                            when (n.name) {
                                "area" -> if (element == "way" && closed) {
                                    areas += Area(color(a["fill"]), color(a["stroke"]), a["stroke-width"]?.toFloatOrNull() ?: 0f)
                                }
                                "line" -> if (element == "way") color(a["stroke"])?.let { c ->
                                    val width = a["stroke-width"]?.toFloatOrNull() ?: 1f
                                    val dash = a["stroke-dasharray"]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size >= 2 }
                                    lines += Line(c, width, dash, a["dy"]?.toFloatOrNull() ?: 0f)
                                }
                                "circle" -> a["radius"]?.toFloatOrNull()?.let { r ->
                                    circles += Circle(r, color(a["fill"]), color(a["stroke"]), a["stroke-width"]?.toFloatOrNull() ?: 0f)
                                }
                                "symbol" -> if (symbol == null) {
                                    symbol = a["src"]?.substringAfter(':')?.takeIf { it.endsWith(".svg") }
                                }
                            }
                        }
                    }
                }
            }
            visit(children)
            return Sample(areas, lines, circles, symbol)
        }
    }

    private fun Rule.matches(element: String, tags: Map<String, String>, closed: Boolean, zoom: Int): Boolean {
        val e = attrs["e"] ?: "any"
        if (e != "any" && e != element) return false
        when (attrs["closed"]) {
            "yes" -> if (!closed) return false
            "no" -> if (closed) return false
        }
        if (zoom < (attrs["zoom-min"]?.toIntOrNull() ?: 0) || zoom > (attrs["zoom-max"]?.toIntOrNull() ?: 99)) return false
        val keys = attrs["k"].orEmpty().split('|')
        val values = attrs["v"].orEmpty().split('|')
        val negative = "~" in values
        val wanted = values.filter { it != "~" }
        if (keys == listOf("*") && wanted == listOf("*")) return true
        val present = keys.filter { it == "*" || it in tags }
        if (negative && present.isEmpty()) return true
        return present.any { k -> if (k == "*") tags.values.any { it in wanted } else wanted == listOf("*") || tags[k] in wanted }
    }

    private fun parse(xml: String, categories: Set<String>?): Theme {
        val root = ArrayList<Node>()
        val stack = arrayListOf<MutableList<Node>>(root)
        var skipping = 0
        var hillshade = false
        var sawTheme = false
        Xml.parse(xml, object : XmlHandler {
            override fun start(name: String, attrs: Map<String, String>) {
                val off = name == "stylemenu" || (categories != null && attrs["cat"]?.let { it !in categories } == true)
                if (skipping > 0 || off) {
                    skipping++
                    return
                }
                when (name) {
                    "rendertheme" -> sawTheme = true
                    "rule" -> Rule(attrs).also { stack.last().add(it); stack += it.children }
                    "hillshading" -> hillshade = true
                    else -> stack.last().add(Instruction(name, attrs))
                }
            }

            override fun end(name: String) {
                if (skipping > 0) {
                    skipping--
                    return
                }
                if (name == "rule" && stack.size > 1) stack.removeAt(stack.lastIndex)
            }
        })
        require(sawTheme) { "Not a render theme" }
        return Theme(root, hillshade)
    }

    /** "#RRGGBB" or "#AARRGGBB" as ARGB; null for none or a colour it can't read. */
    internal fun color(s: String?): Long? {
        val hex = s?.trim()?.removePrefix("#") ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> 0xFF000000L or v
            8 -> v
            3 -> {
                val r = (v shr 8) and 0xF
                val g = (v shr 4) and 0xF
                val b = v and 0xF
                0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            else -> null
        }?.takeIf { (it ushr 24) > 0 }
    }
}

/** What the legend shows: sections of sample features, labelled in the app's language. */
internal object LegendCatalog {
    class Item(
        val label: () -> String,
        val element: String,
        val tags: Map<String, String>,
        val closed: Boolean = false,
        val zoom: Int = 16,
        /** Variants of one kind (track grades): shown only when drawn unlike the group's first. */
        val group: String? = null,
        val hillshade: Boolean = false,
    )

    class SectionSpec(val title: () -> String, val items: List<Item>)

    private fun way(label: () -> String, vararg tags: Pair<String, String>, zoom: Int = 16, group: String? = null) =
        Item(label, "way", mapOf(*tags), closed = false, zoom = zoom, group = group)

    private fun area(label: () -> String, vararg tags: Pair<String, String>, zoom: Int = 15) =
        Item(label, "way", mapOf(*tags), closed = true, zoom = zoom)

    /** A point of interest, named as the app names its kind ("Alpine hut"). */
    private fun poi(vararg tags: Pair<String, String>) =
        Item({ PoiKinds.label(mapOf(*tags)) ?: tags.first().second }, "node", mapOf(*tags), zoom = 17)

    private val l get() = T.legend

    val sections: List<SectionSpec>
        get() = listOf(
            SectionSpec({ l.roads }, listOf(
                way({ l.motorway }, "highway" to "motorway", zoom = 15),
                way({ l.trunk }, "highway" to "trunk", zoom = 15),
                way({ l.primary }, "highway" to "primary", zoom = 15),
                way({ l.secondary }, "highway" to "secondary", zoom = 15),
                way({ l.tertiary }, "highway" to "tertiary", zoom = 15),
                way({ l.minorRoad }, "highway" to "unclassified"),
                way({ l.street }, "highway" to "residential"),
                way({ l.serviceRoad }, "highway" to "service"),
            )),
            SectionSpec({ l.tracks }, listOf(
                way({ l.track }, "highway" to "track", group = "track"),
                way({ l.trackGrade(1) }, "highway" to "track", "tracktype" to "grade1", group = "track"),
                way({ l.trackGrade(2) }, "highway" to "track", "tracktype" to "grade2", group = "track"),
                way({ l.trackGrade(3) }, "highway" to "track", "tracktype" to "grade3", group = "track"),
                way({ l.trackGrade(4) }, "highway" to "track", "tracktype" to "grade4", group = "track"),
                way({ l.trackGrade(5) }, "highway" to "track", "tracktype" to "grade5", group = "track"),
                way({ l.fourWdOnly }, "highway" to "track", "4wd_only" to "yes", group = "track"),
            )),
            SectionSpec({ l.paths }, listOf(
                way({ l.path }, "highway" to "path", group = "path"),
                way({ l.sacScale(1) }, "highway" to "path", "sac_scale" to "hiking", group = "path"),
                way({ l.sacScale(2) }, "highway" to "path", "sac_scale" to "mountain_hiking", group = "path"),
                way({ l.sacScale(3) }, "highway" to "path", "sac_scale" to "demanding_mountain_hiking", group = "path"),
                way({ l.sacScale(4) }, "highway" to "path", "sac_scale" to "alpine_hiking", group = "path"),
                way({ l.sacScale(5) }, "highway" to "path", "sac_scale" to "demanding_alpine_hiking", group = "path"),
                way({ l.sacScale(6) }, "highway" to "path", "sac_scale" to "difficult_alpine_hiking", group = "path"),
                way({ l.faintPath }, "highway" to "path", "trail_visibility" to "bad", group = "path"),
                way({ l.footway }, "highway" to "footway"),
                way({ l.cycleway }, "highway" to "cycleway"),
                way({ l.bridleway }, "highway" to "bridleway"),
                way({ l.steps }, "highway" to "steps"),
                way({ l.viaFerrata }, "highway" to "via_ferrata"),
            )),
            SectionSpec({ l.markedTrails }, TRAIL_COLOURS.map { c ->
                way({ l.markedTrail(c) }, "trail" to "hiking", "trail_n" to "1", "trail_offset" to "0", "trail_colour" to c, zoom = 15)
            }),
            SectionSpec({ l.railAndLifts }, listOf(
                way({ l.railway }, "railway" to "rail", zoom = 15),
                way({ l.tram }, "railway" to "tram"),
                way({ l.cableCar }, "aerialway" to "cable_car"),
                way({ l.gondola }, "aerialway" to "gondola"),
                way({ l.chairLift }, "aerialway" to "chair_lift"),
                way({ l.ferry }, "route" to "ferry", zoom = 14),
            )),
            SectionSpec({ l.water }, listOf(
                way({ l.river }, "waterway" to "river", zoom = 15),
                way({ l.stream }, "waterway" to "stream"),
                way({ l.canal }, "waterway" to "canal"),
                area({ l.lake }, "natural" to "water"),
                area({ l.wetland }, "natural" to "wetland"),
                area({ l.glacier }, "natural" to "glacier"),
                poi("natural" to "spring"),
                poi("amenity" to "drinking_water"),
                poi("waterway" to "waterfall"),
            )),
            SectionSpec({ l.land }, listOf(
                area({ l.forest }, "landuse" to "forest"),
                area({ l.forest }, "natural" to "wood"),
                area({ l.scrub }, "natural" to "scrub"),
                area({ l.heath }, "natural" to "heath"),
                area({ l.meadow }, "landuse" to "meadow"),
                area({ l.grassland }, "natural" to "grassland"),
                area({ l.farmland }, "landuse" to "farmland"),
                area({ l.orchard }, "landuse" to "orchard"),
                area({ l.vineyard }, "landuse" to "vineyard"),
                area({ l.bareRock }, "natural" to "bare_rock"),
                area({ l.scree }, "natural" to "scree"),
                area({ l.sand }, "natural" to "sand"),
                area({ l.beach }, "natural" to "beach"),
                area({ l.builtUp }, "landuse" to "residential"),
                area({ l.industrial }, "landuse" to "industrial"),
                area({ l.military }, "landuse" to "military"),
                area({ l.cemetery }, "landuse" to "cemetery"),
                area({ l.park }, "leisure" to "park"),
            )),
            SectionSpec({ l.terrain }, listOf(
                way({ l.contourIndex }, "contour" to "major", "ele" to "1000", zoom = 14),
                way({ l.contour }, "contour" to "minor", "ele" to "1020", zoom = 14),
                Item({ l.hillShading }, "way", emptyMap(), hillshade = true),
                way({ l.cliff }, "natural" to "cliff"),
                way({ l.ridge }, "natural" to "ridge"),
                way({ l.arete }, "natural" to "arete"),
                way({ l.earthBank }, "natural" to "earth_bank"),
                poi("natural" to "peak"),
                poi("natural" to "saddle"),
                poi("mountain_pass" to "yes"),
                poi("natural" to "cave_entrance"),
            )),
            SectionSpec({ l.boundaries }, listOf(
                way({ l.countryBorder }, "boundary" to "administrative", "admin_level" to "2", zoom = 12),
                way({ l.regionBorder }, "boundary" to "administrative", "admin_level" to "4", zoom = 12),
                area({ l.nationalPark }, "boundary" to "national_park", zoom = 13),
                area({ l.natureReserve }, "leisure" to "nature_reserve", zoom = 13),
                area({ l.protectedArea }, "boundary" to "protected_area", zoom = 13),
            )),
            SectionSpec({ l.stays }, listOf(
                poi("tourism" to "alpine_hut"),
                poi("tourism" to "wilderness_hut"),
                poi("amenity" to "shelter"),
                poi("tourism" to "camp_site"),
                poi("tourism" to "caravan_site"),
                poi("tourism" to "picnic_site"),
                poi("tourism" to "chalet"),
                poi("tourism" to "hostel"),
                poi("tourism" to "hotel"),
            )),
            SectionSpec({ l.services }, listOf(
                poi("tourism" to "viewpoint"),
                poi("tourism" to "information"),
                poi("amenity" to "toilets"),
                poi("amenity" to "parking"),
                poi("amenity" to "fuel"),
                poi("amenity" to "restaurant"),
                poi("amenity" to "cafe"),
                poi("shop" to "supermarket"),
                poi("amenity" to "pharmacy"),
                poi("amenity" to "hospital"),
                poi("amenity" to "place_of_worship"),
                poi("barrier" to "gate"),
            )),
            SectionSpec({ l.landmarks }, listOf(
                poi("historic" to "castle"),
                poi("historic" to "ruins"),
                poi("historic" to "archaeological_site"),
                poi("historic" to "memorial"),
                poi("tourism" to "attraction"),
                poi("man_made" to "lighthouse"),
                poi("man_made" to "windmill"),
                poi("man_made" to "tower"),
            )),
        )

    /** The marked trails' colours, as MapAround's maps tag them (trail_colour). */
    val TRAIL_COLOURS = listOf("red", "blue", "green", "black", "yellow", "orange", "purple", "white", "brown")
}
