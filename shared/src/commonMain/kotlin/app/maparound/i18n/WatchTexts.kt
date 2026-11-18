package app.maparound.i18n

/** MapAround's Wear OS app (the rest of its words are the phone app's: navigation, recording). */
open class WatchTexts {
    open val openOnPhone = "Open MapAround on your phone"
    open val openOnPhoneNote = "Navigation and recording on the phone show here, and can be stopped from here."
    open val phoneNotReachable = "Can't reach the phone"
    open val recording = "Recording"
}

object WatchTextsHe : WatchTexts() {
    override val openOnPhone = "פתחו את MapAround בטלפון"
    override val openOnPhoneNote = "ניווט והקלטה בטלפון מוצגים כאן, ואפשר לעצור אותם מכאן."
    override val phoneNotReachable = "אין קשר עם הטלפון"
    override val recording = "מקליט"
}
