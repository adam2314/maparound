package app.maparound.ui

import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalityIndicator
import app.maparound.stats.StatsLayers
import app.maparound.stats.WeatherLayer
import app.maparound.stats.WeatherMapField
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import kotlin.test.Test

class MapAddressTest {
    @Test
    fun theDataViewGoesToAnAddressAndBack() {
        val all = listOf(
            DataLayers(country = CountryIndicator.POPULATION, locality = LocalityIndicator.SIZE),
            DataLayers(country = CountryIndicator.GDP, locality = LocalityIndicator.YOUNG_ADULTS),
            DataLayers(country = null, timeZones = true, locality = null),
            DataLayers(country = null, locality = null, mapBuilds = true),
            DataLayers(country = null, locality = null, rainRadar = true, weather = WeatherLayer.WIND, photos = true, buses = true, cameras = true, gpsTraces = true),
            DataLayers(country = null, locality = null, weatherMap = WeatherMapField.entries.first(), windAnimation = true),
            DataLayers(country = null, locality = null),
        )
        for (l in all) assertEquals(MapAddress.data(l), l, MapAddress.data(MapAddress.data(l)))
        assertEquals("c.population,l.size", MapAddress.data(DataLayers(country = CountryIndicator.POPULATION, locality = LocalityIndicator.SIZE)))
        // Nothing on to start with.
        assertEquals("none", MapAddress.data(DataLayers()))
    }

    @Test
    fun theSupportPagesLinkStillMeansTheMapsAlone() {
        assertEquals(DataLayers(country = null, locality = null, mapBuilds = true), MapAddress.data("maps"))
        // Unknown words are left out; nothing known at all is no data view.
        assertEquals(DataLayers(country = CountryIndicator.DENSITY, locality = null), MapAddress.data("c.density,x.y,c.nothing"))
        assertNull(MapAddress.data("nonsense"))
    }

    @Test
    fun overlaysAndTheTappedThing() {
        val on = setOf(MapAddress.Overlay.PARTY, MapAddress.Overlay.TRAILS, MapAddress.Overlay.SHADING)
        assertEquals("trails,party,shade", MapAddress.overlays(on))
        assertEquals(on, MapAddress.overlays("trails,party,shade"))
        assertEquals(emptySet<MapAddress.Overlay>(), MapAddress.overlays("-"))
        val key = StatsLayers.TAP_COUNTRY + "RUS"
        assertEquals("c:RUS", MapAddress.selected(key))
        assertEquals(key, MapAddress.selectedKey("c:RUS"))
    }
}
