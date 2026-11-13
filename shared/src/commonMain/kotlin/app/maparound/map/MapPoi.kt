package app.maparound.map

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// Plain Kotlin: the points of interest the map shows (peaks, springs, huts, cafés…), as the
// platform's map reports them under a tap. Unit-tested.

/**
 * A point of interest on the map, from the offline maps' data: where it is and its OSM tags
 * (names in other languages as `name_de`…). [lang]: the user's language, for its name.
 */
data class MapPoi(val lat: Double, val lon: Double, val tags: Map<String, String>, val lang: String = "") {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    /** Its name where it is. */
    val name: String? get() = tags["name"]?.trim()?.takeIf { it.isNotEmpty() }

    /** Its name in [language], if the map has it. */
    fun nameIn(language: String): String? = tags["name_$language"]?.trim()?.takeIf { it.isNotEmpty() }

    /** What it is, in words ("Peak", "Alpine hut"), or null. */
    val kind: String? get() = PoiKinds.label(tags)

    val elevationM: Int? get() = tags["ele"]?.replace(',', '.')?.toDoubleOrNull()?.roundToInt()

    /** Its name in the user's language, or where it is, or what it is. */
    val title: String get() = nameIn(lang) ?: name ?: kind ?: "Point of interest"

    /** "Peak · 2085 m" (and its local name, when [title] is another), without what [title] says. */
    val details: String?
        get() = listOfNotNull(
            name?.takeIf { it != title },
            kind?.takeIf { it != title },
            elevationM?.let { "$it m" },
        ).joinToString(" · ").ifEmpty { null }

    /** Its Wikipedia article from its own `wikipedia` tag ("de:Drei Schwestern"): language and title. */
    val wikipedia: Pair<String, String>?
        get() {
            val w = tags["wikipedia"]?.trim() ?: return null
            val lang = w.substringBefore(':', "")
            if (lang.length !in 2..12 || !lang.all { it.isLetter() || it == '-' }) return null
            val title = w.substringAfter(':').trim()
            return if (title.isEmpty()) null else lang to title
        }

    /** Its Wikidata item from its `wikidata` tag ("Q666946"), which knows its articles in every language. */
    val wikidata: String?
        get() = tags["wikidata"]?.substringBefore(';')?.trim()?.takeIf { WIKIDATA_ID.matches(it) }

    /** Whether it has a Wikipedia article of its own (from its tags). */
    val hasArticle: Boolean get() = wikipedia != null || wikidata != null

    companion object {
        private val WIKIDATA_ID = Regex("Q[1-9][0-9]{0,11}")

        /**
         * The points the platform's map reported ([app.maparound.map.maplibre.NativeMap.poisNear]:
         * `[{"lat":…,"lon":…,"p":{tags}}]`), each place once (the map may draw one in several tiles
         * or layers), nearest to [lat], [lon] first.
         */
        fun parseList(json: String, lat: Double, lon: Double, lang: String = ""): List<MapPoi> {
            val items = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<MapPoi>()
            for (item in items) {
                val o = item as? JsonObject ?: continue
                val la = o["lat"]?.jsonPrimitive?.let { runCatching { it.double }.getOrNull() } ?: continue
                val lo = o["lon"]?.jsonPrimitive?.let { runCatching { it.double }.getOrNull() } ?: continue
                val tags = (o["p"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap().orEmpty()
                val poi = MapPoi(la, lo, tags, lang)
                if (poi.name == null && poi.kind == null) continue
                val key = "${poi.name}|${poi.kind}|${(la * 1e4).roundToInt()}|${(lo * 1e4).roundToInt()}"
                if (seen.add(key)) out += poi
            }
            val here = GeoPoint(lat, lon)
            return out.sortedBy { Geo.distance(here, it.point) }
        }
    }
}

/** What a point of interest is, from its OSM tags, in words. */
object PoiKinds {
    /** The tags that say what a thing is, the most telling first. */
    private val KEYS = listOf(
        "mountain_pass", "natural", "tourism", "historic", "amenity", "leisure", "man_made", "waterway",
        "place", "shop", "railway", "aerialway", "emergency", "sport", "office", "craft", "healthcare",
        "military", "power", "barrier", "highway", "landuse", "building",
    )

    private val NAMES = mapOf(
        "mountain_pass=yes" to "Mountain pass",
        "natural=peak" to "Peak", "natural=volcano" to "Volcano", "natural=saddle" to "Saddle",
        "natural=spring" to "Spring", "natural=hot_spring" to "Hot spring", "natural=geyser" to "Geyser",
        "natural=cave_entrance" to "Cave", "natural=glacier" to "Glacier", "natural=cliff" to "Cliff",
        "natural=rock" to "Rock", "natural=stone" to "Boulder", "natural=tree" to "Tree", "natural=water" to "Water",
        "natural=beach" to "Beach", "natural=wood" to "Wood", "natural=ridge" to "Ridge", "natural=arete" to "Arête",
        "natural=sinkhole" to "Sinkhole", "natural=bay" to "Bay", "natural=cape" to "Cape", "natural=island" to "Island",
        "tourism=alpine_hut" to "Alpine hut", "tourism=wilderness_hut" to "Wilderness hut",
        "tourism=viewpoint" to "Viewpoint", "tourism=camp_site" to "Campsite", "tourism=caravan_site" to "Caravan site",
        "tourism=picnic_site" to "Picnic site", "tourism=information" to "Information", "tourism=hotel" to "Hotel",
        "tourism=hostel" to "Hostel", "tourism=guest_house" to "Guest house", "tourism=chalet" to "Chalet",
        "tourism=attraction" to "Attraction", "tourism=museum" to "Museum", "tourism=artwork" to "Artwork",
        "tourism=zoo" to "Zoo", "tourism=theme_park" to "Theme park",
        "historic=castle" to "Castle", "historic=ruins" to "Ruins", "historic=memorial" to "Memorial",
        "historic=monument" to "Monument", "historic=archaeological_site" to "Archaeological site",
        "historic=wayside_cross" to "Wayside cross", "historic=wayside_shrine" to "Wayside shrine",
        "historic=fort" to "Fort", "historic=battlefield" to "Battlefield",
        "amenity=drinking_water" to "Drinking water", "amenity=water_point" to "Water point",
        "amenity=shelter" to "Shelter", "amenity=fuel" to "Fuel station", "amenity=charging_station" to "Charging station",
        "amenity=parking" to "Parking", "amenity=toilets" to "Toilets", "amenity=place_of_worship" to "Place of worship",
        "amenity=cafe" to "Café", "amenity=restaurant" to "Restaurant", "amenity=fast_food" to "Fast food",
        "amenity=bar" to "Bar", "amenity=pub" to "Pub", "amenity=pharmacy" to "Pharmacy", "amenity=hospital" to "Hospital",
        "amenity=doctors" to "Doctor", "amenity=police" to "Police", "amenity=fire_station" to "Fire station",
        "amenity=bench" to "Bench", "amenity=fountain" to "Fountain", "amenity=atm" to "ATM", "amenity=bank" to "Bank",
        "amenity=school" to "School", "amenity=library" to "Library", "amenity=townhall" to "Town hall",
        "amenity=post_office" to "Post office", "amenity=hunting_stand" to "Hunting stand", "amenity=bbq" to "Barbecue",
        "leisure=picnic_table" to "Picnic table", "leisure=firepit" to "Fire pit", "leisure=park" to "Park",
        "leisure=nature_reserve" to "Nature reserve", "leisure=playground" to "Playground", "leisure=swimming_pool" to "Swimming pool",
        "man_made=tower" to "Tower", "man_made=water_well" to "Well", "man_made=cairn" to "Cairn",
        "man_made=cross" to "Cross", "man_made=lighthouse" to "Lighthouse", "man_made=mast" to "Mast",
        "man_made=survey_point" to "Survey point", "man_made=windmill" to "Windmill", "man_made=water_tower" to "Water tower",
        "waterway=waterfall" to "Waterfall", "waterway=dam" to "Dam", "waterway=weir" to "Weir",
        "place=city" to "City", "place=town" to "Town", "place=village" to "Village", "place=hamlet" to "Hamlet",
        "place=suburb" to "Suburb", "place=neighbourhood" to "Neighbourhood", "place=locality" to "Place",
        "place=isolated_dwelling" to "Isolated dwelling", "place=farm" to "Farm",
        "shop=supermarket" to "Supermarket", "shop=bakery" to "Bakery", "shop=convenience" to "Shop",
        "railway=station" to "Railway station", "railway=halt" to "Railway halt", "railway=tram_stop" to "Tram stop",
        "aerialway=station" to "Cable car station", "highway=bus_stop" to "Bus stop",
        "emergency=phone" to "Emergency phone", "emergency=defibrillator" to "Defibrillator",
    )

    /** Kinds that say little ("an attraction"): only when nothing more telling is there. */
    private val GENERIC = setOf("tourism=attraction", "historic=yes", "building=yes", "landuse=yes", "amenity=yes")

    fun label(tags: Map<String, String>): String? {
        var generic: String? = null
        for (k in KEYS) {
            val v = tags[k]?.trim() ?: continue
            if (v.isEmpty() || v == "no") continue
            val word = NAMES["$k=$v"] ?: humanize(if (v == "yes") k else v)
            if ("$k=$v" in GENERIC) {
                if (generic == null) generic = word
                continue
            }
            return word
        }
        return generic
    }

    private fun humanize(s: String) = s.replace('_', ' ').replace(';', ',').replaceFirstChar { it.uppercase() }
}
