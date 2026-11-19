package app.maparound.tools

import app.maparound.routing.GeoPoint
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every coordinate grid over many views, each within a time limit: zoomed in and out, tilted (the
 * view reaching to the horizon), the world's copies side by side, the poles, the date line, a UTM
 * zone's edge. None may hang (a grid's loop that never ends burns the phone and runs out of
 * memory), throw, or make more lines and labels than a map can show.
 */
class GridFuzzTest {
    private class Case(val name: String, val view: MapGrid.View, val mpd: Double, val centre: GeoPoint?)

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val places = listOf(
            "Eiger" to GeoPoint(46.577, 8.005), "Tel Aviv" to GeoPoint(32.08, 34.78), "London" to GeoPoint(51.5, -0.12),
            "0,0" to GeoPoint(0.0, 0.0), "date line" to GeoPoint(10.0, 179.99), "Arctic" to GeoPoint(84.0, 20.0),
            "Antarctic" to GeoPoint(-84.0, -60.0), "UTM zone edge" to GeoPoint(46.0, 11.999),
        )
        for ((name, c) in places) for (zoom in listOf(1.0, 3.0, 5.0, 8.0, 12.0, 16.0, 18.0, 20.0, 22.0, 24.0)) {
            val mpd = 40_075_016.0 * cos(Math.toRadians(c.lat)) / (512 * Math.pow(2.0, zoom))
            val dLat = mpd * 400 / 111_320.0
            val dLon = mpd * 200 / (111_320.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(0.01))
            out += Case("$name z$zoom", MapGrid.View(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon), mpd, c)
            // Tilted: the far edge hundreds of views away, wider there.
            out += Case("$name z$zoom tilted", MapGrid.View(c.lat - dLat, c.lon - dLon * 60, c.lat + dLat * 600, c.lon + dLon * 60), mpd, c)
            // Without the centre (as a caller might): the view's middle.
            out += Case("$name z$zoom tilted, no centre", MapGrid.View(c.lat - dLat, c.lon - dLon * 60, c.lat + dLat * 600, c.lon + dLon * 60), mpd, null)
        }
        out += Case("world", MapGrid.View(-85.0, -180.0, 85.0, 180.0), 40_000.0, null)
        out += Case("world copies", MapGrid.View(-85.0, -700.0, 85.0, 700.0), 40_000.0, null)
        out += Case("one copy over", MapGrid.View(20.0, -400.0, 60.0, -300.0), 20_000.0, null)
        out += Case("not numbers", MapGrid.View(Double.NaN, 0.0, 10.0, 10.0), 10.0, null)
        out += Case("infinite", MapGrid.View(0.0, Double.NEGATIVE_INFINITY, 10.0, 10.0), 10.0, null)
        out += Case("no scale", MapGrid.View(46.0, 8.0, 46.1, 8.1), Double.NaN, null)
        return out
    }

    @Test
    fun everyGridOverEveryViewIsQuickAndBounded() {
        val problems = ArrayList<String>()
        for (format in CoordFormat.entries) for (c in cases()) {
            var result: MapGrid.Grid? = null
            var error: Throwable? = null
            val start = System.nanoTime()
            val t = Thread { try { result = MapGrid.make(format, c.view, c.mpd, c.centre) } catch (e: Throwable) { error = e } }
            t.isDaemon = true
            t.start()
            t.join(5_000)
            val ms = (System.nanoTime() - start) / 1e6
            val r = result
            when {
                t.isAlive -> problems += "hangs: $format, ${c.name}"
                error != null -> problems += "throws: $format, ${c.name}: $error"
                r == null -> problems += "nothing: $format, ${c.name}"
                r.lines.size > 700 || r.labels.size > 400 -> problems += "too much: $format, ${c.name}: ${r.lines.size} lines, ${r.labels.size} labels"
                r.lines.any { l -> l.points.any { !it.lat.isFinite() || !it.lon.isFinite() } } -> problems += "not numbers: $format, ${c.name}"
                ms > 1_000 -> problems += "slow: $format, ${c.name}: ${ms.toInt()} ms"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun aTiltedViewGetsTheGridOfItsCentreNotOfTheHorizon() {
        // Zoomed in on the Eiger (about 1 m a dp), tilted so the view's far edge is 300 km away.
        val c = GeoPoint(46.577, 8.005)
        val mpd = 1.0
        val view = MapGrid.View(46.57, 7.9, 49.3, 8.1)
        val g = MapGrid.make(CoordFormat.UTM, view, mpd, c)
        // Lines 110 m apart or so (100 m), not the 50 km the whole view would need.
        val values = g.lines.mapNotNull { it.label?.replace(',', '.')?.toDoubleOrNull() }.distinct().sorted()
        val spans = values.zipWithNext { a, b -> b - a }.filter { it > 0 }
        assertTrue(g.lines.isNotEmpty() && spans.isNotEmpty() && spans.min() < 0.5, "${g.lines.size} lines, values $values")
    }
}
