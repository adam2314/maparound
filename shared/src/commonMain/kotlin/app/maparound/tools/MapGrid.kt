package app.maparound.tools

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.fixed
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.padded
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
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

    /** Cells with more than this many in view aren't labelled (the world in Maidenhead fields): lines only. */
    private const val MAX_CELL_LABELS = 400

    private val NONE = Grid(emptyList(), emptyList())

    /**
     * The grid of [format] over [view], [metresPerDp] the map's scale at its [centre] (the view's
     * middle when null): a metric grid for projected formats (UTM, the national grids…), degrees
     * for the others, cells with their codes for Maidenhead, Plus Codes, Geohash and GEOREF. A grid
     * that doesn't reach the centre falls back to degrees.
     *
     * Only the ground around the centre gets lines, as far as [MAX_LINES] of them at the scale's
     * spacing: a tilted map's view reaches to the horizon, where a grid would be either far too
     * coarse near by or far too many lines (or a projection's numbers past any use).
     */
    fun make(format: CoordFormat, view: View, metresPerDp: Double, centre: GeoPoint? = null): Grid {
        val world = sane(view) ?: return NONE
        val mpd = metresPerDp.takeIf { it.isFinite() && it > 0 } ?: ((world.north - world.south) * 111_320.0 / 800)
        val target = mpd * SPACING_DP
        val c = centre?.takeIf { it.lat.isFinite() && it.lon.isFinite() }?.let { GeoPoint(it.lat.coerceIn(-85.0, 85.0), it.lon) }
            ?: GeoPoint((world.south + world.north) / 2, (world.west + world.east) / 2)
        val v = around(world, c, target * MAX_LINES * 0.6) ?: return NONE
        val middle = GeoPoint((v.south + v.north) / 2, (v.west + v.east) / 2)
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
            else -> projection(format, middle)?.let { metric(v, target, it) } ?: degrees(v, target, CoordFormat.DD)
        }
    }

    /**
     * [view] within the world: latitudes within ±85°, at most once around in longitude (a map
     * showing the world's copies side by side); null when it's empty or not numbers.
     */
    fun sane(view: View): View? {
        if (!(view.south.isFinite() && view.north.isFinite() && view.west.isFinite() && view.east.isFinite())) return null
        val south = view.south.coerceIn(-85.0, 85.0)
        val north = view.north.coerceIn(-85.0, 85.0)
        var west = view.west
        var east = view.east
        if (east - west >= 360) {
            west = -180.0
            east = 180.0
        }
        return if (north > south && east > west) View(south, west, north, east) else null
    }

    /** The part of [v] within [reachM] metres (north, south, east and west) of [c]; null if none. */
    private fun around(v: View, c: GeoPoint, reachM: Double): View? {
        val dLat = reachM / 111_320.0
        val dLon = dLat / max(cos(c.lat * kotlin.math.PI / 180), 0.05)
        val out = View(max(v.south, c.lat - dLat), max(v.west, c.lon - dLon), min(v.north, c.lat + dLat), min(v.east, c.lon + dLon))
        return out.takeIf { it.north > it.south && it.east > it.west }
    }

    // ---- metric grids -------------------------------------------------------------------------------

    /**
     * A projected grid: (lat, lon) → (easting, northing) and back, where it [reaches] (a zone's
     * transverse Mercator is only sound near its zone; a national grid covers its country).
     */
    class Projection(
        val forward: (GeoPoint) -> Pair<Double, Double>?,
        val inverse: (Double, Double) -> GeoPoint?,
        val reaches: (GeoPoint) -> Boolean = { true },
    )

    /** [format]'s projection around [centre], or null where it doesn't reach (another country's grid). */
    fun projection(format: CoordFormat, centre: GeoPoint): Projection? {
        if (Coords.format(centre, format) == null) return null
        val inCountry = { p: GeoPoint -> Coords.format(p, format) != null }
        return when (format) {
            CoordFormat.UTM, CoordFormat.MGRS -> Utm.from(centre)?.let { u ->
                val tm = Utm.tm(u.zone, centre.lat >= 0)
                val meridian = u.zone * 6.0 - 183
                Projection({ tm.forward(it.lat, it.lon) }, { e, n -> tm.inverse(e, n) }) { p ->
                    // Its zone and a little beyond (lines go on past a zone's edge on paper maps too).
                    p.lat in -80.0..84.0 && abs((((p.lon - meridian) % 360 + 540) % 360) - 180) <= UTM_REACH
                }
            }
            CoordFormat.ITM -> Projection({ Itm.project(it) }, { e, n -> Itm.toPoint(e, n) }, inCountry)
            CoordFormat.OSGB -> Projection({ Osgb.project(it) }, { e, n -> Osgb.unproject(e, n) }, inCountry)
            CoordFormat.IRISH_TM -> Projection({ IrishTm.project(it) }, { e, n -> IrishTm.toPoint(e, n) }, inCountry)
            CoordFormat.LV95 -> Projection({ Lv95.project(it) }, { e, n -> Lv95.toPoint(e, n) }, inCountry)
            CoordFormat.RD -> Projection({ Rd.project(it) }, { e, n -> Rd.toPoint(e, n) }, inCountry)
            CoordFormat.LAMBERT93 -> Projection({ Lambert93.project(it) }, { e, n -> Lambert93.toPoint(e, n) }, inCountry)
            CoordFormat.WEB_MERCATOR -> Projection({ WebMercator.from(it) }, { x, y -> WebMercator.toPoint(x, y) })
            else -> null
        }
    }

    /** Degrees of longitude from a UTM zone's middle its grid is drawn to (the zone is 3° each way). */
    private const val UTM_REACH = 9.0

    /** 1, 2, 5 × 10ⁿ metres, from 10 m to 10 000 km (Web Mercator's whole world is 40 000 km across). */
    private val METRIC_STEPS = listOf(
        10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
        100_000.0, 200_000.0, 500_000.0, 1_000_000.0, 2_000_000.0, 5_000_000.0, 10_000_000.0,
    )

    /** The step the lines are drawn at: the first one [target] metres or more apart. */
    fun metricStep(target: Double): Double = METRIC_STEPS.firstOrNull { it >= target } ?: METRIC_STEPS.last()

    /** A grid value worth drawing: a number, and on Earth (a projection's far side gives anything). */
    private fun usable(v: Double) = v.isFinite() && abs(v) < 1e8

    private fun metric(v: View, target: Double, proj: Projection): Grid {
        // The extent in the grid of the view's part the projection reaches: points across it projected.
        val es = ArrayList<Double>()
        val ns = ArrayList<Double>()
        for (i in 0..EXTENT_SAMPLES) for (j in 0..EXTENT_SAMPLES) {
            val p = GeoPoint(v.south + (v.north - v.south) * i / EXTENT_SAMPLES, v.west + (v.east - v.west) * j / EXTENT_SAMPLES)
            if (!proj.reaches(p)) continue
            proj.forward(p)?.takeIf { (e, n) -> usable(e) && usable(n) }?.let { (e, n) -> es += e; ns += n }
        }
        if (es.size < 2) return NONE
        val minE = es.min()
        val maxE = es.max()
        val minN = ns.min()
        val maxN = ns.max()
        // The finest step at least [target] apart that fits; the coarsest otherwise.
        val step = METRIC_STEPS.firstOrNull { it >= target && (maxE - minE) / it <= MAX_LINES && (maxN - minN) / it <= MAX_LINES } ?: METRIC_STEPS.last()
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        fun ok(p: GeoPoint?): GeoPoint? = p?.takeIf { it.lat.isFinite() && it.lon.isFinite() && abs(it.lat) <= 85.5 && proj.reaches(it) }
        fun along(from: Double, to: Double, at: (Double) -> GeoPoint?): List<GeoPoint> =
            (0..SAMPLES).mapNotNull { ok(at(from + (to - from) * it / SAMPLES)) }
        // Counted, not added up: each line's value from the first (a sum could stall far from 0).
        val e0 = ceil(minE / step)
        for (i in 0..((maxE - minE) / step).toInt() + 1) {
            val e = (e0 + i) * step
            if (e > maxE) break
            val pts = along(minN, maxN) { n -> proj.inverse(e, n) }
            if (pts.size >= 2) lines += Line(pts)
            ok(proj.inverse(e, maxN - (maxN - minN) * EDGE))?.let { labels += Label(it, km(e, step)) }
        }
        val n0 = ceil(minN / step)
        for (i in 0..((maxN - minN) / step).toInt() + 1) {
            val n = (n0 + i) * step
            if (n > maxN) break
            val pts = along(minE, maxE) { x -> proj.inverse(x, n) }
            if (pts.size >= 2) lines += Line(pts)
            ok(proj.inverse(minE + (maxE - minE) * EDGE, n))?.let { labels += Label(it, km(n, step)) }
        }
        return Grid(lines, labels)
    }

    /** Points across the view (this many steps each way) whose projection gives the grid's extent. */
    private const val EXTENT_SAMPLES = 12

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
        val steps = when (format) {
            CoordFormat.DDM -> MINUTE_STEPS
            CoordFormat.DMS -> SECOND_STEPS
            else -> DECIMAL_STEPS
        }
        val wanted = degreeStep(format, target)
        // The finest step at least as wide as wanted that fits; the coarsest otherwise (30°: 12 across the world).
        val step = steps.firstOrNull { it >= wanted && (v.east - v.west) / it <= MAX_LINES && (v.north - v.south) / it <= MAX_LINES } ?: steps.last()
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        val labelLat = v.north - (v.north - v.south) * EDGE
        val labelLon = v.west + (v.east - v.west) * EDGE
        val lon0 = ceil(v.west / step - 1e-9)
        for (i in 0..((v.east - v.west) / step).toInt() + 1) {
            val lon = (lon0 + i) * step
            if (lon > v.east) break
            lines += Line(listOf(GeoPoint(v.south, lon), GeoPoint(v.north, lon)))
            labels += Label(GeoPoint(labelLat, lon), angle(lon, step, format, 'E', 'W'))
        }
        val lat0 = ceil(v.south / step - 1e-9)
        for (i in 0..((v.north - v.south) / step).toInt() + 1) {
            val lat = (lat0 + i) * step
            if (lat > v.north) break
            lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)))
            labels += Label(GeoPoint(lat, labelLon), angle(lat, step, format, 'N', 'S'))
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
        // Cells are counted from 180°W and 90°S; columns and rows counted, not added up.
        val col0 = floor((v.west + 180) / size.lon)
        val row0 = floor((v.south + 90) / size.lat)
        val cols = ((v.east - v.west) / size.lon).toInt() + 2
        val rows = ((v.north - v.south) / size.lat).toInt() + 2
        for (i in 0..cols) {
            val lon = (col0 + i) * size.lon - 180
            if (lon > v.east) break
            if (lon >= v.west) lines += Line(listOf(GeoPoint(v.south, lon), GeoPoint(v.north, lon)))
        }
        for (j in 0..rows) {
            val lat = (row0 + j) * size.lat - 90
            if (lat > v.north) break
            if (lat >= v.south) lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)))
        }
        // Each cell in view labelled in the middle of the part that's in view (so the cell the
        // whole view is in has its code too); not when there are too many to read.
        if (cols.toLong() * rows > MAX_CELL_LABELS) return Grid(lines, labels)
        for (j in 0 until rows) {
            val lat = (row0 + j) * size.lat - 90
            if (lat >= v.north) break
            for (i in 0 until cols) {
                val lon = (col0 + i) * size.lon - 180
                if (lon >= v.east) break
                val s = maxOf(lat, v.south)
                val n = minOf(lat + size.lat, v.north)
                val w = maxOf(lon, v.west)
                val e = minOf(lon + size.lon, v.east)
                if (n > s && e > w) {
                    // The code from the cell's own middle (not the visible part's, which may sit in a finer cell's corner).
                    val cellMid = GeoPoint((lat + size.lat / 2).coerceIn(-89.99, 89.99), lon + size.lon / 2)
                    labels += Label(GeoPoint((s + n) / 2, (w + e) / 2), code(cellMid, level))
                }
            }
        }
        return Grid(lines, labels)
    }
}
