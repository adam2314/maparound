package app.maparound.i18n

import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalityIndicator
import kotlin.math.abs
import kotlin.math.roundToLong

/** The data view (the web app): countries and Israel's localities coloured by their figures. */
open class StatsTexts {
    open val dataView = "Data view"
    open val dataViewSubtitle = "Countries and Israel's localities by their figures"
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
    override val dataViewSubtitle = "מדינות ויישובי ישראל לפי הנתונים שלהם"
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
}
