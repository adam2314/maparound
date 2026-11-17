package app.maparound.ui

import app.maparound.data.MemoryStore
import app.maparound.data.Prefs
import app.maparound.testing.assertEquals
import kotlin.test.Test

class ScreenOnTest {
    @Test
    fun keptOnForAWhileAfterTheLastTouch() {
        val t = 1_800_000_000_000L
        assertEquals(0L, ScreenOn.SYSTEM.keepLeft(t, t))
        assertEquals(Long.MAX_VALUE, ScreenOn.ALWAYS.keepLeft(t, t + 3_600_000))
        assertEquals(5 * 60_000L, ScreenOn.M5.keepLeft(t, t))
        assertEquals(60_000L, ScreenOn.M5.keepLeft(t, t + 4 * 60_000))
        assertEquals(0L, ScreenOn.M5.keepLeft(t, t + 6 * 60_000))
    }

    @Test
    fun asBeforeUntilChosen() {
        val store = MemoryStore()
        val p = Prefs(store)
        // The phone's own setting with the app open, always on while navigating (as before).
        assertEquals(ScreenOn.SYSTEM, p.screenOnOpen.value)
        assertEquals(ScreenOn.ALWAYS, p.screenOnNav.value)
        p.setScreenOnOpen(ScreenOn.M10)
        p.setScreenOnNav(ScreenOn.M30)
        val again = Prefs(store)
        assertEquals(ScreenOn.M10, again.screenOnOpen.value)
        assertEquals(ScreenOn.M30, again.screenOnNav.value)
    }
}
