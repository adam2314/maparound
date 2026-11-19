package app.maparound.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.maparound.sensors.SpeedUnit
import app.maparound.shared.util.deviceRegion
import app.maparound.shared.util.deviceUses24h
import kotlinx.datetime.TimeZone

// Plain Kotlin (with snapshot state): the units and formats values are shown in. Unit-tested.

/** Distances: kilometres and metres, miles (and feet or metres close by), nautical miles. */
enum class DistanceUnit { METRIC, IMPERIAL, NAUTICAL }

/** Heights, and lengths close by (accuracy, a few hundred metres): metres or feet. */
enum class HeightUnit { M, FT }

/** Directions: compass points ("NNE"), degrees ("23°"), both ("NNE 23°"), or mils (6400 to a circle). */
enum class DirectionFormat { COMPASS, DEGREES, BOTH, MILS }

/** The time of day: "14:05" or "2:05 PM". */
enum class TimeFormat { H24, H12 }

/** Dates: "17 Nov 2026", "Nov 17, 2026" or "2026-11-17". */
enum class DateOrder { DAY_MONTH, MONTH_DAY, ISO }

/** Units and formats, all decided. */
data class UnitSystem(
    val distance: DistanceUnit = DistanceUnit.METRIC,
    val height: HeightUnit = HeightUnit.M,
    val speed: SpeedUnit = SpeedUnit.KMH,
    val direction: DirectionFormat = DirectionFormat.COMPASS,
    val time: TimeFormat = TimeFormat.H24,
    val date: DateOrder = DateOrder.DAY_MONTH,
    /** How coordinates are written (its own setting: Settings → Units & formats, or the converter). */
    val coords: app.maparound.tools.CoordFormat = app.maparound.tools.CoordFormat.DD,
) {
    companion object {
        /** Where miles, feet and mph are the everyday units. */
        private val IMPERIAL_REGIONS = setOf("US", "LR", "MM")

        /** The time zones each of those regions (and the UK) is in, as their names start. */
        private val ZONES = mapOf(
            "US" to listOf("America/", "Pacific/Honolulu", "US/"),
            "LR" to listOf("Africa/Monrovia"),
            "MM" to listOf("Asia/Yangon", "Asia/Rangoon"),
            "GB" to listOf("Europe/London", "Europe/Belfast", "GB"),
        )

        /**
         * What a device in [region] (its country code, "US") uses, with its clock in 24 hours or not:
         * imperial in the US (and Liberia, Myanmar), miles and mph with heights in metres in the UK,
         * metric elsewhere; month first in the US. The region counts only when the device's time
         * [zone] ("Asia/Jerusalem") is there too: many phones elsewhere are set to "English (US)".
         */
        fun forRegion(region: String, uses24h: Boolean, zone: String = ""): UnitSystem {
            val claimed = region.uppercase()
            val inZone = zone.isEmpty() || zone == "UTC" || zone.startsWith("Etc/") ||
                ZONES[claimed]?.any { zone.startsWith(it) } != false
            val r = if (inZone) claimed else ""
            val time = if (uses24h) TimeFormat.H24 else TimeFormat.H12
            return when {
                r in IMPERIAL_REGIONS -> UnitSystem(
                    DistanceUnit.IMPERIAL, HeightUnit.FT, SpeedUnit.MPH, time = time,
                    date = if (r == "US") DateOrder.MONTH_DAY else DateOrder.DAY_MONTH,
                )
                r == "GB" -> UnitSystem(DistanceUnit.IMPERIAL, HeightUnit.M, SpeedUnit.MPH, time = time)
                else -> UnitSystem(time = time)
            }
        }
    }
}

/**
 * The user's choices of units and formats; null where they haven't chosen (the device's then:
 * [UnitSystem.forRegion]).
 */
data class UnitChoice(
    val distance: DistanceUnit? = null,
    val height: HeightUnit? = null,
    val speed: SpeedUnit? = null,
    val direction: DirectionFormat? = null,
    val time: TimeFormat? = null,
    val date: DateOrder? = null,
) {
    /** With the device's units and formats where nothing was chosen. */
    fun resolve(device: UnitSystem): UnitSystem = UnitSystem(
        distance ?: device.distance,
        height ?: device.height,
        speed ?: device.speed,
        direction ?: device.direction,
        time ?: device.time,
        date ?: device.date,
    )

    /** Nothing chosen: all as the device has them. */
    val automatic: Boolean get() = this == UnitChoice()

    companion object {
        /** The quick choices: distance, height and speed together (the rest as they were). */
        fun metric(c: UnitChoice) = c.copy(distance = DistanceUnit.METRIC, height = HeightUnit.M, speed = SpeedUnit.KMH)
        fun imperial(c: UnitChoice) = c.copy(distance = DistanceUnit.IMPERIAL, height = HeightUnit.FT, speed = SpeedUnit.MPH)
        fun nautical(c: UnitChoice) = c.copy(distance = DistanceUnit.NAUTICAL, height = HeightUnit.M, speed = SpeedUnit.KNOTS)
    }
}

/**
 * The units and formats now: the user's choices (Settings), the device's for the rest; metric,
 * 24 hours and day first until the app's settings say ([set]). [current] is snapshot state, so
 * screens showing values ([app.maparound.ui.Fmt]) are drawn again when it changes.
 */
object Units {
    var current: UnitSystem by mutableStateOf(UnitSystem())
        private set

    /** What the device uses: its region's units (where it is, by its time zone), its clock. */
    val device: UnitSystem
        get() = UnitSystem.forRegion(
            runCatching { deviceRegion() }.getOrDefault(""),
            runCatching { deviceUses24h() }.getOrDefault(true),
            runCatching { TimeZone.currentSystemDefault().id }.getOrDefault(""),
        )

    /** Shows values in [system] from now on (the apps' settings call it: [app.maparound.data.Prefs]). */
    fun set(system: UnitSystem) {
        current = system
    }
}
