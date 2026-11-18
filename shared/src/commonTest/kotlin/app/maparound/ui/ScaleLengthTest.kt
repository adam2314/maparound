package app.maparound.ui

import app.maparound.i18n.DistanceUnit
import app.maparound.i18n.HeightUnit
import app.maparound.i18n.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scale line: one round length, in the distance unit chosen only. */
class ScaleLengthTest {
    private val metric = UnitSystem()
    private val us = UnitSystem(distance = DistanceUnit.IMPERIAL, height = HeightUnit.FT)
    private val uk = UnitSystem(distance = DistanceUnit.IMPERIAL, height = HeightUnit.M)
    private val nautical = UnitSystem(distance = DistanceUnit.NAUTICAL)

    @Test fun metric() {
        assertEquals(2000.0 to "2 km", scaleLength(3500.0, metric))
        assertEquals(50.0 to "50 m", scaleLength(99.0, metric))
    }

    @Test fun milesAndFeet() {
        // Over a fifth of a mile: miles.
        val (m, label) = scaleLength(3500.0, us)
        assertEquals("2 mi", label)
        assertEquals(2 * Fmt.MILE_M, m, 1e-6)
        assertEquals("0.2 mi", scaleLength(400.0, us).second)
        // Closer in: feet, or metres where heights are in metres.
        assertEquals("500 ft", scaleLength(200.0, us).second)
        assertEquals("200 m", scaleLength(300.0, uk).second)
    }

    @Test fun nauticalMiles() {
        assertEquals("1 NM", scaleLength(2000.0, nautical).second)
        assertEquals("0.1 NM", scaleLength(200.0, nautical).second)
        assertEquals("100 m", scaleLength(150.0, nautical).second)
    }
}
