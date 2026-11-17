package app.maparound.i18n

import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalityIndicator
import kotlin.math.abs
import kotlin.math.roundToLong

/** The data view (the web app): countries and Israel's localities coloured by their figures. */
open class StatsTexts {
    open val dataView = "Data view"
    open val dataViewSubtitle = "Countries and Israel's localities by their figures, time zones, GPS traces"
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
    open val credits = "data: CBS, Population Authority (data.gov.il) · Our World in Data · Natural Earth"

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
    open fun altitudeOf(m: Int) = "Altitude: ${ltr("$m m")}"
    open fun clusterOf(c: Int) = "Socio-economic cluster: $c of 10"
    open val byAge = "Residents by age"
    open val ageGroups = listOf("0–5", "6–18", "19–45", "46–55", "56–64", "65+")
    open fun asOf(year: Int) = "($year)"
    open val pinIt = "Pin it"

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
    open val zonesCredit = "time zones: timezone-boundary-builder (© OpenStreetMap contributors)"

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
    override val dataViewSubtitle = "מדינות ויישובי ישראל לפי הנתונים שלהם, אזורי זמן, מסלולי GPS"
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
    override val credits = "נתונים: הלמ\"ס, רשות האוכלוסין (data.gov.il) · Our World in Data · Natural Earth"

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
    override fun altitudeOf(m: Int) = "גובה: ${ltr("$m מ'")}"
    override fun clusterOf(c: Int) = "אשכול חברתי־כלכלי: $c מתוך 10"
    override val byAge = "תושבים לפי גיל"
    override fun asOf(year: Int) = "($year)"
    override val pinIt = "סימון במפה"

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
    override val zonesCredit = "אזורי זמן: timezone-boundary-builder (© תורמי OpenStreetMap)"

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
