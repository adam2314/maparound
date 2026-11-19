package app.maparound.i18n

import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalityIndicator
import kotlin.math.abs
import kotlin.math.roundToLong

/** The data view (the web app): countries and Israel's localities coloured by their figures. */
open class StatsTexts {
    open val dataView = "Data view"
    open val dataViewSubtitle = "Countries and Israel's localities by their figures, time zones, weather and rain radar, street photos, buses now, GPS traces"
    open val leaveDataView = "Leave the data view"
    open val countries = "Countries"
    open val localities = "Israel's localities"
    open val sizeIsPopulation = "Circles: as many people, as big"
    open val tapToHide = "Tap a colour to hide those, again to show them."
    open val zoomInForLocalities = "Zoom in on Israel for its localities."
    open val noData = "No data"
    open val gettingCountries = "Getting the countries' figures…"
    open val gettingLocalities = "Getting Israel's localities…"
    open val couldntGet = "Couldn't get the data. Online?"
    open val credits = "data: CBS, Population Authority (data.gov.il) · Our World in Data · Natural Earth · Wikidata"

    open fun countryIndicator(i: CountryIndicator) = when (i) {
        CountryIndicator.POPULATION -> "Population"
        CountryIndicator.DENSITY -> "Population density"
        CountryIndicator.GDP -> "GDP per person"
        CountryIndicator.LIFE -> "Life expectancy"
        CountryIndicator.MEDIAN_AGE -> "Median age"
    }

    open fun countryValue(i: CountryIndicator, v: Double): String = when (i) {
        CountryIndicator.POPULATION -> if (v >= 1e6) "${decimal(v / 1e6)} million" else group(v)
        CountryIndicator.DENSITY -> "${group(v)} per km²"
        CountryIndicator.GDP -> "$" + group(v)
        CountryIndicator.LIFE, CountryIndicator.MEDIAN_AGE -> "${decimal(v)} years"
    }

    open fun localityIndicator(i: LocalityIndicator) = when (i) {
        LocalityIndicator.SIZE -> "Size"
        LocalityIndicator.CHILDREN -> "Children (0–18)"
        LocalityIndicator.YOUNG_ADULTS -> "Young adults (19–45)"
        LocalityIndicator.ELDERLY -> "Aged 65 and over"
        LocalityIndicator.ARABS -> "Arab residents"
        LocalityIndicator.TYPE -> "Kind of place"
        LocalityIndicator.RELIGION -> "Religion"
        LocalityIndicator.FOUNDED -> "Founded"
        LocalityIndicator.ALTITUDE -> "Altitude"
        LocalityIndicator.CLUSTER -> "Socio-economic cluster"
        LocalityIndicator.DISTRICT -> "District"
    }

    open fun under(v: String) = "under $v"
    open fun andOver(v: String) = "$v and over"
    open fun range(a: String, b: String) = "$a–$b"
    open fun people(range: String) = "$range people"
    open fun before(year: Int) = "before $year"
    open fun since(year: Int) = "$year and after"
    open val belowSeaLevel = "below sea level"

    open val localityTypes = listOf(
        "Jewish town or city", "Arab town", "Moshav", "Kibbutz", "Community settlement", "Moshava",
        "Arab or Bedouin village", "Institution, other",
    )
    open val religions = listOf("Jewish", "Arab", "Bedouin", "Mixed")

    /** A district by the CBS's (Hebrew) name. */
    open fun district(he: String) = when {
        he == "ירושלים" -> "Jerusalem"
        he == "הצפון" -> "Northern"
        he == "חיפה" -> "Haifa"
        he == "המרכז" -> "Central"
        he == "תל אביב" -> "Tel Aviv"
        he == "הדרום" -> "Southern"
        he.startsWith("אזור יהודה") -> "Judea and Samaria"
        else -> he
    }

    // ---- a tapped place ----
    open fun residents(n: Int) = "${group(n.toDouble())} residents"
    open fun arabsShare(pct: Int) = "Arab residents: $pct%"
    open fun foundedIn(year: Int) = "Founded in $year"
    /** [height] as heights are shown ("812 m", "2664 ft"). */
    open fun altitudeOf(height: String) = "Altitude: ${ltr(height)}"
    open fun clusterOf(c: Int) = "Socio-economic cluster: $c of 10"
    open val byAge = "Residents by age"
    open val ageGroups = listOf("0–5", "6–18", "19–45", "46–55", "56–64", "65+")
    open fun asOf(year: Int) = "($year)"
    open val pinIt = "Pin it"

    // ---- a tapped country ----
    /** Square units: of kilometres, miles or nautical miles, as lengths are shown. */
    open fun areaUnit(u: DistanceUnit) = when (u) {
        DistanceUnit.METRIC -> "km²"
        DistanceUnit.IMPERIAL -> "sq mi"
        DistanceUnit.NAUTICAL -> "sq nmi"
    }
    open fun area(v: String) = "Area: $v"
    open fun capitals(names: String, n: Int) = (if (n > 1) "Capitals: " else "Capital: ") + names
    open fun languages(names: String, n: Int, more: Int) = (if (n + more > 1) "Languages: " else "Language: ") + names + if (more > 0) " and $more more" else ""

    /** "Area: 20,770 km²" (or square miles, by Settings → Units & formats). */
    fun areaOf(km2: Double): String {
        val u = Units.current.distance
        val v = km2 * when (u) {
            DistanceUnit.METRIC -> 1.0
            DistanceUnit.IMPERIAL -> 1 / 2.589988110336
            DistanceUnit.NAUTICAL -> 1 / 3.429904
        }
        val n = when {
            v < 1 -> (v * 100).roundToLong().let { "0.${it.toString().padStart(2, '0')}" }
            v < 10 -> decimal(v)
            else -> group(v)
        }
        return area("$n ${areaUnit(u)}")
    }

    fun capitalsOf(names: List<String>) = capitals(names.joinToString(", "), names.size)

    fun languagesOf(names: List<String>, more: Int) = languages(names.joinToString(", "), names.size, more)

    // ---- the explorer ----
    open val layers = "Data layers"
    open val findData = "Find data"
    open val nothingFound = "Nothing found"
    open val world = "World"
    open val israel = "Israel"
    open val time = "Time"
    open val movement = "Movement"
    open val timeZones = "Time zones"
    open val gpsTraces = "GPS traces"
    open val gpsTracesNote = "Public GPS traces uploaded to OpenStreetMap: the denser the lines, the more people went that way."
    open val gettingTimeZones = "Getting the time zones…"
    /** Words that find them (besides their names). */
    open val countriesKeywords = "countries country"
    open val localitiesKeywords = "localities towns cities villages places"
    open val timeZonesKeywords = "clock hour utc gmt daylight saving summer time dst offset"
    open val gpsKeywords = "heat map heatmap tracks traces activity hiking where people go"
    open val offsetsNow = "Hours from UTC, now"
    open val explorerSubtitle = "The world through its numbers: pick what to show"
    open val onTheMap = "On the map"
    open val nothingOnTheMap = "Nothing on the map yet: pick a layer below."
    open val clearAll = "Clear all"
    open val takeOff = "Take off the map"
    open val showTheMap = "Show the map"
    /** Where each layer's data comes from, under its name. */
    open fun countrySource(i: CountryIndicator) = if (i == CountryIndicator.GDP) "World Bank, via Our World in Data" else "UN, via Our World in Data"
    open fun localitySource(i: LocalityIndicator) = when {
        i.fromAges -> "Population Authority · data.gov.il"
        i == LocalityIndicator.CLUSTER -> "CBS socio-economic clusters (2019)"
        else -> "Central Bureau of Statistics · data.gov.il"
    }
    open val timeZonesSource = "Built in, works offline · timezone-boundary-builder"
    open val gpsTracesSource = "OpenStreetMap · where people walked, rode and drove"
    open val zonesCredit = "time zones: timezone-boundary-builder (© OpenStreetMap contributors)"

    // ---- MapAround's maps: how each region's stands ----
    open val mapAround = "MapAround"
    open val mapBuilds = "MapAround's maps"
    open val mapBuildsSource = "Built every month · MapAround's list of maps"
    open val mapBuildsKeywords = "offline maps download built build monthly regions coverage catalog pipeline"
    open val gettingMapList = "Getting the list of maps…"
    open val couldntGetMapList = "Couldn't get the list of maps. Online?"
    open val buildsCredit = "regions: Geofabrik (© OpenStreetMap contributors)"
    /** The legend's classes. */
    open fun buildState(s: app.maparound.stats.BuildState) = when (s) {
        app.maparound.stats.BuildState.CURRENT -> "Latest build"
        app.maparound.stats.BuildState.OLD -> "Older build"
        app.maparound.stats.BuildState.MISSING -> "No map"
    }
    /** Under the legend: "321 of 484 maps built · latest build 15 Nov 2026". */
    open fun buildsSummary(built: Int, total: Int, latest: String?) =
        "$built of $total maps built" + (latest?.let { " · latest build $it" } ?: "")
    open val tapARegion = "Tap a region for its map."
    // A tapped region.
    open fun builtOn(day: String) = "Up to date: built on $day"
    open fun olderBuild(day: String) = "An older map, built on $day"
    open val needsFunding = "No map yet: too big for the free build machines, until there's money for a bigger one"
    open val buildFailed = "No map: its build failed"
    open val notListed = "Not in the list of maps"
    open fun mapSize(size: String) = "Download: $size"
    /** A country, or group of states, [region] is part of, as one map. */
    open fun asOneMap(region: String, how: String) = "$region, as one map: $how"
    open fun buildShort(s: app.maparound.stats.BuildState, why: app.maparound.data.CatalogEntry.Unavailable?) = when {
        s == app.maparound.stats.BuildState.CURRENT -> "up to date"
        s == app.maparound.stats.BuildState.OLD -> "an older map"
        why == app.maparound.data.CatalogEntry.Unavailable.NEEDS_FUNDING -> "too big for the free build machines"
        why == app.maparound.data.CatalogEntry.Unavailable.NOT_BUILT -> "its build failed"
        else -> "not in the list"
    }
    open val helpFund = "Help fund a bigger machine"
    open val supportUrl = "https://maparound.app/support"

    // ---- a tapped time zone ----
    open val atSea = "At sea"
    open val seaZone = "Nautical time zone"
    open fun localTime(time: String, day: String) = "Local time: $time · $day"
    open fun utcNow(offset: String) = "UTC$offset now"
    open fun ahead(by: String) = "$by ahead of you"
    open fun behind(by: String) = "$by behind you"
    open val sameTime = "Same time as you"
    open fun clocksForward(by: String, day: String, time: String) = "Clocks go forward $by on $day at $time"
    open fun clocksBack(by: String, day: String, time: String) = "Clocks go back $by on $day at $time"
    open val noClockChanges = "No clock changes in the coming year"
    /** A length of time: "1 h", "30 min", "5 h 30 min". */
    open fun hoursMinutes(h: Int, m: Int) = listOfNotNull(if (h > 0) "$h h" else null, if (m > 0 || h == 0) "$m min" else null).joinToString(" ")

    /** 12,345 */
    fun group(v: Double): String {
        val n = v.roundToLong()
        val s = abs(n).toString().reversed().chunked(3).joinToString(",").reversed()
        return if (n < 0) "-$s" else s
    }

    /** 9.8 */
    fun decimal(v: Double): String {
        val t = (v * 10).roundToLong()
        return "${t / 10}.${abs(t % 10)}"
    }
}

object StatsTextsHe : StatsTexts() {
    override val dataView = "תצוגת נתונים"
    override val dataViewSubtitle = "מדינות ויישובי ישראל לפי הנתונים שלהם, אזורי זמן, מזג אוויר ומכ״ם גשם, תמונות רחוב, אוטובוסים עכשיו, מסלולי GPS"
    override val leaveDataView = "יציאה מתצוגת הנתונים"
    override val countries = "מדינות"
    override val localities = "יישובי ישראל"
    override val sizeIsPopulation = "עיגולים: ככל שיותר תושבים, גדול יותר"
    override val tapToHide = "הקשה על צבע מסתירה אותם, הקשה נוספת מחזירה."
    override val zoomInForLocalities = "כדי לראות את היישובים, יש להתקרב לישראל."
    override val noData = "אין נתונים"
    override val gettingCountries = "מוריד את נתוני המדינות…"
    override val gettingLocalities = "מוריד את יישובי ישראל…"
    override val couldntGet = "לא ניתן להוריד את הנתונים. יש חיבור?"
    override val credits = "נתונים: הלמ\"ס, רשות האוכלוסין (data.gov.il) · Our World in Data · Natural Earth · Wikidata"

    override fun countryIndicator(i: CountryIndicator) = when (i) {
        CountryIndicator.POPULATION -> "אוכלוסייה"
        CountryIndicator.DENSITY -> "צפיפות אוכלוסין"
        CountryIndicator.GDP -> "תוצר לנפש"
        CountryIndicator.LIFE -> "תוחלת חיים"
        CountryIndicator.MEDIAN_AGE -> "גיל חציוני"
    }

    override fun countryValue(i: CountryIndicator, v: Double): String = when (i) {
        CountryIndicator.POPULATION -> if (v >= 1e6) "${ltr(decimal(v / 1e6))} מיליון" else ltr(group(v))
        CountryIndicator.DENSITY -> "${ltr(group(v))} לקמ\"ר"
        CountryIndicator.GDP -> ltr("$" + group(v))
        CountryIndicator.LIFE, CountryIndicator.MEDIAN_AGE -> "${ltr(decimal(v))} שנים"
    }

    override fun localityIndicator(i: LocalityIndicator) = when (i) {
        LocalityIndicator.SIZE -> "גודל"
        LocalityIndicator.CHILDREN -> "ילדים (0–18)"
        LocalityIndicator.YOUNG_ADULTS -> "צעירים (19–45)"
        LocalityIndicator.ELDERLY -> "בני 65 ומעלה"
        LocalityIndicator.ARABS -> "תושבים ערבים"
        LocalityIndicator.TYPE -> "סוג יישוב"
        LocalityIndicator.RELIGION -> "דת"
        LocalityIndicator.FOUNDED -> "שנת ייסוד"
        LocalityIndicator.ALTITUDE -> "גובה"
        LocalityIndicator.CLUSTER -> "אשכול חברתי־כלכלי"
        LocalityIndicator.DISTRICT -> "מחוז"
    }

    override fun under(v: String) = "פחות מ־$v"
    override fun andOver(v: String) = "$v ומעלה"
    override fun range(a: String, b: String) = ltr("$a–$b")
    override fun people(range: String) = "${ltr(range)} תושבים"
    override fun before(year: Int) = "לפני $year"
    override fun since(year: Int) = "$year ואילך"
    override val belowSeaLevel = "מתחת לפני הים"

    override val localityTypes = listOf(
        "עיר או יישוב עירוני יהודי", "יישוב עירוני ערבי", "מושב", "קיבוץ", "יישוב קהילתי", "מושבה",
        "כפר ערבי או בדואי", "מוסד, אחר",
    )
    override val religions = listOf("יהודי", "ערבי", "בדואי", "מעורב")

    override fun district(he: String) = if (he.startsWith("אזור יהודה")) "יהודה ושומרון" else he

    override fun residents(n: Int) = "${ltr(group(n.toDouble()))} תושבים"
    override fun arabsShare(pct: Int) = "תושבים ערבים: ${ltr("$pct%")}"
    override fun foundedIn(year: Int) = "נוסד ב־$year"
    override fun altitudeOf(height: String) = "גובה: ${ltr(height)}"
    override fun clusterOf(c: Int) = "אשכול חברתי־כלכלי: $c מתוך 10"
    override val byAge = "תושבים לפי גיל"
    override fun asOf(year: Int) = "($year)"
    override val pinIt = "סימון במפה"

    override fun areaUnit(u: DistanceUnit) = when (u) {
        DistanceUnit.METRIC -> "קמ״ר"
        DistanceUnit.IMPERIAL -> "מייל רבוע"
        DistanceUnit.NAUTICAL -> "מייל ימי רבוע"
    }
    override fun area(v: String) = "שטח: $v"
    override fun capitals(names: String, n: Int) = (if (n > 1) "ערי בירה: " else "בירה: ") + names
    override fun languages(names: String, n: Int, more: Int) = (if (n + more > 1) "שפות: " else "שפה: ") + names + if (more > 0) " ועוד $more" else ""

    override val layers = "שכבות נתונים"
    override val findData = "חיפוש נתונים"
    override val nothingFound = "לא נמצא"
    override val world = "העולם"
    override val israel = "ישראל"
    override val time = "זמן"
    override val movement = "תנועה"
    override val timeZones = "אזורי זמן"
    override val gpsTraces = "מסלולי GPS"
    override val gpsTracesNote = "מסלולי GPS ציבוריים שהועלו ל־OpenStreetMap: ככל שהקווים צפופים יותר, יותר אנשים עברו שם."
    override val gettingTimeZones = "מוריד את אזורי הזמן…"
    override val countriesKeywords = "מדינות מדינה countries"
    override val localitiesKeywords = "יישובים יישוב ערים עיר כפרים localities"
    override val timeZonesKeywords = "שעון שעה שעות UTC GMT שעון קיץ חורף הפרש time zones"
    override val gpsKeywords = "מפת חום הקלטות מסלולים טיולים פעילות heat map gps traces"
    override val offsetsNow = "שעות מ־UTC, עכשיו"
    override val explorerSubtitle = "העולם דרך המספרים שלו: מה להציג"
    override val onTheMap = "על המפה"
    override val nothingOnTheMap = "עדיין אין כלום על המפה: אפשר לבחור שכבה למטה."
    override val clearAll = "ניקוי הכול"
    override val takeOff = "הסרה מהמפה"
    override val showTheMap = "הצגת המפה"
    override fun countrySource(i: CountryIndicator) = if (i == CountryIndicator.GDP) "הבנק העולמי, דרך Our World in Data" else "האו״ם, דרך Our World in Data"
    override fun localitySource(i: LocalityIndicator) = when {
        i.fromAges -> "רשות האוכלוסין · data.gov.il"
        i == LocalityIndicator.CLUSTER -> "הלמ״ס: אשכולות חברתיים־כלכליים (2019)"
        else -> "הלמ״ס · data.gov.il"
    }
    override val timeZonesSource = "מובנה, עובד גם בלי חיבור · timezone-boundary-builder"
    override val gpsTracesSource = "OpenStreetMap · איפה אנשים הלכו, רכבו ונסעו"
    override val zonesCredit = "אזורי זמן: timezone-boundary-builder (© תורמי OpenStreetMap)"

    override val mapBuilds = "המפות של MapAround"
    override val mapBuildsSource = "נבנות כל חודש · רשימת המפות של MapAround"
    override val mapBuildsKeywords = "מפות אופליין הורדה בנייה חודשית אזורים כיסוי offline maps download build"
    override val gettingMapList = "מוריד את רשימת המפות…"
    override val couldntGetMapList = "לא ניתן להוריד את רשימת המפות. יש חיבור?"
    override val buildsCredit = "אזורים: Geofabrik (© תורמי OpenStreetMap)"
    override fun buildState(s: app.maparound.stats.BuildState) = when (s) {
        app.maparound.stats.BuildState.CURRENT -> "בנייה אחרונה"
        app.maparound.stats.BuildState.OLD -> "בנייה קודמת"
        app.maparound.stats.BuildState.MISSING -> "אין מפה"
    }
    override fun buildsSummary(built: Int, total: Int, latest: String?) =
        "נבנו $built מתוך $total מפות" + (latest?.let { " · הבנייה האחרונה: $it" } ?: "")
    override val tapARegion = "הקשה על אזור מציגה את המפה שלו."
    override fun builtOn(day: String) = "עדכנית: נבנתה ב־$day"
    override fun olderBuild(day: String) = "מפה ישנה יותר, נבנתה ב־$day"
    override val needsFunding = "עדיין אין מפה: גדולה מדי למכונות הבנייה החינמיות, עד שיהיה מימון למכונה חזקה יותר"
    override val buildFailed = "אין מפה: הבנייה שלה נכשלה"
    override val notListed = "לא ברשימת המפות"
    override fun mapSize(size: String) = "הורדה: ${ltr(size)}"
    override fun asOneMap(region: String, how: String) = "$region, כמפה אחת: $how"
    override fun buildShort(s: app.maparound.stats.BuildState, why: app.maparound.data.CatalogEntry.Unavailable?) = when {
        s == app.maparound.stats.BuildState.CURRENT -> "עדכנית"
        s == app.maparound.stats.BuildState.OLD -> "מפה ישנה יותר"
        why == app.maparound.data.CatalogEntry.Unavailable.NEEDS_FUNDING -> "גדולה מדי למכונות הבנייה החינמיות"
        why == app.maparound.data.CatalogEntry.Unavailable.NOT_BUILT -> "הבנייה שלה נכשלה"
        else -> "לא ברשימה"
    }
    override val helpFund = "לעזור לממן מכונה חזקה יותר"
    override val supportUrl = "https://maparound.app/he/support"

    override val atSea = "בים"
    override val seaZone = "אזור זמן ימי"
    override fun localTime(time: String, day: String) = "השעה שם: ${ltr(time)} · $day"
    override fun utcNow(offset: String) = "${ltr("UTC$offset")} עכשיו"
    override fun ahead(by: String) = "$by יותר מאצלך"
    override fun behind(by: String) = "$by פחות מאצלך"
    override val sameTime = "אותה שעה כמו אצלך"
    override fun clocksForward(by: String, day: String, time: String) = "השעון יוזז $by קדימה ב־$day בשעה ${ltr(time)}"
    override fun clocksBack(by: String, day: String, time: String) = "השעון יוזז $by אחורה ב־$day בשעה ${ltr(time)}"
    override val noClockChanges = "אין שינויי שעון בשנה הקרובה"
    override fun hoursMinutes(h: Int, m: Int) = when {
        h == 1 && m == 0 -> "שעה"
        h == 2 && m == 0 -> "שעתיים"
        m == 0 -> "$h שעות"
        h == 0 -> "$m דקות"
        else -> "$h שעות ו־$m דקות"
    }
}
