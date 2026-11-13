package app.maparound.i18n

/** The map's legend: its sections, the kinds of roads, paths, water and land, and the online maps' legends. */
open class LegendTexts {
    open val legend = "Legend"

    /** Under the offline style's legend. */
    open val drawnLikeThis = "As this style draws them, close up. Not everything shows at every zoom."

    // ---- sections ----
    open val roads = "Roads"
    open val tracks = "Tracks"
    open val paths = "Paths"
    open val markedTrails = "Marked trails"
    open val railAndLifts = "Railways and lifts"
    open val water = "Water"
    open val land = "Land"
    open val terrain = "Terrain"
    open val boundaries = "Boundaries"
    open val stays = "Shelter and stays"
    open val services = "Places and services"
    open val landmarks = "History and landmarks"

    // ---- roads ----
    open val motorway = "Motorway"
    open val trunk = "Trunk road"
    open val primary = "Main road"
    open val secondary = "Secondary road"
    open val tertiary = "Regional road"
    open val minorRoad = "Minor road"
    open val street = "Street"
    open val serviceRoad = "Service road"

    // ---- tracks ----
    open val track = "Track"

    /** Tracks by their surface: grade 1 (hard) to 5 (soft). */
    open fun trackGrade(grade: Int) = when (grade) {
        1 -> "Track, paved or hard"
        2 -> "Track, gravel"
        3 -> "Track, mixed surface"
        4 -> "Track, mostly soft"
        else -> "Track, soft (sand, grass)"
    }

    open val fourWdOnly = "Track, 4×4 only"

    // ---- paths ----
    open val path = "Path"

    /** Paths by their difficulty, the SAC scale T1–T6. */
    open fun sacScale(level: Int) = when (level) {
        1 -> "Path, easy (T1)"
        2 -> "Mountain path (T2)"
        3 -> "Demanding mountain path (T3)"
        4 -> "Alpine route (T4)"
        5 -> "Demanding alpine route (T5)"
        else -> "Difficult alpine route (T6)"
    }

    open val faintPath = "Path, hard to see on the ground"
    open val footway = "Footway"
    open val cycleway = "Cycle path"
    open val bridleway = "Bridleway"
    open val steps = "Steps"
    open val viaFerrata = "Via ferrata"

    // ---- marked trails ----
    open fun markedTrail(colour: String) = "Trail marked ${colourName(colour)}"

    open fun colourName(colour: String) = when (colour) {
        "red" -> "red"
        "blue" -> "blue"
        "green" -> "green"
        "black" -> "black"
        "yellow" -> "yellow"
        "orange" -> "orange"
        "purple" -> "purple"
        "white" -> "white"
        "brown" -> "brown"
        else -> colour
    }

    // ---- rail and lifts ----
    open val railway = "Railway"
    open val tram = "Tram, light rail"
    open val cableCar = "Cable car"
    open val gondola = "Gondola lift"
    open val chairLift = "Chair lift"
    open val ferry = "Ferry"

    // ---- water ----
    open val river = "River"
    open val stream = "Stream"
    open val canal = "Canal, ditch"
    open val lake = "Lake, pond"
    open val wetland = "Wetland"
    open val glacier = "Glacier"

    // ---- land ----
    open val forest = "Forest"
    open val scrub = "Scrub"
    open val heath = "Heath"
    open val meadow = "Meadow"
    open val grassland = "Grassland"
    open val farmland = "Farmland"
    open val orchard = "Orchard"
    open val vineyard = "Vineyard"
    open val bareRock = "Bare rock"
    open val scree = "Scree"
    open val sand = "Sand"
    open val beach = "Beach"
    open val builtUp = "Built-up area"
    open val industrial = "Industrial area"
    open val military = "Military area"
    open val cemetery = "Cemetery"
    open val park = "Park"

    // ---- terrain ----
    open val contourIndex = "Index contour, with its height"
    open val contour = "Contour line"
    open val hillShading = "Hill shading (light from the northwest)"
    open val cliff = "Cliff"
    open val ridge = "Ridge"
    open val arete = "Arête"
    open val earthBank = "Earth bank"

    // ---- boundaries ----
    open val countryBorder = "Country border"
    open val regionBorder = "Region border"
    open val nationalPark = "National park"
    open val natureReserve = "Nature reserve"
    open val protectedArea = "Protected area"

    // ---- online maps ----
    /** The online map's own legend, on its makers' site. */
    open fun onlineLegend(name: String) = "$name's legend, on its makers' site."
    open val openLegend = "Open the legend"

    /** For maps drawn in OpenStreetMap's standard style or close to it. */
    open val standardKeyNote = "This map is drawn like OpenStreetMap's standard map; its map key explains the colours and symbols."
    open val imageryNote = "Satellite and aerial photos: no legend needed, what you see is on the ground."
    open val noLegend = "This map's makers don't publish a legend MapAround knows of."
    open val trailOverlayNote = "Marked trails over the map (Waymarked Trails): the line's colour tells how far the route goes — international, national, regional or local."
}

/** Hebrew. */
object LegendTextsHe : LegendTexts() {
    override val legend = "מקרא"
    override val drawnLikeThis = "כפי שהסגנון הזה מצייר אותם מקרוב. לא הכול מוצג בכל רמת זום."

    override val roads = "כבישים"
    override val tracks = "דרכי עפר"
    override val paths = "שבילים"
    override val markedTrails = "שבילים מסומנים"
    override val railAndLifts = "מסילות ורכבלים"
    override val water = "מים"
    override val land = "שטח"
    override val terrain = "תבליט"
    override val boundaries = "גבולות"
    override val stays = "לינה ומחסה"
    override val services = "מקומות ושירותים"
    override val landmarks = "היסטוריה וציוני דרך"

    override val motorway = "כביש מהיר"
    override val trunk = "כביש עורקי"
    override val primary = "כביש ראשי"
    override val secondary = "כביש משני"
    override val tertiary = "כביש אזורי"
    override val minorRoad = "כביש מקומי"
    override val street = "רחוב"
    override val serviceRoad = "דרך שירות"

    override val track = "דרך עפר"
    override fun trackGrade(grade: Int) = when (grade) {
        1 -> "דרך עפר קשה או סלולה"
        2 -> "דרך עפר מכבישה (חצץ)"
        3 -> "דרך עפר מעורבת"
        4 -> "דרך עפר רכה ברובה"
        else -> "דרך עפר רכה (חול, עשב)"
    }
    override val fourWdOnly = "דרך עפר לרכבי 4×4 בלבד"

    override val path = "שביל"
    override fun sacScale(level: Int) = when (level) {
        1 -> "שביל קל (T1)"
        2 -> "שביל הררי (T2)"
        3 -> "שביל הררי תובעני (T3)"
        4 -> "מסלול אלפיני (T4)"
        5 -> "מסלול אלפיני תובעני (T5)"
        else -> "מסלול אלפיני קשה (T6)"
    }
    override val faintPath = "שביל שקשה לראות בשטח"
    override val footway = "שביל להולכי רגל"
    override val cycleway = "שביל אופניים"
    override val bridleway = "שביל רכיבה על סוסים"
    override val steps = "מדרגות"
    override val viaFerrata = "ויה פרטה"

    override fun markedTrail(colour: String) = "שביל מסומן ב${colourName(colour)}"
    override fun colourName(colour: String) = when (colour) {
        "red" -> "אדום"
        "blue" -> "כחול"
        "green" -> "ירוק"
        "black" -> "שחור"
        "yellow" -> "צהוב"
        "orange" -> "כתום"
        "purple" -> "סגול"
        "white" -> "לבן"
        "brown" -> "חום"
        else -> colour
    }

    override val railway = "מסילת רכבת"
    override val tram = "רכבת קלה"
    override val cableCar = "רכבל"
    override val gondola = "רכבל גונדולות"
    override val chairLift = "רכבל כיסאות"
    override val ferry = "מעבורת"

    override val river = "נהר"
    override val stream = "נחל"
    override val canal = "תעלה"
    override val lake = "אגם, בריכה"
    override val wetland = "ביצה"
    override val glacier = "קרחון"

    override val forest = "יער"
    override val scrub = "חורש"
    override val heath = "בתה"
    override val meadow = "אחו"
    override val grassland = "שטח עשבוני"
    override val farmland = "שטח חקלאי"
    override val orchard = "מטע"
    override val vineyard = "כרם"
    override val bareRock = "סלע חשוף"
    override val scree = "מפולת אבנים"
    override val sand = "חול"
    override val beach = "חוף"
    override val builtUp = "שטח בנוי"
    override val industrial = "אזור תעשייה"
    override val military = "שטח צבאי"
    override val cemetery = "בית קברות"
    override val park = "פארק"

    override val contourIndex = "קו גובה ראשי, עם הגובה"
    override val contour = "קו גובה"
    override val hillShading = "הצללת תבליט (אור מצפון־מערב)"
    override val cliff = "מצוק"
    override val ridge = "רכס"
    override val arete = "רכס חד"
    override val earthBank = "דופן עפר"

    override val countryBorder = "גבול מדינה"
    override val regionBorder = "גבול מחוז"
    override val nationalPark = "גן לאומי"
    override val natureReserve = "שמורת טבע"
    override val protectedArea = "שטח מוגן"

    override fun onlineLegend(name: String) = "המקרא של $name, באתר של יוצרי המפה."
    override val openLegend = "פתיחת המקרא"
    override val standardKeyNote = "המפה הזאת מצוירת כמו המפה הרגילה של OpenStreetMap; המקרא שלה מסביר את הצבעים והסמלים."
    override val imageryNote = "תצלומי לוויין ואוויר: אין צורך במקרא, מה שרואים זה מה שיש בשטח."
    override val noLegend = "יוצרי המפה הזאת לא מפרסמים מקרא ש־MapAround מכירה."
    override val trailOverlayNote = "שבילים מסומנים מעל המפה (Waymarked Trails): צבע הקו מציין את היקף המסלול — בין־לאומי, ארצי, אזורי או מקומי."
}
