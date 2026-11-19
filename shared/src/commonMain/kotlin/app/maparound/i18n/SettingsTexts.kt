package app.maparound.i18n

/** Settings: the language, units and formats, the party, the online sources. */
open class SettingsTexts {
    // ---- the list ----
    open val unitsAndFormats = "Units & formats"
    open val party = "Party"
    open val sources = "Sources"

    /** Under "Party": the name and where the party meets ("Dana · public servers"). */
    open fun partySummary(name: String?, server: String) = listOfNotNull(name, server).joinToString(" · ")
    open val noNameYet = "No name yet"

    // ---- units and formats ----
    open val automatic = "Automatic"

    /** A choice that follows the device, with what it is now: "Automatic (km)". */
    open fun automaticNow(now: String) = "Automatic ($now)"
    open val automaticNote = "Automatic follows the device: where it is (its region and time zone) and its clock."
    open val metric = "Metric"
    open val imperial = "Imperial"
    open val nautical = "Nautical"
    open val distance = "Distance"
    open val height = "Height"
    open val speed = "Speed"
    open val direction = "Direction"
    open val time = "Time"
    open val date = "Date"
    open val coordinates = "Coordinates"

    /** The distance choices. */
    open val kmAndM = "km · m"
    open val miAndFt = "mi · ft"
    open val nauticalMiles = "NM"

    /** The direction choices, shown by example: compass points, degrees, both, mils. */
    open val mils = "Mils (6400)"

    // ---- the party ----
    open val yourNameAndLook = "Your name and look"
    open val howOthersSeeYouNext = "How the others see you, in this party and the next."
    open val serverForParties = "For this party and new ones. Invites include it."

    // ---- the screen ----
    open val screen = "Screen"
    open fun screenSummary(open: String, navigating: String) = "Open: $open · Navigating: $navigating"
    open val screenWhileOpen = "With MapAround open"
    open val screenWhileNavigating = "While navigating"
    open val screenSystem = "Phone's setting"
    open val screenAlways = "Always on"
    open fun screenMinutes(n: Int) = "$n min"
    open val screenNote =
        "How long the screen stays on after you last touch it. It doesn't go off sooner than the phone's own " +
            "setting; Always on uses more battery. The Speed screen always keeps it on."

    // ---- sharing ----
    open val sharing = "Sharing"
    open fun shareLinkSummary(link: String) = "Link: $link"
    open val shareLink = "The link in what you share"
    open val shareLinkNote =
        "A point, a place, a route or a track, and the GPX files sent with them, carry this link and no other."
    open val linkMapAround = "MapAround"
    open val linkMapAroundHint = "Opens the place or the route itself, in the app or in any browser"
    open val linkGoogle = "Google Maps"
    open val linkGoogleHint = "A planned route as directions between its points; a track at its start"
    open val linkOsm = "OpenStreetMap"
    open val linkOsmHint = "A planned route on BRouter-Web, OpenStreetMap's map; a track at its start"
    open val linkAll = "All of them"
    open val linkAllHint = "MapAround, Google Maps and OpenStreetMap"

    // ---- the online sources ----
    open val sourcesNote =
        "Every online service MapAround uses. Set your own server for one, or turn it off; otherwise the app's own " +
            "are used, and some regions get their own (a country's national map)."
    open val allDefault = "All default"
    open fun sourcesChanged(own: Int, off: Int) = listOfNotNull(
        own.takeIf { it > 0 }?.let { if (it == 1) "1 of your own" else "$it of your own" },
        off.takeIf { it > 0 }?.let { "$it off" },
    ).joinToString(" · ")
    open val groupMaps = "Maps"
    open val groupTerrain = "Terrain"
    open val groupRouting = "Routing"
    open val groupPlaces = "Search & places"
    open val mapList = "Map list"
    open val mapLayer = "Online map"
    open val streamedMaps = "Maps over the internet"
    open val streamedMapsNote = "Where no map is downloaded, MapAround's maps are read online"
    open val satellite = "Satellite imagery"
    open val trailsOverlay = "Marked trails online"
    open val trailsData = "Marked trails to download"
    open val elevation = "Elevation"
    open val routing = "Online routing"
    open val routingData = "Routing data"
    open val search = "Online search"
    open val wikipedia = "Wikipedia & photos"
    open val wikipediaNote = "Articles and photos around a place (What's here?)"
    open val partyServer = "Party server"

    /** What's used: "Default: brouter.de", "Yours: tiles.example.org", "swisstopo (for Switzerland)". */
    open fun defaultIs(what: String) = "Default: $what"
    open fun yours(what: String) = "Yours: $what"
    open fun forRegion(what: String, region: String) = "$what (for $region)"
    open val off = "Off"
    open val default = "Default"
    open val yourAddress = "Your address"
    open val triedFirst = "Tried first, then the default ones."
    open val check = "Check"
    open val checking = "Checking…"
    open fun works(ms: Long) = "Works ($ms ms)"
    open fun didntWork(problem: String) = "Didn't work: $problem"
    open val offlineMaps = "Offline maps (vector)"
}

/** Hebrew. */
object SettingsTextsHe : SettingsTexts() {
    override val unitsAndFormats = "יחידות ותבניות"
    override val party = "קבוצה"
    override val sources = "מקורות"
    override val noNameYet = "עדיין אין שם"
    override val automatic = "אוטומטי"
    override fun automaticNow(now: String) = "אוטומטי ($now)"
    override val automaticNote = "האפשרות האוטומטית לפי המכשיר: המקום שלו (האזור ואזור הזמן) והשעון שלו."
    override val metric = "מטרי"
    override val imperial = "אימפריאלי"
    override val nautical = "ימי"
    override val distance = "מרחק"
    override val height = "גובה"
    override val speed = "מהירות"
    override val direction = "כיוון"
    override val time = "שעה"
    override val date = "תאריך"
    override val coordinates = "קואורדינטות"
    override val kmAndM = "ק״מ · מ׳"
    override val miAndFt = "מייל · רגל"
    override val nauticalMiles = "מייל ימי"
    override val mils = "מילים (6400)"
    override val yourNameAndLook = "השם והמראה שלך"
    override val howOthersSeeYouNext = "איך האחרים רואים אותך, בקבוצה הזו ובבאות."
    override val serverForParties = "לקבוצה הזו ולחדשות. ההזמנות כוללות אותו."
    override val screen = "מסך"
    override val sharing = "שיתוף"
    override fun shareLinkSummary(link: String) = "קישור: $link"
    override val shareLink = "הקישור במה שמשתפים"
    override val shareLinkNote = "נקודה, מקום, מסלול או הקלטה, וקובצי ה־GPX שנשלחים איתם, נושאים את הקישור הזה ולא אחר."
    override val linkMapAroundHint = "פותח את המקום או את המסלול עצמו, באפליקציה או בכל דפדפן"
    override val linkGoogleHint = "מסלול מתוכנן כהוראות דרך בין הנקודות שלו; הקלטה בנקודת ההתחלה שלה"
    override val linkOsmHint = "מסלול מתוכנן ב־BRouter-Web, על המפה של OpenStreetMap; הקלטה בנקודת ההתחלה שלה"
    override val linkAll = "כולם"
    override val linkAllHint = "MapAround,‏ Google Maps ו־OpenStreetMap"
    override fun screenSummary(open: String, navigating: String) = "פתוחה: $open · בניווט: $navigating"
    override val screenWhileOpen = "כש־MapAround פתוחה"
    override val screenWhileNavigating = "בזמן ניווט"
    override val screenSystem = "לפי הטלפון"
    override val screenAlways = "תמיד דולק"
    override fun screenMinutes(n: Int) = "$n דק׳"
    override val screenNote =
        "כמה זמן המסך נשאר דולק אחרי הנגיעה האחרונה. הוא לא נכבה מוקדם יותר מההגדרה של הטלפון עצמו; תמיד דולק " +
            "צורך יותר סוללה. מסך המהירות תמיד משאיר אותו דולק."
    override val sourcesNote =
        "כל השירותים המקוונים ש־MapAround משתמשת בהם. אפשר להגדיר שרת משלך לכל אחד, או לכבות אותו; אחרת משמשים השירותים " +
            "של האפליקציה, ובחלק מהאזורים יש שירות משלהם (המפה הלאומית של המדינה)."
    override val allDefault = "הכול ברירת מחדל"
    override fun sourcesChanged(own: Int, off: Int) = listOfNotNull(
        own.takeIf { it > 0 }?.let { if (it == 1) "אחד משלך" else "$it משלך" },
        off.takeIf { it > 0 }?.let { "$it כבויים" },
    ).joinToString(" · ")
    override val groupMaps = "מפות"
    override val groupTerrain = "תבליט"
    override val groupRouting = "ניתוב"
    override val groupPlaces = "חיפוש ומקומות"
    override val mapList = "רשימת המפות"
    override val mapLayer = "מפה מקוונת"
    override val streamedMaps = "מפות דרך האינטרנט"
    override val streamedMapsNote = "איפה שלא הורדה מפה, המפות של MapAround נקראות ברשת"
    override val satellite = "תצלומי לוויין"
    override val trailsOverlay = "שבילים מסומנים ברשת"
    override val trailsData = "שבילים מסומנים להורדה"
    override val elevation = "גבהים"
    override val routing = "ניתוב מקוון"
    override val routingData = "נתוני ניתוב"
    override val search = "חיפוש מקוון"
    override val wikipedia = "ויקיפדיה ותמונות"
    override val wikipediaNote = "ערכים ותמונות סביב מקום (מה יש כאן?)"
    override val partyServer = "שרת הקבוצה"
    override fun defaultIs(what: String) = "ברירת מחדל: $what"
    override fun yours(what: String) = "שלך: $what"
    override fun forRegion(what: String, region: String) = "$what (עבור $region)"
    override val off = "כבוי"
    override val default = "ברירת מחדל"
    override val yourAddress = "כתובת משלך"
    override val triedFirst = "נבדקת ראשונה, ואחריה ברירות המחדל."
    override val check = "בדיקה"
    override val checking = "בודק…"
    override fun works(ms: Long) = "עובד ($ms אלפיות שנייה)"
    override fun didntWork(problem: String) = "לא עבד: $problem"
    override val offlineMaps = "מפות אופליין (וקטוריות)"
}
