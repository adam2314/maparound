package app.maparound.i18n

/** Texts many screens share: the tabs, common buttons, units, dates, directions, settings. */
open class CommonTexts {
    // ---- the tabs ----
    open val tabMap = "Map"
    open val tabLibrary = "Library"
    open val tabParty = "Party"
    open val tabOffline = "Offline"
    open val tabSensors = "Sensors"

    // ---- buttons and actions ----
    open val ok = "OK"
    open val cancel = "Cancel"
    open val close = "Close"
    open val done = "Done"
    open val save = "Save"
    open val delete = "Delete"
    open val edit = "Edit"
    open val rename = "Rename"
    open val add = "Add"
    open val remove = "Remove"
    open val share = "Share"
    open val copy = "Copy"
    open val open = "Open"
    open val more = "More"
    open val back = "Back"
    open val retry = "Try again"
    open val download = "Download"
    open val search = "Search"
    open val start = "Start"
    open val stop = "Stop"
    open val undo = "Undo"
    open val showOnMap = "Show on map"
    open val notNow = "Not now"
    open val settings = "Settings"
    open val copied = "Copied"

    // ---- units ----
    open val unitM = "m"
    open val unitKm = "km"
    open val unitFt = "ft"
    open val unitMi = "mi"
    open val unitKmh = "km/h"
    open val unitMph = "mph"
    open val unitKnots = "kn"
    open val unitMps = "m/s"
    open val unitS = "s"
    open val unitMin = "min"
    open val unitH = "h"

    // ---- time and dates ----
    open val justNow = "Just now"
    open fun minutesAgo(n: Long) = "$n min ago"
    open fun hoursAgo(n: Long) = if (n == 1L) "1 hour ago" else "$n hours ago"
    open val yesterday = "Yesterday"
    open fun daysAgo(n: Int) = "$n days ago"
    open val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Nov 3" */
    open fun monthDay(month: String, day: Int) = "$month $day"

    /** "Nov 3, 2024" */
    open fun monthDayYear(month: String, day: Int, year: Int) = "$month $day, $year"

    /** "3 Nov", as in names made from dates. */
    open fun dayMonth(day: Int, month: String) = "$day $month"

    // ---- directions ----
    private val compass16 = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** The compass point of a direction in degrees (0 = north, clockwise): one of 16. */
    open fun cardinal(deg: Float): String = compass16[((norm(deg) / 22.5f) + 0.5f).toInt() % 16]

    protected fun norm(deg: Float): Float = (deg % 360f + 360f) % 360f

    // ---- not in this version yet (the web app, the iPhone) ----
    open fun recordingLater(later: String) = "Recording tracks: $later"
    open fun styleImportLater(later: String) = "Importing map styles: $later"

    // ---- settings ----
    open val language = "Language"
    open val deviceLanguage = "Device language"

    /** Under "Device language": what it is now. */
    open fun deviceLanguageNow(name: String) = "Now $name"
}

/** Hebrew. */
object CommonTextsHe : CommonTexts() {
    override val tabMap = "מפה"
    override val tabLibrary = "ספרייה"
    override val tabParty = "קבוצה"
    override val tabOffline = "אופליין"
    override val tabSensors = "חיישנים"

    override val ok = "אישור"
    override val cancel = "ביטול"
    override val close = "סגירה"
    override val done = "סיום"
    override val save = "שמירה"
    override val delete = "מחיקה"
    override val edit = "עריכה"
    override val rename = "שינוי שם"
    override val add = "הוספה"
    override val remove = "הסרה"
    override val share = "שיתוף"
    override val copy = "העתקה"
    override val open = "פתיחה"
    override val more = "עוד"
    override val back = "חזרה"
    override val retry = "לנסות שוב"
    override val download = "הורדה"
    override val search = "חיפוש"
    override val start = "התחלה"
    override val stop = "עצירה"
    override val undo = "ביטול הפעולה"
    override val showOnMap = "הצגה במפה"
    override val notNow = "לא עכשיו"
    override val settings = "הגדרות"
    override val copied = "הועתק"

    override val unitM = "מ׳"
    override val unitKm = "ק״מ"
    override val unitFt = "רגל"
    override val unitMi = "מייל"
    override val unitKmh = "קמ״ש"
    override val unitMph = "מייל/ש׳"
    override val unitKnots = "קשר"
    override val unitMps = "מ׳/שנ׳"
    override val unitS = "שנ׳"
    override val unitMin = "דק׳"
    override val unitH = "ש׳"

    override val justNow = "עכשיו"
    override fun minutesAgo(n: Long) = "לפני $n דק׳"
    override fun hoursAgo(n: Long) = when (n) {
        1L -> "לפני שעה"
        2L -> "לפני שעתיים"
        else -> "לפני $n שעות"
    }
    override val yesterday = "אתמול"
    override fun daysAgo(n: Int) = if (n == 2) "לפני יומיים" else "לפני $n ימים"
    override val months = listOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")
    override fun monthDay(month: String, day: Int) = "$day ב$month"
    override fun monthDayYear(month: String, day: Int, year: Int) = "$day ב$month $year"
    override fun dayMonth(day: Int, month: String) = "$day ב$month"

    private val compass8 = listOf("צפון", "צפון־מזרח", "מזרח", "דרום־מזרח", "דרום", "דרום־מערב", "מערב", "צפון־מערב")

    /** Hebrew names the eight main directions. */
    override fun cardinal(deg: Float): String = compass8[((norm(deg) / 45f) + 0.5f).toInt() % 8]

    override fun recordingLater(later: String) = "הקלטת מסלולים: $later"
    override fun styleImportLater(later: String) = "ייבוא סגנונות מפה: $later"

    override val language = "שפה"
    override val deviceLanguage = "שפת המכשיר"
    override fun deviceLanguageNow(name: String) = "כרגע $name"
}
