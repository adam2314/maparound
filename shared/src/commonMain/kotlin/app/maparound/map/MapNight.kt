package app.maparound.map

/**
 * When the map is drawn dark, light text on dark ground ([MapSurface.night]): with the device's
 * dark mode (the default), or always light, or always dark. Settings → Screen.
 *
 * The car's map follows the car's day and night (Android Auto requires a dark map at night), and
 * [DARK] keeps it dark by day too.
 */
enum class MapNight {
    AUTO, LIGHT, DARK;

    /** Dark or not, with the device in dark mode or not. */
    fun dark(deviceDark: Boolean): Boolean = when (this) {
        AUTO -> deviceDark
        LIGHT -> false
        DARK -> true
    }
}
