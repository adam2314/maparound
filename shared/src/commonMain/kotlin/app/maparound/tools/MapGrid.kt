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
//
// Steady while the map moves: the spacing follows the scale alone; the lines are always at the same
// round values; a grid is made for more than the view (so a pan of up to half a view needs no new
// one, see [MapGrid.fits]); and the values are written along the lines, where the map places them
// itself on every frame.

object MapGrid {
    /** A grid line: points along it, and its value written along it ("711", "35.25°"), or none. */
    data class Line(val points: List<GeoPoint>, val label: String? = null)

    /** A cell's code in its middle (Maidenhead, Plus Codes, Geohash, GEOREF, UTM zones). */
    data class Label(val at: GeoPoint, val text: String)

    /**
     * A grid, made for the area it [covers] (more than the view) at the spacing [key] names: it
     * still serves while the view stays inside [covers] and the scale asks for the same [key]
     * ([fits]).
     */
    data class Grid(val lines: List<Line>, val labels: List<Label>, val covers: View? = null, val key: String = "")

    /** An area: the visible one, or what a grid covers. */
    data class View(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(o: View): Boolean = o.south >= south && o.north <= north && o.west >= west && o.east <= east
    }

    /** Lines this far apart on screen (density-independent pixels), or a bit more. */
    const val SPACING_DP = 110.0

    /** Never more lines than this one way (a zoom between levels, a very tall view). */
    private const val MAX_LINES = 80

    /**
     * How far from the map's centre (in spacings) the view's grid has to reach: a tilted map's view
     * goes on to the horizon, where a grid at the centre's spacing would be thousands of lines.
     */
    private const val VIEW_REACH = 24.0

    /** How far from the centre (in spacings) a grid is made for, at most. */
    private const val COVER_REACH = 36.0

    /** A grid covers the view and this much of its size more on each side. */
    private const val PAD = 0.5

    /** Cells with more than this many in a grid aren't labelled (the world in Maidenhead fields): lines only. */
    private const val MAX_CELL_LABELS = 400

    /** More UTM zones than this in a grid: the zones and bands themselves ("32T"), not their kilometres. */
    private const val MAX_ZONES = 4

    private val NONE = Grid(emptyList(), emptyList())

    /**
     * The grid of [format] over [view], [metresPerDp] the map's scale at its [centre] (the view's
     * middle when null): a metric grid for projected formats (UTM, the national grids…), degrees
     * for the others, cells with their codes for Maidenhead, Plus Codes, Geohash and GEOREF. A grid
     * that doesn't reach the centre falls back to degrees.
     */
    fun make(format: CoordFormat, view: View, metresPerDp: Double, centre: GeoPoint? = null): Grid =
        plan(format, view, metresPerDp, centre)?.let { p -> p.build().copy(covers = p.covered, key = p.key) } ?: NONE

    /**
     * Whether [grid] still serves [view] (at [metresPerDp], around [centre]): the same spacing, and
     * the part of the view a grid is drawn on inside what it covers. Cheap: call it as the map moves.
     */
    fun fits(grid: Grid, format: CoordFormat, view: View, metresPerDp: Double, centre: GeoPoint? = null): Boolean {
        val p = plan(format, view, metresPerDp, centre) ?: return grid.lines.isEmpty() && grid.labels.isEmpty()
        return grid.key == p.key && grid.covers?.contains(p.needed) == true
    }

    /** What a grid is made of: the area it must cover ([needed]), the one it's made for, its spacing's key, and how. */
    private class Plan(val needed: View, val covered: View, val key: String, val build: () -> Grid)

    private fun plan(format: CoordFormat, view: View, metresPerDp: Double, centre: GeoPoint?): Plan? {
        val world = sane(view) ?: return null
        val mpd = metresPerDp.takeIf { it.isFinite() && it > 0 } ?: ((world.north - world.south) * 111_320.0 / 800)
        val target = mpd * SPACING_DP
        val c = centre?.takeIf { it.lat.isFinite() && it.lon.isFinite() }?.let { GeoPoint(it.lat.coerceIn(-85.0, 85.0), it.lon) }
            ?: GeoPoint((world.south + world.north) / 2, (world.west + world.east) / 2)
        val needed = around(world, c, target * VIEW_REACH) ?: return null
        val h = (needed.north - needed.south) * PAD
        val w = (needed.east - needed.west) * PAD
        val padded = sane(View(needed.south - h, needed.west - w, needed.north + h, needed.east + w)) ?: needed
        val covered = around(padded, c, target * COVER_REACH) ?: needed
        fun cells(levels: List<Level>, code: (GeoPoint, Int) -> String): Plan {
            var level = levels.indexOfLast { it.lat * 111_320.0 >= target }.coerceAtLeast(0)
            while (level > 0 && ((covered.east - covered.west) / levels[level].lon > MAX_LINES || (covered.north - covered.south) / levels[level].lat > MAX_LINES)) level--
            return Plan(needed, covered, "c$level") { cells(covered, levels, level, code) }
        }
        fun degrees(f: CoordFormat): Plan {
            val step = degreeStep(f, target)
            // Never more than [MAX_LINES] each way (near the poles a degree east is short).
            val d = MAX_LINES / 2.0 * step
            val v = within(covered, c, d, d)
            return Plan(within(needed, c, d, d), v, "d$step") { degrees(v, step, f) }
        }
        return when (format) {
            CoordFormat.MAIDENHEAD -> cells(MAIDENHEAD_LEVELS) { p, level -> Maidenhead.encode(p).take(2 * (level + 1)) }
            CoordFormat.PLUS -> cells(PLUS_LEVELS) { p, level -> plusCode(p, level) }
            CoordFormat.GEOHASH -> cells(geohashLevels()) { p, level -> Geohash.encode(p, level + 1) }
            CoordFormat.GEOREF -> if (target >= 111_320.0) cells(GEOREF_LEVELS) { p, level -> Georef.encode(p).take(2 * (level + 1)) } else degrees(CoordFormat.DDM)
            CoordFormat.DD, CoordFormat.DDM, CoordFormat.DMS, CoordFormat.ECEF -> degrees(format)
            else -> {
                val projections = projections(format, covered, c) ?: return degrees(CoordFormat.DD)
                if (projections.isEmpty()) {
                    // UTM zoomed out over many zones: the zones and their bands.
                    Plan(needed, covered, "zones") { utmZones(covered) }
                } else {
                    val step = metricStep(target)
                    Plan(needed, covered, "m$step") {
                        Grid(projections.flatMap { metric(covered, c, step, it) }, emptyList())
                    }
                }
            }
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
        return within(v, c, dLat, dLon).takeIf { it.north > it.south && it.east > it.west }
    }

    /** The part of [v] within [dLat] and [dLon] degrees of [c] (or [v] itself where it doesn't reach that far). */
    private fun within(v: View, c: GeoPoint, dLat: Double, dLon: Double): View =
        View(max(v.south, c.lat - dLat), max(v.west, c.lon - dLon), min(v.north, c.lat + dLat), min(v.east, c.lon + dLon))

    // ---- metric grids -------------------------------------------------------------------------------

    /**
     * A projected grid: (lat, lon) → (easting, northing) and back, where it [reaches] (a UTM zone's
     * lines stop at its edge; a national grid's at its country's).
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
            CoordFormat.UTM, CoordFormat.MGRS -> Utm.from(centre)?.let { utmZone(it.zone, centre.lat >= 0) }
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

    /** UTM [zone]'s grid, reaching exactly its zone (Norway's and Svalbard's exceptions too, as [Utm.from] has them). */
    private fun utmZone(zone: Int, north: Boolean): Projection {
        val tm = Utm.tm(zone, north)
        return Projection({ tm.forward(it.lat, it.lon) }, { e, n -> tm.inverse(e, n) }) { p -> Utm.zoneOf(p) == zone }
    }

    /**
     * The projections a grid of [format] over [covered] is drawn in: UTM's zones there (each to its
     * edge, so crossing into the next zone shows both, not a jump), empty when there are too many
     * (zoomed out: the zones themselves instead); another format's one; null where it doesn't
     * reach [centre].
     */
    private fun projections(format: CoordFormat, covered: View, centre: GeoPoint): List<Projection>? {
        if (format != CoordFormat.UTM && format != CoordFormat.MGRS) return projection(format, centre)?.let(::listOf)
        if (Utm.from(centre) == null) return null
        val zones = LinkedHashSet<Int>()
        Utm.zoneOf(centre)?.let { zones += it }
        for (i in 0..ZONE_SAMPLES) for (j in 0..ZONE_SAMPLES) {
            val p = GeoPoint(covered.south + (covered.north - covered.south) * i / ZONE_SAMPLES, covered.west + (covered.east - covered.west) * j / ZONE_SAMPLES)
            Utm.zoneOf(p)?.let { zones += it }
        }
        if (zones.size > MAX_ZONES) return emptyList()
        return zones.map { utmZone(it, centre.lat >= 0) }
    }

    /** Points across a grid's area (this many steps each way) where UTM zones are looked for. */
    private const val ZONE_SAMPLES = 16

    /** 1, 2, 5 × 10ⁿ metres, from 10 m to 10 000 km (Web Mercator's whole world is 40 000 km across). */
    private val METRIC_STEPS = listOf(
        10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
        100_000.0, 200_000.0, 500_000.0, 1_000_000.0, 2_000_000.0, 5_000_000.0, 10_000_000.0,
    )

    /** The step the lines are drawn at: the first one [target] metres or more apart. */
    fun metricStep(target: Double): Double = METRIC_STEPS.firstOrNull { it >= target } ?: METRIC_STEPS.last()

    /** A grid value worth drawing: a number, and on Earth (a projection's far side gives anything). */
    private fun usable(v: Double) = v.isFinite() && abs(v) < 1e8

    private fun onEarth(p: GeoPoint?): GeoPoint? = p?.takeIf { it.lat.isFinite() && it.lon.isFinite() && abs(it.lat) <= 85.5 }

    /** [proj]'s lines [step] apart over [v] (at most [MAX_LINES] each way around [c]), each with its value. */
    private fun metric(v: View, c: GeoPoint, step: Double, proj: Projection): List<Line> {
        // The extent in the grid of the part the projection reaches: points across it projected.
        val es = ArrayList<Double>()
        val ns = ArrayList<Double>()
        for (i in 0..EXTENT_SAMPLES) for (j in 0..EXTENT_SAMPLES) {
            val p = GeoPoint(v.south + (v.north - v.south) * i / EXTENT_SAMPLES, v.west + (v.east - v.west) * j / EXTENT_SAMPLES)
            if (!proj.reaches(p)) continue
            proj.forward(p)?.takeIf { (e, n) -> usable(e) && usable(n) }?.let { (e, n) -> es += e; ns += n }
        }
        if (es.size < 2) return emptyList()
        var minE = es.min()
        var maxE = es.max()
        var minN = ns.min()
        var maxN = ns.max()
        // At most [MAX_LINES] each way, around the centre (or the middle of what's reached).
        val half = MAX_LINES / 2.0 * step
        val (ce, cn) = proj.forward(c)?.takeIf { (e, n) -> usable(e) && usable(n) && proj.reaches(c) } ?: ((minE + maxE) / 2 to (minN + maxN) / 2)
        minE = max(minE, ce - half)
        maxE = min(maxE, ce + half)
        minN = max(minN, cn - half)
        maxN = min(maxN, cn + half)
        val lines = ArrayList<Line>()
        // Counted, not added up: each line's value from the first (a sum could stall far from 0).
        val e0 = ceil(minE / step)
        for (i in 0..((maxE - minE) / step).toInt() + 1) {
            val e = (e0 + i) * step
            if (e > maxE) break
            val label = km(e, step)
            for (piece in pieces(minN, maxN, { n -> proj.inverse(e, n) }, proj.reaches)) lines += Line(piece, label)
        }
        val n0 = ceil(minN / step)
        for (i in 0..((maxN - minN) / step).toInt() + 1) {
            val n = (n0 + i) * step
            if (n > maxN) break
            val label = km(n, step)
            for (piece in pieces(minE, maxE, { x -> proj.inverse(x, n) }, proj.reaches)) lines += Line(piece, label)
        }
        return lines
    }

    /**
     * A line [at] each value from [from] to [to], in the pieces where it's on Earth and [inside]:
     * each piece ending exactly where it leaves (found by halving between two of its points).
     */
    private fun pieces(from: Double, to: Double, at: (Double) -> GeoPoint?, inside: (GeoPoint) -> Boolean): List<List<GeoPoint>> {
        fun isIn(t: Double): GeoPoint? = onEarth(at(t))?.takeIf(inside)
        val out = ArrayList<List<GeoPoint>>()
        var piece = ArrayList<GeoPoint>()
        var lastT = from
        var lastIn = false
        for (i in 0..SAMPLES) {
            val t = from + (to - from) * i / SAMPLES
            val p = isIn(t)
            if (i > 0 && (p != null) != lastIn) {
                // The edge between the last point and this one.
                var a = lastT
                var b = t
                repeat(EDGE_STEPS) {
                    val m = (a + b) / 2
                    if ((isIn(m) != null) == lastIn) a = m else b = m
                }
                if (lastIn) {
                    isIn(a)?.let { piece += it }
                    if (piece.size >= 2) out += piece
                    piece = ArrayList()
                } else {
                    isIn(b)?.let { piece += it }
                }
            }
            if (p != null) piece += p
            lastT = t
            lastIn = p != null
        }
        if (piece.size >= 2) out += piece
        return out
    }

    /** Halvings that find where a line leaves its grid's area (1/4096 of a sample's length). */
    private const val EDGE_STEPS = 12

    /** Points across a grid's area (this many steps each way) whose projection gives its extent. */
    private const val EXTENT_SAMPLES = 12

    /** Points along a projected grid line (they curve a little in degrees). */
    private const val SAMPLES = 16

    /** A grid value in kilometres, as maps write them: "711", or "711.5" for steps under a kilometre. */
    fun km(v: Double, step: Double): String = when {
        step >= 1_000 -> (v / 1_000).roundToLong().toString()
        step >= 100 -> localFixed(v / 1_000, 1)
        else -> localFixed(v / 1_000, 2)
    }

    // ---- UTM's zones and bands, zoomed out ------------------------------------------------------------

    /** The bands' edges: every 8° from 80°S, the last (X) 12° to 84°N. */
    private val BAND_EDGES = (0..19).map { -80.0 + 8 * it } + 84.0

    private fun utmZones(v: View): Grid {
        val lines = ArrayList<Line>()
        val labels = ArrayList<Label>()
        val south = max(v.south, -80.0)
        val north = min(v.north, 84.0)
        if (north <= south) return Grid(lines, labels)
        val firstZone = floor((v.west + 180) / 6).toInt()
        val zones = ((v.east - v.west) / 6).toInt() + 2
        for (k in 0..zones) {
            val lon = (firstZone + k) * 6.0 - 180
            if (lon > v.east) break
            if (lon >= v.west) lines += Line(listOf(GeoPoint(south, lon), GeoPoint(north, lon)))
        }
        val bands = BAND_EDGES.filter { it in south..north }
        for (lat in bands) lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)))
        val rows = BAND_EDGES.zipWithNext().filter { (s, n) -> n > south && s < north }
        if (rows.size.toLong() * zones > MAX_CELL_LABELS) return Grid(lines, labels)
        for ((s0, n0) in rows) for (k in 0 until zones) {
            val w0 = (firstZone + k) * 6.0 - 180
            val s = max(s0, south)
            val n = min(n0, north)
            val w = max(w0, v.west)
            val e = min(w0 + 6, v.east)
            if (n <= s || e <= w) continue
            val lon = ((w0 + 3 + 540) % 360) - 180
            val zone = (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)
            labels += Label(GeoPoint((s + n) / 2, (w + e) / 2), "$zone${Utm.bandOf((s0 + n0) / 2)}")
        }
        return Grid(lines, labels)
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

    private fun degrees(v: View, step: Double, format: CoordFormat): Grid {
        val lines = ArrayList<Line>()
        // Counted, not added up (see [metric]).
        val lon0 = ceil(v.west / step - 1e-9)
        for (i in 0..((v.east - v.west) / step).toInt() + 1) {
            val lon = (lon0 + i) * step
            if (lon > v.east) break
            lines += Line(listOf(GeoPoint(v.south, lon), GeoPoint(v.north, lon)), angle(lon, step, format, 'E', 'W'))
        }
        val lat0 = ceil(v.south / step - 1e-9)
        for (i in 0..((v.north - v.south) / step).toInt() + 1) {
            val lat = (lat0 + i) * step
            if (lat > v.north) break
            lines += Line(listOf(GeoPoint(lat, v.west), GeoPoint(lat, v.east)), angle(lat, step, format, 'N', 'S'))
        }
        return Grid(lines, emptyList())
    }

    /** A line's value as [format] writes angles: "35.39°", "35°23.5′E", "35°23′30″E". */
    fun angle(value: Double, step: Double, format: CoordFormat, pos: Char, neg: Char): String {
        // A meridian past the date line (a map showing the world's copies) is the one it is.
        val v = if (pos == 'E') ((value + 540) % 360) - 180 else value
        val a = abs(v)
        val hemi = if (v >= 0) pos else neg
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
                fixed(v, decimals) + "°"
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

    /** The cells of [level] over [v], each with its code in the middle of its part there. */
    private fun cells(v: View, levels: List<Level>, level: Int, code: (GeoPoint, Int) -> String): Grid {
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
        // Each cell labelled in the middle of its part the grid covers (so a cell bigger than the
        // view has its code in view too); not when there are too many to read.
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
                    // The code from the cell's own middle (not its part's, which may sit in a finer cell's corner).
                    val cellMid = GeoPoint((lat + size.lat / 2).coerceIn(-89.99, 89.99), lon + size.lon / 2)
                    labels += Label(GeoPoint((s + n) / 2, (w + e) / 2), code(cellMid, level))
                }
            }
        }
        return Grid(lines, labels)
    }
}
