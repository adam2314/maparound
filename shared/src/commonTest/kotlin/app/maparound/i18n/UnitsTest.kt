package app.maparound.i18n

import app.maparound.data.MemoryStore
import app.maparound.data.Prefs
import app.maparound.routing.Guidance
import app.maparound.sensors.SpeedUnit
import app.maparound.shared.util.localDecimalSeparator
import app.maparound.testing.assertEquals
import app.maparound.ui.Fmt
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test

class UnitsTest {
    private val d = localDecimalSeparator()

    @AfterTest
    fun backToMetric() = Units.set(UnitSystem())

    @Test
    fun distancesAndHeights() {
        Units.set(UnitSystem())
        assertEquals("950 m", Fmt.distance(950f))
        assertEquals("812 m", Fmt.height(812.4))
        // US: miles, feet close by.
        Units.set(UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.FT, SpeedUnit.MPH))
        assertEquals("1${d}0 mi", Fmt.distance(1609.344f))
        assertEquals("0${d}2 mi", Fmt.distance(300f))
        assertEquals("328 ft", Fmt.distance(100f))
        assertEquals("2664 ft", Fmt.height(812))
        assertEquals("12 mph", Fmt.speed(5.4))
        // UK: miles, but metres close by.
        Units.set(UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.M, SpeedUnit.MPH))
        assertEquals("100 m", Fmt.distance(100f))
        assertEquals("1${d}2 mi", Fmt.distance(2000f))
        Units.set(UnitSystem(DistanceUnit.NAUTICAL, speed = SpeedUnit.KNOTS))
        assertEquals("1${d}0 NM", Fmt.distance(1852f))
        assertEquals("150 m", Fmt.distance(150f))
    }

    @Test
    fun directions() {
        val as_ = { f: DirectionFormat -> Units.set(UnitSystem(direction = f)); Fmt.heading(23f) }
        assertEquals("NNE", as_(DirectionFormat.COMPASS))
        assertEquals("23°", as_(DirectionFormat.DEGREES))
        assertEquals("NNE 23°", as_(DirectionFormat.BOTH))
        assertEquals("409 mil", as_(DirectionFormat.MILS))
        Units.set(UnitSystem(direction = DirectionFormat.DEGREES))
        assertEquals("0°", Fmt.heading(359.7f))
    }

    @Test
    fun timesAndDates() {
        Units.set(UnitSystem())
        assertEquals("14:05", Fmt.clock(14, 5, null))
        assertEquals("09:05:07", Fmt.clock(9, 5, 7))
        Units.set(UnitSystem(time = TimeFormat.H12))
        assertEquals("2:05 PM", Fmt.clock(14, 5, null))
        assertEquals("12:05 AM", Fmt.clock(0, 5, null))
        assertEquals("12:00 PM", Fmt.clock(12, 0, null))
        val day = LocalDate(2026, 11, 17)
        Units.set(UnitSystem(date = DateOrder.DAY_MONTH))
        assertEquals("17 Nov 2026", Fmt.day(day, withYear = true))
        assertEquals("17 Nov", Fmt.day(day, withYear = false))
        Units.set(UnitSystem(date = DateOrder.MONTH_DAY))
        assertEquals("Nov 17, 2026", Fmt.day(day, withYear = true))
        Units.set(UnitSystem(date = DateOrder.ISO))
        assertEquals("2026-11-17", Fmt.day(day, withYear = false))
    }

    @Test
    fun theDevicesUnitsGoByWhereItIs() {
        val us = UnitSystem.forRegion("US", uses24h = false, zone = "America/Denver")
        assertEquals(UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.FT, SpeedUnit.MPH, time = TimeFormat.H12, date = DateOrder.MONTH_DAY), us)
        // An "English (US)" phone in Israel: metric.
        assertEquals(UnitSystem(), UnitSystem.forRegion("US", uses24h = true, zone = "Asia/Jerusalem"))
        assertEquals(UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.M, SpeedUnit.MPH), UnitSystem.forRegion("GB", true, "Europe/London"))
        assertEquals(UnitSystem(), UnitSystem.forRegion("IL", true, "Asia/Jerusalem"))
        // No time zone known: the region alone.
        assertEquals(DistanceUnit.IMPERIAL, UnitSystem.forRegion("US", true, "").distance)
    }

    @Test
    fun choicesOverTheDevices() {
        val device = UnitSystem.forRegion("US", false, "America/Chicago")
        val metricHere = UnitChoice.metric(UnitChoice(time = TimeFormat.H24))
        assertEquals(UnitSystem(time = TimeFormat.H24, date = DateOrder.MONTH_DAY), metricHere.resolve(device))
        assertEquals(device, UnitChoice().resolve(device))
        // Kept, and given to the app.
        val store = MemoryStore()
        var applied: UnitSystem? = null
        Prefs(store).setUnitChoice(UnitChoice(height = HeightUnit.FT, direction = DirectionFormat.MILS))
        val again = Prefs(store, onUnits = { applied = it })
        assertEquals(UnitChoice(height = HeightUnit.FT, direction = DirectionFormat.MILS), again.unitChoice.value)
        assertEquals(HeightUnit.FT, applied!!.height)
        assertEquals(DirectionFormat.MILS, applied!!.direction)
    }

    @Test
    fun navigationSaysDistancesInTheUnitsChosen() {
        Units.set(UnitSystem())
        assertEquals("50 metres", Guidance.spokenDistance(47.0))
        assertEquals("1 kilometre", Guidance.spokenDistance(1020.0))
        Units.set(UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.FT, SpeedUnit.MPH))
        assertEquals("350 feet", Guidance.spokenDistance(100.0))
        assertEquals("0.4 miles", Guidance.spokenDistance(650.0))
        assertEquals("1 mile", Guidance.spokenDistance(1600.0))
        assertEquals(0.4 * Fmt.MILE_M, Guidance.rounded(650.0))
    }
}
