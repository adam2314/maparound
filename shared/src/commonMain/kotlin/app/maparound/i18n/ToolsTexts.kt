package app.maparound.i18n

/** The map tools: azimuth & range, line of sight, viewshed, radio links, coordinates, and their messages. */
open class ToolsTexts {
    // ---- the panel and its modes ----
    open val modeAzimuth = "Azimuth"
    open val modeSight = "Sight"
    open val modeViewshed = "Viewshed"
    open val modeCoords = "Coords"
    open val closeTools = "Close map tools"

    // ---- points A and B ----
    /** A point's chip before it's placed: "A: tap map". */
    open fun chipTapMap(name: String) = "$name: tap map"

    /** A placed point's chip with its ground height: "A · 812 m". */
    open fun chipHeight(name: String, heightM: Int) = "$name · $heightM m"
    open fun putAtMyLocation(point: String) = "Put $point at my location"
    open val swapAB = "Swap A and B"
    open val placeBByBearing = "Place B by bearing and distance from A"

    // ---- azimuth & range ----
    open val measureTapA = "Tap the map to place A, the point you measure from."
    open val measureTapB = "Now tap B. Further taps move B; tap a chip to move A instead."

    /** Under the distance and azimuth: "Magnetic 123° · 2187 mil · back 303°". */
    open fun magneticMilsBack(magnetic: String, mils: Int, back: String) = "Magnetic $magnetic · $mils mil · back $back"

    /**
     * "Height 120 → 340 m · Δ +220 m · +4.5°", and " · slant 2.8 km" when [slant] isn't null.
     * [delta] and [angle] come signed ("+220", "-4.5").
     */
    open fun heightChange(from: Int, to: Int, delta: String, angle: String, slant: String?) =
        "Height $from → $to m · Δ $delta m · $angle°" + (if (slant != null) " · slant $slant" else "")

    /** [declination]: signed degrees, without the ° sign. */
    open fun bearingNote(declination: String) =
        "True north bearing along the great circle; magnetic from the WMM declination at A ($declination°)."

    // ---- place B by bearing and distance ----
    open val placeBTitle = "Place B from A"
    open val degrees = "Degrees"
    open val mils6400 = "Mils (6400)"
    open val bearingMils = "Bearing (mil)"
    open val bearingDegrees = "Bearing (°)"
    open val magneticBearing = "Magnetic bearing (compass)"
    open val distanceField = "Distance (m, or e.g. 2.5 km)"
    open val placeB = "Place B"

    // ---- line of sight ----
    open val radioLink = "Radio link"
    open val radioLinkDetail = "Antennas at A and B, Fresnel zone and path loss"
    open val antennaA = "Antenna at A (m)"
    open val antennaB = "Antenna at B (m)"
    open val eyeA = "Eye at A (m)"
    open val targetB = "Target at B (m)"
    open val radioTapA = "Tap the map to place A, the first antenna."
    open val sightTapA = "Tap the map to place A, where you stand."
    open val radioTapB = "Now tap B, the other antenna. Further taps move B."
    open val sightTapB = "Now tap B, what you want to see. Further taps move B."
    open val bInSight = "B is in sight from A"
    open val bHidden = "B is hidden from A"
    open fun clearsBy(m: Int) = "The sight line clears the terrain by at least $m m."

    /** [distance]: how far from A the obstacle is. */
    open fun blockedAt(distance: String, groundM: Int, aboveM: Int) =
        "Blocked $distance from A by ground at $groundM m, $aboveM m above the sight line."
    open val blockedByTerrain = "Blocked by terrain."
    open val sightNote = "Bare terrain with Earth curvature and refraction; trees and buildings aren't included."

    // ---- radio link ----
    open val radioBlocked = "Radio path blocked"
    open val radioClear = "Clear radio path"
    open val fresnelPartlyBlocked = "Fresnel zone partly blocked"
    open val fresnelAllClear = "The whole first Fresnel zone is clear of the terrain."

    /** [percent]: "75%"; [at]: the tightest point's distance from A. */
    open fun fresnelMostlyClear(percent: String, at: String) =
        "At least $percent of the first Fresnel zone is clear (the rule of thumb is 60%); tightest $at from A."

    /** [at]: the tightest point's distance from A; [percent]: "35%". */
    open fun fresnelPartly(at: String, percent: String, extraDb: Int) =
        "The ground $at from A reaches into the Fresnel zone: only $percent of it is clear. " +
            "Expect about $extraDb dB of extra loss."

    /** [at]: the tightest point's distance from A. */
    open fun fresnelAbove(at: String, aboveM: Int, extraDb: Int) =
        "The ground $at from A rises $aboveM m above the direct line between the antennas. " +
            "Expect about $extraDb dB of extra loss (diffraction over it); the signal may still get through."

    /** [terrainDb]: null when the terrain adds no loss. */
    open fun pathLoss(totalDb: Int, freeSpaceDb: Int, terrainDb: Int?, midRadiusM: Int) =
        "Path loss about $totalDb dB: free space $freeSpaceDb dB" +
            (if (terrainDb != null) " + terrain $terrainDb dB" else "") +
            ". The first Fresnel zone is $midRadiusM m in radius halfway."
    open val radioNote = "Bare terrain, with radio's 4/3 Earth radius; trees, buildings, antenna gain and cable loss aren't " +
        "included. Terrain loss is the tightest point as a single knife edge (ITU-R P.526)."
    open val otherFrequency = "Other frequency…"
    open val chooseFrequency = "Choose a frequency"

    // ---- frequency bands (the others are names: LoRa 433 MHz (EU433), PMR446, FRS / GMRS…) ----
    open val bandVhfAmateur = "VHF 2 m (amateur)"
    open val bandMarineVhf = "Marine VHF"
    open val band5800 = "5.8 GHz (Wi-Fi, video)"

    // ---- higher ground near A ----
    open fun higherGround(higherM: Int, awayM: Int) =
        "Ground $higherM m higher is $awayM m away and may block the view (terrain data rounds off summits)."
    open val moveAUp = "Move A up"

    // ---- terrain needed ----
    /** While the area's terrain downloads: [done] of [total] 1° cells. */
    open fun gettingTerrain(done: Int, total: Int) =
        "Getting the terrain data for this area" + (if (total > 1) " ($done of $total)" else "") + "…"
    open val terrainNeeded = "This needs terrain data for the area. Download it with the map (Offline maps → ⋮ → Terrain)."

    /** The button that goes to where terrain is downloaded. */
    open val getTerrain = "Get"
    open val terrainNoConnection = "Couldn't get the terrain data for this area (no connection?)."

    /** What the tools' terrain download is called in the download progress. */
    open val thisArea = "this area"

    // ---- viewshed ----
    open val observer = "Observer"
    open val observeFromMyLocation = "Observe from my location"
    open fun radius(distance: String) = "Radius $distance"
    open val eyeHeight = "Eye height (m)"
    open val targetHeight = "Target height (m)"
    open val viewshedTap = "Tap the map where you'll stand. Green ground is in sight, red is hidden."
    open fun percentInSight(percent: Int) = "$percent% in sight"

    /** Next to "40% in sight": "within 3.0 km". */
    open fun within(distance: String) = "within $distance"

    /** The legend's colours. */
    open val seen = "seen"
    open val hidden = "hidden"

    /** [targetHeight]: "1.7 m", or null when looking at the ground itself; [eyeHeight]: "1.7 m". */
    open fun viewshedNote(targetHeight: String?, eyeHeight: String) =
        (if (targetHeight != null) "Where something $targetHeight tall would be seen" else "Ground in sight") +
            " from $eyeHeight above A. Bare terrain only; trees and buildings aren't included."

    // ---- coordinate formats (UTM, MGRS and Plus Code are names) ----
    open val formatDecimalDegrees = "Decimal degrees"
    open val formatDegreesMinutes = "Degrees & minutes"
    open val formatDms = "Degrees, minutes, seconds"
    open val formatIsraelGrid = "Israel grid (ITM)"
    open val formatMaidenhead = "Maidenhead locator"

    // ---- coordinate converter ----
    open val couldntReadCoords =
        "Couldn't read that. Try 31.7767 35.2345, N31 46.602 E35 14.070, 36R YA 11594 17858, 222304 631558 or 8G3QQ6GM+MR."
    open val coordsPlaceholder = "Type or paste coordinates, any format"
    open val findCoords = "Find these coordinates"
    open val myLocation = "My location"
    open val mapCentre = "Map centre"
    open val sharePoint = "Share this point"
    open val coordsHint = "Tap the map, use your location or the map centre, or type coordinates above."

    /** The format typed coordinates were read as: "Read as MGRS". */
    open fun readAs(format: String) = "Read as $format"

    /** After "Read as …": the point's ground height. */
    open fun groundHeight(m: Int) = "height $m m"
    open fun copyFormat(format: String) = "Copy $format"
    open val coordsNote = "The selected format is used on pins and cards. ITM uses the EPSG Israel 1993 transformation (±0.5 m)."
}

/** Hebrew. */
object ToolsTextsHe : ToolsTexts() {
    /** Right-to-left mark: a text that starts with a Latin letter ("A", "B") still reads right to left. */
    private const val RLM = "‏"

    override val modeAzimuth = "אזימוט"
    override val modeSight = "קו ראייה"
    override val modeViewshed = "שדה ראייה"
    override val modeCoords = "נ״צ"
    override val closeTools = "סגירת כלי המפה"

    override fun chipTapMap(name: String) = "$RLM$name: הקשה במפה"
    override fun chipHeight(name: String, heightM: Int) = "$RLM$name · $heightM מ׳"
    override fun putAtMyLocation(point: String) = "הצבת $point במיקום שלי"
    override val swapAB = "החלפה בין A ל־B"
    override val placeBByBearing = "הצבת B לפי אזימוט ומרחק מ־A"

    override val measureTapA = "יש להקיש על המפה כדי להציב את A, הנקודה שממנה מודדים."
    override val measureTapB = "עכשיו יש להקיש כדי להציב את B. הקשות נוספות מזיזות את B; כדי להזיז את A, יש לבחור קודם ב־A."
    override fun magneticMilsBack(magnetic: String, mils: Int, back: String) =
        "מגנטי $magnetic · $mils אלפיות · אזימוט חוזר $back"
    override fun heightChange(from: Int, to: Int, delta: String, angle: String, slant: String?) =
        "גובה $from ← $to מ׳ · ${ltr("Δ $delta")} מ׳ · ${ltr("$angle°")}" +
            (if (slant != null) " · מרחק אלכסוני $slant" else "")
    override fun bearingNote(declination: String) =
        "אזימוט לפי צפון אמיתי, לאורך המעגל הגדול; המגנטי לפי הסטייה המגנטית ב־A במודל WMM (${ltr("$declination°")})."

    override val placeBTitle = "הצבת B ביחס ל־A"
    override val degrees = "מעלות"
    override val mils6400 = "אלפיות (6400)"
    override val bearingMils = "אזימוט (אלפיות)"
    override val bearingDegrees = "אזימוט (°)"
    override val magneticBearing = "אזימוט מגנטי (מצפן)"
    override val distanceField: String get() = "מרחק במטרים (או למשל ${ltr("2.5 km")})"
    override val placeB = "הצבת B"

    override val radioLink = "קישור רדיו"
    override val radioLinkDetail = "אנטנות ב־A וב־B, אזור פרנל והנחתת מסלול"
    override val antennaA = "גובה אנטנה ב־A (מ׳)"
    override val antennaB = "גובה אנטנה ב־B (מ׳)"
    override val eyeA = "גובה עין ב־A (מ׳)"
    override val targetB = "גובה מטרה ב־B (מ׳)"
    override val radioTapA = "יש להקיש על המפה כדי להציב את A, האנטנה הראשונה."
    override val sightTapA = "יש להקיש על המפה כדי להציב את A, המקום שבו עומדים."
    override val radioTapB = "עכשיו יש להקיש כדי להציב את B, האנטנה השנייה. הקשות נוספות מזיזות את B."
    override val sightTapB = "עכשיו יש להקיש כדי להציב את B, מה שרוצים לראות. הקשות נוספות מזיזות את B."
    override val bInSight = "רואים את B מ־A"
    override val bHidden = "לא רואים את B מ־A"
    override fun clearsBy(m: Int) = "קו הראייה עובר לפחות $m מ׳ מעל פני השטח."
    override fun blockedAt(distance: String, groundM: Int, aboveM: Int) =
        "חסום במרחק $distance מ־A, בקרקע בגובה $groundM מ׳ שמתנשאת $aboveM מ׳ מעל קו הראייה."
    override val blockedByTerrain = "פני השטח חוסמים את קו הראייה."
    override val sightNote = "לפי פני השטח בלבד, כולל עקמומיות כדור הארץ ושבירת האור; עצים ומבנים לא נלקחים בחשבון."

    override val radioBlocked = "נתיב הרדיו חסום"
    override val radioClear = "נתיב רדיו פנוי"
    override val fresnelPartlyBlocked = "אזור פרנל חסום חלקית"
    override val fresnelAllClear = "אזור פרנל הראשון פנוי כולו מפני השטח."
    override fun fresnelMostlyClear(percent: String, at: String) =
        "לפחות $percent מאזור פרנל הראשון פנוי (כלל האצבע הוא 60%); המקום הצר ביותר במרחק $at מ־A."
    override fun fresnelPartly(at: String, percent: String, extraDb: Int) =
        "הקרקע במרחק $at מ־A נכנסת לאזור פרנל: רק $percent ממנו פנוי. " +
            "צפויה הנחתה נוספת של כ־${ltr("$extraDb dB")}."
    override fun fresnelAbove(at: String, aboveM: Int, extraDb: Int) =
        "הקרקע במרחק $at מ־A מתנשאת $aboveM מ׳ מעל הקו הישר בין האנטנות. " +
            "צפויה הנחתה נוספת של כ־${ltr("$extraDb dB")} (עקיפה מעל המכשול); ייתכן שהאות עדיין יעבור."
    override fun pathLoss(totalDb: Int, freeSpaceDb: Int, terrainDb: Int?, midRadiusM: Int) =
        "הנחתת מסלול של כ־${ltr("$totalDb dB")}: מרחב חופשי ${ltr("$freeSpaceDb dB")}" +
            (if (terrainDb != null) " + פני השטח ${ltr("$terrainDb dB")}" else "") +
            ". רדיוס אזור פרנל הראשון באמצע הדרך: $midRadiusM מ׳."
    override val radioNote = "לפי פני השטח בלבד, ברדיוס כדור ארץ אפקטיבי של 4/3 כמקובל ברדיו; עצים, מבנים, הגבר אנטנות " +
        "והפסדי כבלים לא נלקחים בחשבון. הנחתת פני השטח מחושבת בנקודה הצרה ביותר כשפת סכין יחידה (ITU-R P.526)."
    override val otherFrequency = "תדר אחר…"
    override val chooseFrequency = "בחירת תדר"

    override val bandVhfAmateur = "VHF 2 מ׳ (חובבי רדיו)"
    override val bandMarineVhf = "VHF ימי"
    override val band5800 = "5.8 GHz (Wi-Fi, וידאו)"

    override fun higherGround(higherM: Int, awayM: Int) =
        "במרחק $awayM מ׳ יש קרקע גבוהה יותר ב־$higherM מ׳ שעלולה להסתיר את הנוף (נתוני הגובה מעגלים פסגות)."
    override val moveAUp = "העלאת A"

    override fun gettingTerrain(done: Int, total: Int) =
        "מוריד נתוני גובה לאזור הזה" + (if (total > 1) " ($done מתוך $total)" else "") + "…"
    override val terrainNeeded = "לשם כך צריך נתוני גובה של האזור. אפשר להוריד אותם עם המפה (מפות אופליין ← ⋮ ← נתוני גובה)."
    override val getTerrain = "הורדה"
    override val terrainNoConnection = "לא ניתן היה להוריד את נתוני הגובה לאזור הזה (אין חיבור לאינטרנט?)."
    override val thisArea = "האזור הזה"

    override val observer = "נקודת תצפית"
    override val observeFromMyLocation = "תצפית מהמיקום שלי"
    override fun radius(distance: String) = "רדיוס $distance"
    override val eyeHeight = "גובה עין (מ׳)"
    override val targetHeight = "גובה מטרה (מ׳)"
    override val viewshedTap = "יש להקיש על המפה במקום שממנו מסתכלים. שטח ירוק נראה משם, אדום מוסתר."
    override fun percentInSight(percent: Int) = "$percent% נראה"
    override fun within(distance: String) = "ברדיוס $distance"
    override val seen = "נראה"
    override val hidden = "מוסתר"
    override fun viewshedNote(targetHeight: String?, eyeHeight: String) =
        (if (targetHeight != null) "המקומות שבהם עצם בגובה $targetHeight ייראה" else "השטח הנראה") +
            " מגובה $eyeHeight מעל A. לפי פני השטח בלבד; עצים ומבנים לא נלקחים בחשבון."

    override val formatDecimalDegrees = "מעלות עשרוניות"
    override val formatDegreesMinutes = "מעלות ודקות"
    override val formatDms = "מעלות, דקות ושניות"
    override val formatIsraelGrid = "רשת ישראל החדשה (ITM)"
    override val formatMaidenhead = "לוקטור Maidenhead"

    override val couldntReadCoords: String
        get() = "לא ניתן לזהות את הקואורדינטות. אפשר לנסות למשל ${ltr("31.7767 35.2345")}, " +
            "${ltr("N31 46.602 E35 14.070")}, ${ltr("36R YA 11594 17858")}, ${ltr("222304 631558")} או ${ltr("8G3QQ6GM+MR")}."
    override val coordsPlaceholder = "הקלדה או הדבקה של קואורדינטות, בכל פורמט"
    override val findCoords = "חיפוש הקואורדינטות"
    override val myLocation = "המיקום שלי"
    override val mapCentre = "מרכז המפה"
    override val sharePoint = "שיתוף הנקודה"
    override val coordsHint = "אפשר להקיש על המפה, להשתמש במיקום הנוכחי או במרכז המפה, או להקליד קואורדינטות למעלה."
    override fun readAs(format: String) = "זוהה בפורמט $format"
    override fun groundHeight(m: Int) = "גובה $m מ׳"
    override fun copyFormat(format: String) = "העתקת $format"
    override val coordsNote = "הפורמט שנבחר מוצג בסיכות ובכרטיסים. ההמרה ל־ITM לפי טרנספורמציית Israel 1993 של EPSG (±0.5 מ׳)."
}
