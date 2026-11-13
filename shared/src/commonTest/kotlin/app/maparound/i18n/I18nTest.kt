package app.maparound.i18n

import app.maparound.ui.Fmt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class I18nTest {
    @AfterTest
    fun backToEnglish() = I18n.apply("en")

    @Test
    fun languagesByCode() {
        assertEquals(Language.HE, Language.of("he"))
        assertEquals(Language.HE, Language.of("he-IL"))
        // Java's old code for Hebrew (Android's Locale.language).
        assertEquals(Language.HE, Language.of("iw"))
        assertEquals(Language.EN, Language.of("en_GB"))
        assertNull(Language.of("de"))
        assertNull(Language.of(null))
    }

    @Test
    fun theChosenLanguage() {
        I18n.apply("he")
        assertEquals(Language.HE, I18n.language)
        assertTrue(I18n.rtl)
        assertEquals("he", I18n.contentLanguage)
        assertEquals("מפה", T.common.tabMap)
        // Left to right inside right to left: an isolate, for display.
        assertEquals("⁦31.77670, 35.23450⁩", ltr("31.77670, 35.23450"))

        I18n.apply("en")
        assertFalse(I18n.rtl)
        assertEquals("Map", T.common.tabMap)
        assertEquals("1, 2", ltr("1, 2"))
        // A language the app doesn't speak: English.
        I18n.apply("de")
        assertEquals(Language.EN, I18n.language)
        assertEquals("de", I18n.contentLanguage)
    }

    @Test
    fun unitsDatesAndDirectionsInHebrew() {
        I18n.apply("he")
        assertEquals("850 מ׳", Fmt.distance(850f))
        assertEquals("12.5 ק״מ", Fmt.distance(12_500f))
        assertEquals("4 ש׳ 10 דק׳", Fmt.duration(15_000))
        assertEquals("צפון־מזרח", Fmt.cardinal(30f))
        // NNE and NNW round to their nearest of the eight alike.
        assertEquals("צפון", Fmt.cardinal(20f))
        assertEquals("צפון", Fmt.cardinal(340f))
        assertEquals("לפני שעתיים", T.common.hoursAgo(2))
        assertEquals("לפני יומיים", T.common.daysAgo(2))
        assertEquals("3 בנוב׳", T.common.monthDay(T.common.months[10], 3))

        I18n.apply("en")
        assertEquals("12.5 km", Fmt.distance(12_500f))
        assertEquals("NNE", Fmt.cardinal(20f))
        assertEquals("4 h 10 min", Fmt.duration(15_000))
    }
}
