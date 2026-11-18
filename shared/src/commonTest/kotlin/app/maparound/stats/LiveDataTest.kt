package app.maparound.stats

import app.maparound.i18n.HeightUnit
import app.maparound.i18n.UnitSystem
import app.maparound.i18n.Units
import app.maparound.tools.MapGrid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data view's live layers: the weather, rain radar, street photos and buses, from their services' answers. */
class LiveDataTest {
    @AfterTest fun metric() = Units.set(UnitSystem())

    @Test fun weatherGridIsALatticeOverTheView() {
        val israel = MapGrid.View(29.5, 34.2, 33.3, 35.9)
        val grid = Weather.grid(israel)
        assertTrue(grid.size in 20..Weather.MAX_POINTS, "${grid.size} points")
        assertTrue(grid.all { (lat, lon) -> lat in 29.5..33.3 && lon in 34.2..35.9 }, grid.toString())
        // A little further east: the points still in view are the same ones (asked once).
        val moved = Weather.grid(MapGrid.View(29.5, 34.3, 33.3, 36.0))
        assertTrue(grid.intersect(moved.toSet()).size >= grid.size / 2)
        // The whole world, across the date line, and a view from the far side.
        assertTrue(Weather.grid(MapGrid.View(-85.0, -180.0, 85.0, 180.0)).size in 20..Weather.MAX_POINTS)
        val pacific = Weather.grid(MapGrid.View(-10.0, 170.0, 10.0, -170.0))
        assertTrue(pacific.isNotEmpty() && pacific.all { it.second in -180.0..180.0 }, pacific.toString())
        assertEquals(emptyList(), Weather.grid(MapGrid.View(10.0, 0.0, 10.0, 1.0)))
    }

    @Test fun weatherNowFromOneOrSeveralPlaces() {
        val two = """[{"latitude":31.76,"longitude":35.23,"elevation":788.0,"current":{"time":"2026-11-18T05:15","temperature_2m":11.6,"weather_code":1,"wind_speed_10m":5.2,"wind_direction_10m":290,"wind_gusts_10m":14.0,"precipitation":0.0,"cloud_cover":20}},
            {"latitude":32.1,"longitude":34.83,"current":{"temperature_2m":17.2,"weather_code":61,"wind_speed_10m":22.0,"wind_direction_10m":250}}]"""
        val asked = listOf(31.75 to 35.25, 32.1 to 34.8)
        val w = Weather.parseNow(two, asked)
        assertEquals(2, w.size)
        // The places asked, not the forecast grid's.
        assertEquals(31.75, w[0].lat)
        assertEquals(11.6, w[0].tempC)
        assertEquals(20, w[0].cloudPct)
        assertEquals(5, Weather.kindOf(w[1].code!!))
        val one = Weather.parseNow("""{"current":{"temperature_2m":-3.0}}""", listOf(45.0 to 7.0))
        assertEquals(-3.0, one.single().tempC)
        assertTrue("latitude=31.75,32.1" in Weather.nowUrl(Weather.OPEN_METEO, asked))
    }

    @Test fun weatherClassesLabelsAndUnits() {
        val points = listOf(WeatherNow(31.75, 35.25, tempC = 11.6, windKmh = 25.0, windFrom = 315.0), WeatherNow(32.0, 35.0, tempC = 31.0))
        val json = Weather.geoJson(points, WeatherLayer.TEMPERATURE, emptySet())
        assertTrue(""""tap":"data:w:31.75,35.25"""" in json, json)
        assertTrue(""""label":"12°"""" in json, json)
        assertTrue(""""label":"31°"""" in json, json)
        // The warm one hidden.
        val warm = Weather.classOf(WeatherLayer.TEMPERATURE, points[1])!!
        assertFalse("31°" in Weather.geoJson(points, WeatherLayer.TEMPERATURE, setOf(warm)))
        // Wind: how strong, and its arrow the way it blows (from the north-west: to the south-east);
        // the one without wind isn't there.
        val wind = Weather.geoJson(points, WeatherLayer.WIND, emptySet())
        assertTrue(""""label":"25","rot":135""" in wind, wind)
        assertEquals(1, Regex("\"tap\"").findAll(wind).count())
        assertEquals(31.75 to 35.25, Weather.placeOf("data:w:31.75,35.25"))
        // Where heights are in feet, temperatures are in °F.
        Units.set(UnitSystem(height = HeightUnit.FT))
        assertEquals("53°", Weather.degrees(11.6))
        assertEquals("<14°", Weather.classes(WeatherLayer.TEMPERATURE).first().label.filter { it.code < 0x2000 })
    }

    @Test fun forecastByTheClocksThere() {
        val json = """{"latitude":31.76,"longitude":35.23,"elevation":788.0,"utc_offset_seconds":7200,
            "current":{"temperature_2m":11.6,"apparent_temperature":9.8,"relative_humidity_2m":71,"weather_code":2,"wind_speed_10m":5.2},
            "hourly":{"time":[1794978000,1794981600],"temperature_2m":[11.2,12.9],"precipitation":[0.0,0.4],"precipitation_probability":[5,40],"weather_code":[1,61]},
            "daily":{"time":[1794952800],"weather_code":[61],"temperature_2m_max":[19.4],"temperature_2m_min":[9.1],"precipitation_sum":[2.3],"precipitation_probability_max":[60],"wind_speed_10m_max":[18.0],"sunrise":[1794979200],"sunset":[1795017600]}}"""
        val f = Weather.parseForecast(json, 31.75, 35.25)
        assertEquals(7200, f.utcOffsetSeconds)
        assertEquals(788.0, f.elevationM)
        assertEquals(9.8, f.now?.feelsC)
        assertEquals(71, f.now?.humidityPct)
        assertEquals(2, f.hours.size)
        assertEquals(1794981600_000L, f.hours[1].timeMillis)
        assertEquals(40, f.hours[1].chancePct)
        val d = f.days.single()
        // Local midnight (as if it were UTC): 18 Nov 2026.
        assertEquals((1794952800L + 7200) * 1000, d.dayMillis)
        assertEquals(9.1, d.minC)
        assertEquals(1794979200_000L, d.sunriseMillis)
    }

    @Test fun rainRadarPictures() {
        val json = """{"version":"2.0","generated":1794979528,"host":"https://tilecache.rainviewer.com","radar":{"past":[
            {"time":1794979200,"path":"/v2/radar/55bd9c258333"},{"time":1794972000,"path":"/v2/radar/7c73b7f5c22b"}],"nowcast":[]},"satellite":{"infrared":[]}}"""
        val frames = RainRadar.parse(json)
        assertEquals(listOf(1794972000_000L, 1794979200_000L), frames.map { it.timeMillis })
        assertEquals("https://tilecache.rainviewer.com/v2/radar/55bd9c258333/256/{z}/{x}/{y}/2/1_1.png", frames.last().tiles)
        val src = RainRadar.source(frames.last())
        assertEquals(RainRadar.MAX_ZOOM, src.maxZoom)
        assertTrue(src.id.startsWith("rain_radar"))
        assertEquals(emptyList(), RainRadar.parse("""{"radar":{}}"""))
    }

    @Test fun streetPhoto() {
        val json = """{"features":[{"id":"b16ca93b-f463-4b9f-bd23-652bd7567d5f","type":"Feature",
            "links":[{"rel":"license","href":"https://creativecommons.org/licenses/by-sa/4.0/"},{"rel":"via","href":"https://panoramax.openstreetmap.fr","title":"Link to the original instance"}],
            "assets":{"hd":{"href":"https://x.fr/images/b1.jpg"},"sd":{"href":"https://x.fr/derivates/b1/sd.jpg"},"thumb":{"href":"https://x.fr/derivates/b1/thumb.jpg"}},
            "geometry":{"type":"Point","coordinates":[35.215057264,31.777695228]},
            "providers":[{"name":"urimsch","roles":["producer"]}],
            "properties":{"license":"CC-BY-SA-4.0","datetime":"2025-12-14T11:50:59.081800+00:00","view:azimuth":250,"pers:interior_orientation":{"field_of_view":70}}}]}"""
        val p = assertNotNull(StreetPhotos.parse(json))
        assertEquals(31.777695228, p.lat)
        assertEquals("urimsch", p.author)
        assertEquals("CC-BY-SA-4.0", p.license)
        assertEquals("https://x.fr/derivates/b1/sd.jpg", p.picture)
        assertEquals("panoramax.openstreetmap.fr", p.instance)
        assertFalse(p.panorama)
        assertEquals(250.0, p.heading)
        assertEquals(1765713059081L, p.timeMillis)
        assertTrue(StreetPhotos.parse(json.replace("\"field_of_view\":70", "\"field_of_view\":360"))!!.panorama)
        assertNull(StreetPhotos.parse("""{"features":[]}"""))
        assertEquals(
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=31.777695,35.215057&heading=250",
            StreetPhotos.streetViewUrl(p.lat, p.lon, p.heading),
        )
        assertEquals("https://api.panoramax.xyz/api/map/{z}/{x}/{y}.mvt", StreetPhotos.tiles(StreetPhotos.PANORAMAX))
    }

    @Test fun camerasFromOpenStreetMap() {
        val json = """{"version":0.6,"elements":[
            {"type":"node","id":101,"lat":31.9,"lon":34.8,"tags":{"highway":"speed_camera","maxspeed":"90","direction":"135"}},
            {"type":"node","id":102,"lat":31.8,"lon":34.7,"tags":{"man_made":"surveillance","surveillance":"public","surveillance:type":"ALPR","camera:direction":"SE","operator":"Police"}},
            {"type":"node","id":103,"lat":31.7,"lon":34.6,"tags":{"man_made":"surveillance","contact:webcam":"https://wind.co.il/cam","name":"Free Gull Webcam"}},
            {"type":"way","id":104,"center":{"lat":31.6,"lon":34.5},"tags":{"tourism":"viewpoint","webcam":"https://example.org/cam"}},
            {"type":"node","id":105,"lat":31.5,"lon":34.4,"tags":{"man_made":"surveillance","webcam":"not a link"}},
            {"type":"node","id":101,"lat":31.9,"lon":34.8,"tags":{"highway":"speed_camera"}}]}"""
        val cams = Cameras.parse(json)
        assertEquals(listOf("node/101", "node/102", "node/103", "way/104", "node/105"), cams.map { it.id })
        val (speed, alpr, gull, view, plain) = cams
        assertEquals(Cameras.Kind.SPEED, speed.kind)
        assertEquals(135.0, speed.direction)
        assertEquals(Cameras.Kind.SECURITY, alpr.kind)
        assertEquals(135.0, alpr.direction)
        assertEquals("ALPR", alpr.type)
        assertEquals(Cameras.Kind.WEBCAM, gull.kind)
        assertEquals("https://wind.co.il/cam", gull.webcam)
        // A way's centre; a "webcam" that isn't a link is just a camera.
        assertEquals(31.6, view.lat)
        assertEquals(Cameras.Kind.SECURITY, plain.kind)
        assertNull(plain.webcam)
        // Speed cameras labelled with their limit; a hidden kind left out.
        val geo = Cameras.geoJson(cams, setOf(Cameras.Kind.WEBCAM.ordinal))
        assertTrue(""""tap":"data:k:node/101","color":"#D32F2F","label":"90"""" in geo, geo)
        assertFalse("node/103" in geo)
        // An error from the server: another one is tried.
        assertTrue(runCatching { Cameras.parse("""{"remark":"runtime error: Query timed out","elements":[]}""") }.isFailure)
        assertEquals(emptyList(), Cameras.parse("""{"elements":[]}"""))
    }

    @Test fun camerasAreaAndQuery() {
        val view = MapGrid.View(32.03, 34.74, 32.13, 34.86)
        val area = Cameras.around(view)
        assertTrue(Cameras.covers(area, view))
        // Out to whole twentieths of a degree, with a margin.
        assertEquals(MapGrid.View(32.0, 34.7, 32.15, 34.9), area)
        val q = Cameras.query(area)
        assertTrue("node[\"highway\"=\"speed_camera\"](32.0,34.7,32.15,34.9)" in q && "out center tags" in q, q)
        assertEquals(null, Cameras.direction("up"))
        assertEquals(270.0, Cameras.direction("W"))
        assertEquals(350.0, Cameras.direction("-10"))
    }

    @Test fun isoTimes() {
        assertEquals(1794979723000L, isoMillis("2026-11-18T05:28:43+00:00"))
        assertEquals(1794979723000L, isoMillis("2026-11-18T07:28:43+02:00"))
        assertEquals(1794979723500L, isoMillis("2026-11-18T05:28:43.5Z"))
        assertNull(isoMillis("yesterday"))
    }

    @Test fun busesFromTheNewestSnapshot() {
        val snaps = """[{"id":2493025,"snapshot_id":"2026/11/18/05/31","etl_status":"loading"},
            {"id":2493024,"snapshot_id":"2026/11/18/05/30","etl_status":"loaded"},{"id":2493023,"snapshot_id":"2026/11/18/05/29","etl_status":"loaded"}]"""
        val snap = assertNotNull(Buses.latestLoaded(snaps))
        assertEquals(2493024L, snap.id)
        assertEquals(isoMillis("2026-11-18T05:30:00Z"), snap.timeMillis)
        val url = Buses.locationsUrl(Buses.OPEN_BUS, snap, MapGrid.View(32.0, 34.74, 32.15, 34.9))
        assertTrue("siri_snapshot_ids=2493024" in url && "lat__greater_or_equal=32.0" in url, url)
        val locations = """[
            {"siri_ride__id":154237934,"siri_route__line_ref":4901,"lat":32.56,"lon":34.94,"bearing":250,"velocity":32,"recorded_at_time":"2026-11-18T05:28:43+00:00","siri_ride__vehicle_ref":"23301002"},
            {"siri_ride__id":154237934,"siri_route__line_ref":4901,"lat":32.57,"lon":34.95,"bearing":250,"velocity":30,"recorded_at_time":"2026-11-18T05:29:43+00:00","siri_ride__vehicle_ref":"23301002"},
            {"siri_ride__id":154237999,"siri_route__line_ref":10965,"lat":32.08,"lon":34.8,"bearing":null,"velocity":0,"recorded_at_time":"2026-11-18T05:29:10+00:00","siri_ride__vehicle_ref":null},
            {"siri_ride__id":154230000,"siri_route__line_ref":10965,"lat":32.09,"lon":34.8,"velocity":0,"recorded_at_time":"2026-11-18T05:12:10+00:00","siri_ride__vehicle_ref":"777"},
            {"siri_ride__id":154237000,"siri_route__line_ref":4901,"lat":32.5,"lon":34.9,"velocity":0,"recorded_at_time":"2026-11-18T05:27:10+00:00","siri_ride__vehicle_ref":"23301002"}]"""
        // The ride that ended long before the snapshot is left out; the bus on two rides is shown once.
        val buses = Buses.parseLocations(locations, snap)
        assertEquals(2, buses.size)
        // Each ride where it was last.
        assertEquals(32.57, buses.first { it.rideId == 154237934L }.lat)
        assertNull(buses.first { it.rideId == 154237999L }.vehicle)
        val routes = Buses.parseRoutes("""[{"line_ref":10965,"route_short_name":"65","agency_name":"דן",
            "route_long_name":"מסוף כרמלית-תל אביב יפו<->ביה''ח תל השומר/מיון-רמת גן-12"}]""").associateBy { it.lineRef }
        val r = routes.getValue(10965)
        assertEquals("65", r.number)
        assertEquals(Buses.Place("רמת גן", "ביה''ח תל השומר/מיון"), r.to)
        assertEquals(Buses.Place("תל אביב יפו", "מסוף כרמלית"), r.from)
        assertEquals(Buses.Place("קרית מלאכי", "דוד בן גוריון/מבצע יונתן"), Buses.place("דוד בן גוריון/מבצע יונתן-קרית מלאכי-1#"))
        val json = Buses.geoJson(buses, routes)
        assertTrue(""""tap":"data:b:154237999","label":"65"""" in json, json)
        // Outside Israel: nothing to ask for.
        assertNull(Buses.clip(MapGrid.View(48.0, 2.0, 49.0, 3.0)))
        assertEquals(29.4, Buses.clip(MapGrid.View(20.0, 30.0, 40.0, 40.0))?.south)
        // Israel's day: 23:30 UTC is already tomorrow there.
        assertEquals("2026-11-19", Buses.israelDate(isoMillis("2026-11-18T23:30:00Z")!!, 7200))
    }
}
