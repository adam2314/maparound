package app.maparound.i18n

/** Points of interest and search: what places are (peak, spring…), search results and their messages. */
open class PoiTexts {
    // ---- what places are ----

    /**
     * What a point of interest is, by its OSM tag ("natural=peak" to "Peak"). Tags not here read
     * as their values do ("amenity=ice_cream": "Ice cream").
     */
    open val kinds: Map<String, String> = mapOf(
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
        "barrier=gate" to "Gate", "barrier=stile" to "Stile", "barrier=lift_gate" to "Barrier",
    )

    /**
     * What a search result is, by its OSM tag ("natural=peak"). [asIs]: the tag's value in words
     * ("Peak", "Camp site"), which English keeps; other languages say it with [kinds] where they can.
     */
    open fun searchKind(tag: String, asIs: String): String = asIs

    /** A point of interest's title when it has neither a name nor a kind in words. */
    open val pointOfInterest = "Point of interest"

    // ---- search: what results in the offline maps are ----
    open val path = "Path"
    open val street = "Street"

    /** A marked route (hiking, cycling…) in the offline maps. */
    open val trail = "Trail"
    open val building = "Building"
    open val place = "Place"

    /** A shop of a [kind] ("Shop: Bakery"). */
    open fun shop(kind: String) = "Shop: $kind"

    // ---- search: coordinates and links ----

    /** The name of a point from a map link that has none. */
    open val sharedLocation = "Shared location"
    open val fromTheLink = "From the link"

    // ---- search: where results come from (section headings) ----
    open val sourceCoordinates = "Coordinates"
    open val sourcePlaces = "Your places"
    open val sourceOfflineMap = "Offline map"
    open val sourceOnline = "Online (OpenStreetMap)"

    // ---- the search screen ----
    open val closeSearch = "Close search"
    open val searchHint = "Search places, coordinates…"
    open val clearSearch = "Clear"
    open val nothingNearby = "Nothing in your places or the offline maps within 25 km of the map centre."
    open fun searchOnlineFor(query: String) = "Search online for “$query”"

    /** Under "Search online for …": where it searches. */
    open val searchOnlineWhere = "OpenStreetMap, anywhere in the world"
    open val onlineSearchFailed = "Online search failed (no internet?)"
    open val nothingFoundOnline = "Nothing found online."
}

/** Hebrew. */
object PoiTextsHe : PoiTexts() {
    override val kinds: Map<String, String> = mapOf(
        "mountain_pass=yes" to "מעבר הרים",
        "natural=peak" to "פסגה", "natural=volcano" to "הר געש", "natural=saddle" to "אוכף",
        "natural=spring" to "מעיין", "natural=hot_spring" to "מעיין חם", "natural=geyser" to "גייזר",
        "natural=cave_entrance" to "מערה", "natural=glacier" to "קרחון", "natural=cliff" to "מצוק",
        "natural=rock" to "סלע", "natural=stone" to "גוש סלע", "natural=tree" to "עץ", "natural=water" to "מקווה מים",
        "natural=beach" to "חוף", "natural=wood" to "חורש", "natural=ridge" to "רכס", "natural=arete" to "רכס חד",
        "natural=sinkhole" to "בולען", "natural=bay" to "מפרץ", "natural=cape" to "כף", "natural=island" to "אי",
        "tourism=alpine_hut" to "בקתה אלפינית", "tourism=wilderness_hut" to "בקתת שטח",
        "tourism=viewpoint" to "תצפית", "tourism=camp_site" to "חניון לילה", "tourism=caravan_site" to "חניון קרוואנים",
        "tourism=picnic_site" to "אתר פיקניק", "tourism=information" to "מידע", "tourism=hotel" to "מלון",
        "tourism=hostel" to "אכסניה", "tourism=guest_house" to "בית הארחה", "tourism=chalet" to "בקתת נופש",
        "tourism=attraction" to "אטרקציה", "tourism=museum" to "מוזיאון", "tourism=artwork" to "יצירת אמנות",
        "tourism=zoo" to "גן חיות", "tourism=theme_park" to "פארק שעשועים",
        "historic=castle" to "טירה", "historic=ruins" to "חורבות", "historic=memorial" to "אתר הנצחה",
        "historic=monument" to "אנדרטה", "historic=archaeological_site" to "אתר ארכאולוגי",
        "historic=wayside_cross" to "צלב דרכים", "historic=wayside_shrine" to "מקדש דרכים",
        "historic=fort" to "מבצר", "historic=battlefield" to "שדה קרב",
        "amenity=drinking_water" to "מים לשתייה", "amenity=water_point" to "נקודת מים",
        "amenity=shelter" to "מחסה", "amenity=fuel" to "תחנת דלק", "amenity=charging_station" to "עמדת טעינה",
        "amenity=parking" to "חניה", "amenity=toilets" to "שירותים", "amenity=place_of_worship" to "בית תפילה",
        "amenity=cafe" to "בית קפה", "amenity=restaurant" to "מסעדה", "amenity=fast_food" to "מזון מהיר",
        "amenity=bar" to "בר", "amenity=pub" to "פאב", "amenity=pharmacy" to "בית מרקחת", "amenity=hospital" to "בית חולים",
        "amenity=doctors" to "מרפאה", "amenity=police" to "משטרה", "amenity=fire_station" to "תחנת כיבוי אש",
        "amenity=bench" to "ספסל", "amenity=fountain" to "מזרקה", "amenity=atm" to "כספומט", "amenity=bank" to "בנק",
        "amenity=school" to "בית ספר", "amenity=library" to "ספרייה", "amenity=townhall" to "עירייה",
        "amenity=post_office" to "סניף דואר", "amenity=hunting_stand" to "עמדת ציד", "amenity=bbq" to "מנגל",
        "leisure=picnic_table" to "שולחן פיקניק", "leisure=firepit" to "מקום למדורה", "leisure=park" to "פארק",
        "leisure=nature_reserve" to "שמורת טבע", "leisure=playground" to "גן שעשועים", "leisure=swimming_pool" to "בריכת שחייה",
        "man_made=tower" to "מגדל", "man_made=water_well" to "באר", "man_made=cairn" to "גל אבנים",
        "man_made=cross" to "צלב", "man_made=lighthouse" to "מגדלור", "man_made=mast" to "תורן",
        "man_made=survey_point" to "נקודת מדידה", "man_made=windmill" to "טחנת רוח", "man_made=water_tower" to "מגדל מים",
        "waterway=waterfall" to "מפל", "waterway=dam" to "סכר", "waterway=weir" to "סכר נמוך",
        "place=city" to "עיר", "place=town" to "עיירה", "place=village" to "כפר", "place=hamlet" to "יישוב קטן",
        "place=suburb" to "רובע", "place=neighbourhood" to "שכונה", "place=locality" to "מקום",
        "place=isolated_dwelling" to "בית בודד", "place=farm" to "חווה",
        "shop=supermarket" to "סופרמרקט", "shop=bakery" to "מאפייה", "shop=convenience" to "מכולת",
        "railway=station" to "תחנת רכבת", "railway=halt" to "תחנת רכבת קטנה", "railway=tram_stop" to "תחנת רכבת קלה",
        "aerialway=station" to "תחנת רכבל", "highway=bus_stop" to "תחנת אוטובוס",
        "emergency=phone" to "טלפון חירום", "emergency=defibrillator" to "דפיברילטור",
        "barrier=gate" to "שער", "barrier=stile" to "מדרגת מעבר", "barrier=lift_gate" to "מחסום",
        // Hebrew has words for some that English reads as their values ("Stream", "Historic").
        "historic=yes" to "אתר היסטורי", "building=yes" to "מבנה", "building=house" to "בית", "place=house" to "בית",
        "waterway=stream" to "נחל", "waterway=river" to "נהר", "waterway=wadi" to "ואדי",
        "natural=hill" to "גבעה", "natural=valley" to "עמק", "natural=mountain_range" to "רכס הרים",
        "highway=path" to "שביל", "highway=footway" to "שביל להולכי רגל", "highway=track" to "דרך עפר",
        "highway=residential" to "רחוב", "place=country" to "מדינה", "place=region" to "אזור",
    )

    override fun searchKind(tag: String, asIs: String): String = kinds[tag] ?: asIs

    override val pointOfInterest = "נקודת עניין"

    override val path = "שביל"
    override val street = "רחוב"
    override val trail = "שביל מסומן"
    override val building = "מבנה"
    override val place = "מקום"
    override fun shop(kind: String) = "חנות: $kind"

    override val sharedLocation = "מיקום משותף"
    override val fromTheLink = "מהקישור"

    override val sourceCoordinates = "קואורדינטות"
    override val sourcePlaces = "המקומות שלך"
    override val sourceOfflineMap = "מפת אופליין"
    override val sourceOnline = "אונליין (OpenStreetMap)"

    override val closeSearch = "סגירת החיפוש"
    override val searchHint = "חיפוש מקומות, קואורדינטות…"
    override val clearSearch = "ניקוי"
    override val nothingNearby = "לא נמצא דבר במקומות שלך או במפות האופליין ברדיוס 25 ק״מ ממרכז המפה."
    override fun searchOnlineFor(query: String) = "חיפוש \"$query\" אונליין"
    override val searchOnlineWhere = "בכל העולם, ב־OpenStreetMap"
    override val onlineSearchFailed = "החיפוש אונליין נכשל (אין חיבור לאינטרנט?)"
    override val nothingFoundOnline = "לא נמצאו תוצאות אונליין."
}
