package app.maparound.tools

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.fixed
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.padded
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Plain Kotlin: the coordinate grid drawn over the map, in the format chosen for coordinates, its
// spacing following the zoom. Unit-tested.

object MapGrid {
    /** A grid line, as points along it (straight lines in degrees need only their ends). */
    data class Line(val points: List<GeoPoint>)

    /** A value written on the map: a line's at the view's edge, or a cell's code in its middle. */
    data class Label(val at: GeoPoint, val text: String)

    data class Grid(val lines: List<Line>, val labels: List<Label>)

    /** The visible area. */
    data class View(val south: Double, val west: Double, val north: Double, val east: Double)

    /** Lines this far apart on screen (density-independent pixels), or a bit more. */
    const val SPACING_DP = 110.0

    /** Never more lines than this one way (a zoom between levels, a very tall view). */
    private const val MAX_LINES = 40

    /**
     * The grid of [format] over [view], [metresPerDp] the map's scale: a metric grid for projected
     * formats (UTM, the national grids…), degrees for the others, cells with their codes for
     * Maidenhead, Plus Codes, Geohash and GEOREF. A grid that doesn't reach the view's middle falls
     * back to degrees.
     */
    fun make(format: CoordFormat, view: View, metresPerDp: Double): Grid {
        val v = view.copy(south = view.south.coerceIn(-85.0, 85.0), north = view.north.coerceIn(-85.0, 85.0))
        if (v.north <= v.south || v.east <= v.west) return Grid(emptyList(), emptyList())
        val target = metresPerDp * SPACING_DP
        val centre = GeoPoint((v.south + v.north) / 2, (v.west + v.east) / 2)
        return when (format) {
            CoordFormat.MAIDENHEAD -> cells(v, target, MAIDENHEAD_LEVELS) { p, level -> Maidenhead.encode(p).take(2 * (level + 1)) }
            CoordFormat.PLUS -> cells(v, target, PLUS_LEVELS) { p, level -> plusCode(p, level) }
            CoordFormat.GEOHASH -> cells(v, target, geohashLevels()) { p, level -> Geohash.encode(p, level + 1) }
            CoordFormat.GEOREF -> if (target >= 111_320.0) {
                cells(v, target, GEOREF_LEVELS) { p, level -> Georef.encode(p).take(2 * (level + 1)) }
            } else {
                degrees(v, target, CoordFormat.DDM)
            }
            CoordFormat.DD, CoordFormat.DDM, CoordFormat.DMS, CoordFormat.ECEF -> degrees(v, target, format)
            else -> projection(format, centre)?.let { metric(v, target, it) } ?: degrees(v, target, CoordFormat.DD)
        }
    }

    // ---- metric grids -------------------------------------------------------------------------------

    /** A projected grid: (lat, lon) → (easting, northing) and back. */
    class Projection(val forward: (GeoPoint) -> Pair<Double, Double>?, val inverse: (Double, Double) -> GeoPoint?)

    /** [format]'s projection around [centre], or null where it doesn't reach (another country's grid). */
    fun projection(format: CoordFormat, centre: GeoPoint): Projection? {
        if (Coords.format(centre, format) == null) return null
        return when (format) {
            CoordFormat.UTM, CoordFormat.MGRS -> Utm.from(centre)?.let { u ->
                val tm = Utm.tm(u.zone, centre.lat >= 0)
                Projection({ tm.forward(it.lat, it.lon) }, { e, n -> tm.inverse(e, n) })
            }
            CoordFormat.ITM -> Projection({ Itm.project(it) }, { e, n -> Itm.toPoint(e, n) })
            CoordFormat.OSGB -> Projection({ Osgb.project(it) }, { e, n -> Osgb.unproject(e, n) })
            CoordFormat.IRISH_TM -> Projection({ IrishTm.project(it) }, { e, n -> IrishTm.toPoint(e, n) })
            CoordFormat.LV95 -> Projection({ Lv95.project(it) }, { e, n -> Lv95.toPoint(e, n) })
            CoordFormat.RD -> Projection({ Rd.project(it) }, { e, n -> Rd.toPoint(e, n) })
            CoordFormat.LAMBERT93 -> Projection({ Lambert93.project(it) }, { e, n -> Lambert93.toPoint(e, n) })
            CoordFormat.WEB_MERCATOR -> Projection({ WebMercator.from(it) }, { x, y -> WebMercator.toPoint(x, y) })
            else -> null
        }
    }

    /** 1, 2, 5 × 10ⁿ metres, from 10 m to 1000 km. */
    private val METRIC_STEPS = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0, 1_000_000.0)

    /** The step the lines are drawn at: the first one [target] metres or more apart. */
    fun metricStep(target: Double): Double = METRIC_STEPS.firstOrNull { it >= target } ?: METRIC_STEPS.last()

    private fun metric(v: View, target: Double, proj: Projection): Grid {
        // The view's extent in the grid: its edges projected.
        val es = ArrayList<Double>()
        val ns = ArrayList<Double>()
        for (i in 0..8) {
            val f = i / 8.0
            for (p in listOf(
                GeoPoint(v.south, v.west + (v.east - v.west) * f), GeoPoint(v.north, v.west + (v.east - v.west) * f),
                GeoPoint(v.south + (v.north - v.south) * f, v.west), GeoPoint(v.south + (v.north - v.south) * f, v.east),
            )) proj.forward(p)?.let { (e, n) -> es += e; ns += n }
        }
        if (es.isEmpty()) return Grid(emptyList(), emptyList())
        val minE = es.min()
        val maxE = es.max()
        val minN = ns.min()
        val maxN = ns.max()
        var step = metricStep(target)
        while ((maxE - minE) / step > MAX_LINES || (maxN - minN) / step > MAX_LINES) step = metricStep(step * 1.01)
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        fun along(from: Double, to: Double, at: (Double) -> GeoPoint?): List<GeoPoint> =
            (0..SAMPLES).mapNotNull { at(from + (to - from) * it / SAMPLES) }
        var e = ceil(minE / step) * step
        while (e <= maxE) {
            val pts = along(minN, maxN) { n -> proj.inverse(e, n) }
            if (pts.size >= 2) lines += Line(pts)
            proj.inverse(e, maxN - (maxN - minN) * EDGE)?.let { labels += Label(it, km(e, step)) }
            e += step
        }
        var n = ceil(minN / step) * step
        while (n <= maxN) {
            val pts = along(minE, maxE) { x -> proj.inverse(x, n) }
            if (pts.size >= 2) lines += Line(pts)
            proj.inverse(minE + (maxE - minE) * EDGE, n)?.let { labels += Label(it, km(n, step)) }
            n += step
        }
        return Grid(lines, labels)
    }

    /** Points along a projected grid line (they curve a little in degrees). */
    private const val SAMPLES = 16

    /**
     * Labels sit this far in from the view's top (eastings, longitudes) and left (northings,
     * latitudes): the bottom has the map's credits and the panels.
     */
    private const val EDGE = 0.05

    /** A grid value in kilometres, as maps write them: "711", or "711.5" for steps under a kilometre. */
    fun km(v: Double, step: Double): String = when {
        step >= 1_000 -> (v / 1_000).roundToLong().toString()
        step >= 100 -> localFixed(v / 1_000, 1)
        else -> localFixed(v / 1_000, 2)
    }

    // ---- degrees ------------------------------------------------------------------------------------

    /** Decimal steps: 1, 2, 5 × 10ⁿ degrees, from 0.0001° to 30°. */
    private val DECIMAL_STEPS = listOf(0.0001, 0.0002, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0)

    /** Minutes, then degrees. */
    private val MINUTE_STEPS = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0).map { it / 60 } + listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0)

    /** Seconds, minutes, then degrees. */
    private val SECOND_STEPS = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0).map { it / 3600 } +
        listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0).map { it / 60 } + listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0)

    /** The step in degrees for [format], [target] metres apart (a degree of latitude: 111 km). */
    fun degreeStep(format: CoordFormat, target: Double): Double {
        val steps = when (format) {
            CoordFormat.DDM -> MINUTE_STEPS
            CoordFormat.DMS -> SECOND_STEPS
            else -> DECIMAL_STEPS
        }
        val deg = target / 111_320.0
        return steps.firstOrNull { it >= deg } ?: steps.last()
    }

    private fun degrees(v: View, target: Double, format: CoordFormat): Grid {
        var step = degreeStep(format, target)
        while ((v.east - v.west) / step > MAX_LINES || (v.north - v.south) / step > MAX_LINES) step = degreeStep(format, step * 111_320.0 * 1.01)
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        val labelLat = v.north - (v.north - v.south) * EDGE
        val labelLon = v.west + (v.east - v.west) * EDGE
        var lon = ceil(v.west / step - 1e-9) * step
        while (lon <= v.east) {
            lines += Line(listOf(GeoPoint(v.south, lon), GeoPoint(v.north, lon)))
            labels += Label(GeoPoint(labelLat, lon), angle(lon, step, format, 'E', 'W'))
            lon += step
        }
        var lat = ceil(v.south / step - 1e-9) * step
        while (lat <= v.north) {
            lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)))
            labels += Label(GeoPoint(lat, labelLon), angle(lat, step, format, 'N', 'S'))
            lat += step
        }
        return Grid(lines, labels)
    }

    /** A line's value as [format] writes angles: "35.39°", "35°23.5′E", "35°23′30″E". */
    fun angle(value: Double, step: Double, format: CoordFormat, pos: Char, neg: Char): String {
        val a = abs(value)
        val hemi = if (value >= 0) pos else neg
        return when (format) {
            CoordFormat.DDM -> {
                val totalTenths = (a * 600).roundToLong()
                val d = totalTenths / 600
                val m = (totalTenths % 600) / 10.0
                val minutes = if (step * 60 < 1 - 1e-9) fixed(m, 1) else m.roundToInt().toString()
                if (totalTenths % 600 == 0L) "$d°$hemi" else "$d°$minutes′$hemi"
            }
            CoordFormat.DMS -> {
                val total = (a * 3600).roundToLong()
                val d = total / 3600
                val m = (total % 3600) / 60
                val s = total % 60
                when {
                    total % 3600 == 0L -> "$d°$hemi"
                    s == 0L -> "$d°${padded(m, 2)}′$hemi"
                    else -> "$d°${padded(m, 2)}′${padded(s, 2)}″$hemi"
                }
            }
            else -> {
                val decimals = when {
                    step >= 1 -> 0
                    step >= 0.1 -> 1
                    step >= 0.01 -> 2
                    step >= 0.001 -> 3
                    else -> 4
                }
                fixed(value, decimals) + "°"
            }
        }
    }

    // ---- cells with codes --------------------------------------------------------------------------

    /** A level of a code's cells: its size in degrees (longitude, latitude). */
    class Level(val lon: Double, val lat: Double)

    private val MAIDENHEAD_LEVELS = listOf(Level(20.0, 10.0), Level(2.0, 1.0), Level(5.0 / 60, 2.5 / 60), Level(0.5 / 60, 0.25 / 60))
    private val PLUS_LEVELS = listOf(20.0, 1.0, 0.05, 0.0025, 0.000125).map { Level(it, it) }
    private val GEOREF_LEVELS = listOf(Level(15.0, 15.0), Level(1.0, 1.0))

    /** Geohash cells of 1 to 9 characters: the bits alternate, longitude first. */
    private fun geohashLevels(): List<Level> = (1..9).map { len ->
        val bits = 5 * len
        Level(360.0 / (1L shl ((bits + 1) / 2)), 180.0 / (1L shl (bits / 2)))
    }

    /** A Plus Code cut to its [level]'s pairs ("8G", "8G3Q", "8G3QQ6", "8G3QQ6GM+", full). */
    private fun plusCode(p: GeoPoint, level: Int): String {
        val digits = PlusCode.encode(p).replace("+", "")
        val keep = 2 * (level + 1)
        return when {
            keep < 8 -> digits.take(keep)
            keep == 8 -> digits.take(8) + "+"
            else -> digits.take(8) + "+" + digits.drop(8)
        }
    }

    /**
     * The cells of the finest level at least [target] metres tall (the coarsest when none is; the
     * finest that isn't too many), each with its code in its middle.
     */
    private fun cells(v: View, target: Double, levels: List<Level>, code: (GeoPoint, Int) -> String): Grid {
        var level = levels.indexOfLast { it.lat * 111_320.0 >= target }.coerceAtLeast(0)
        while (level > 0 && ((v.east - v.west) / levels[level].lon > MAX_LINES || (v.north - v.south) / levels[level].lat > MAX_LINES)) level--
        val size = levels[level]
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        // Cells are counted from 180°W and 90°S.
        val firstLon = floor((v.west + 180) / size.lon) * size.lon - 180
        val firstLat = floor((v.south + 90) / size.lat) * size.lat - 90
        var lon = firstLon
        while (lon <= v.east) {
            if (lon >= v.west) lines += Line(listOf(GeoPoint(v.south, lon), GeoPoint(v.north, lon)))
            lon += size.lon
        }
        var lat = firstLat
        while (lat <= v.north) {
            if (lat >= v.south) lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)))
            lat += size.lat
        }
        // Each cell in view labelled in the middle of the part that's in view (so the cell the
        // whole view is in has its code too).
        lat = firstLat
        while (lat < v.north) {
            lon = firstLon
            while (lon < v.east) {
                val s = maxOf(lat, v.south)
                val n = minOf(lat + size.lat, v.north)
                val w = maxOf(lon, v.west)
                val e = minOf(lon + size.lon, v.east)
                if (n > s && e > w) {
                    // The code from the cell's own middle (not the visible part's, which may sit in a finer cell's corner).
                    val cellMid = GeoPoint((lat + size.lat / 2).coerceIn(-89.99, 89.99), lon + size.lon / 2)
                    labels += Label(GeoPoint((s + n) / 2, (w + e) / 2), code(cellMid, level))
                }
                lon += size.lon
            }
            lat += size.lat
        }
        return Grid(lines, labels)
    }
}
