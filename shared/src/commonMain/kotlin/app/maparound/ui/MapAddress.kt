package app.maparound.ui

import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalityIndicator
import app.maparound.stats.WeatherLayer
import app.maparound.stats.WeatherMapField

// Plain Kotlin: what's on the map, as short words for a web address (the web app keeps it there,
// so a link or a reload shows the same). Unit-tested.

/** The map's layers and the data view, in an address's parameters. */
object MapAddress {
    /** The overlays and terrain that go on and off, by their word in `ov=`. */
    enum class Overlay(val word: String) {
        TRAILS("trails"), PLACES("places"), GRID("grid"), PARTY("party"), CONTOURS("contours"), SHADING("shade");

        companion object {
            fun of(word: String): Overlay? = entries.firstOrNull { it.word == word }
        }
    }

    /** `ov=`: the overlays on, in a fixed order ("" when none is). */
    fun overlays(on: Set<Overlay>): String = Overlay.entries.filter { it in on }.joinToString(",") { it.word }

    /** The overlays an `ov=` names (unknown words are left out). */
    fun overlays(value: String): Set<Overlay> = value.split(',').mapNotNull { Overlay.of(it.trim()) }.toSet()

    /**
     * `data=`: the data view's layers, each by a word (`c.population`, `l.size`, `zones`, `maps`,
     * `radar`, `w.temperature`…); `none` when nothing is on.
     */
    fun data(l: DataLayers): String = buildList {
        l.country?.let { add("c." + it.name.lowercase()) }
        if (l.timeZones) add("zones")
        l.locality?.let { add("l." + it.name.lowercase()) }
        if (l.mapBuilds) add("maps")
        if (l.gpsTraces) add("gps")
        if (l.rainRadar) add("radar")
        l.weather?.let { add("w." + it.name.lowercase()) }
        l.weatherMap?.let { add("wm." + it.name.lowercase()) }
        if (l.windAnimation) add("wind")
        if (l.photos) add("photos")
        if (l.buses) add("buses")
        if (l.cameras) add("cameras")
    }.joinToString(",").ifEmpty { "none" }

    /**
     * The layers a `data=` names: those listed on, the rest off (so `data=maps` is how MapAround's
     * maps stand, alone). Null when it names none it knows (and isn't `none`).
     */
    fun data(value: String): DataLayers? {
        var l = DataLayers(country = null, locality = null)
        var known = false
        for (word in value.split(',').map { it.trim().lowercase() }) {
            val (head, tail) = word.substringBefore('.') to word.substringAfter('.', "")
            fun <E : Enum<E>> pick(all: Array<E>): E? = all.firstOrNull { it.name.lowercase() == tail }
            val next = when (head) {
                "none" -> l
                "c" -> pick(CountryIndicator.entries.toTypedArray())?.let { l.copy(country = it) }
                "l" -> pick(LocalityIndicator.entries.toTypedArray())?.let { l.copy(locality = it) }
                "w" -> pick(WeatherLayer.entries.toTypedArray())?.let { l.copy(weather = it) }
                "wm" -> pick(WeatherMapField.entries.toTypedArray())?.let { l.copy(weatherMap = it) }
                "zones" -> l.copy(timeZones = true)
                "maps" -> l.copy(mapBuilds = true)
                "gps" -> l.copy(gpsTraces = true)
                "radar" -> l.copy(rainRadar = true)
                "wind" -> l.copy(windAnimation = true)
                "photos" -> l.copy(photos = true)
                "buses" -> l.copy(buses = true)
                "cameras" -> l.copy(cameras = true)
                else -> null
            } ?: continue
            l = next
            known = true
        }
        return l.takeIf { known }
    }

    /** `sel=`: what's tapped in the data view (a country, a locality, a region…), its key without "data:". */
    fun selected(key: String): String = key.removePrefix(DATA_PREFIX)

    /** The tap key a `sel=` stands for. */
    fun selectedKey(value: String): String = DATA_PREFIX + value

    private const val DATA_PREFIX = "data:"
}
