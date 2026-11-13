package app.maparound.party

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Moped
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.NordicWalking
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.maparound.i18n.T
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// How a party member looks on the map and in the list: an icon (or the name's initial) on a disc
// of their colour. Both travel in the Hello message (see Protocol.kt).

/** Member colours. */
object PartyPalette {
    /**
     * The colours of the first app versions, which sent only an index into this list. Hello
     * still carries the nearest one, for members on those versions.
     */
    val LEGACY = intArrayOf(
        0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
    )

    /** The choices in the colour picker (any other colour can be mixed there too). */
    val PRESETS = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF3949AB.toInt(), 0xFF039BE5.toInt(), 0xFF00897B.toInt(),
        0xFF43A047.toInt(), 0xFF7CB342.toInt(), 0xFFF9A825.toInt(), 0xFFFB8C00.toInt(),
        0xFFF4511E.toInt(), 0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(),
        0xFF5E35B1.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(), 0xFF212121.toInt(),
    )

    fun legacy(index: Int): Int = LEGACY[(index % LEGACY.size + LEGACY.size) % LEGACY.size]

    /** The index of the [LEGACY] colour closest to [argb]. */
    fun nearestLegacy(argb: Int): Int = LEGACY.indices.minBy { i ->
        val c = LEGACY[i]
        val dr = (c shr 16 and 0xFF) - (argb shr 16 and 0xFF)
        val dg = (c shr 8 and 0xFF) - (argb shr 8 and 0xFF)
        val db = (c and 0xFF) - (argb and 0xFF)
        2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    /** White, or dark grey on light colours, for the icon or initial on a disc of [argb]. */
    fun onColor(argb: Int): Int {
        val y = 0.299 * (argb shr 16 and 0xFF) + 0.587 * (argb shr 8 and 0xFF) + 0.114 * (argb and 0xFF)
        return if (y > 170) 0xFF212121.toInt() else 0xFFFFFFFF.toInt()
    }
}

/** What a member shows on their disc. [code] is what goes over the air: never reuse one. */
enum class PartyIcon(val code: Int, val group: Group) {
    LETTER(0, Group.LETTER),
    WALK(1, Group.PERSON),
    HIKE(2, Group.PERSON),
    RUN(3, Group.PERSON),
    NORDIC(4, Group.PERSON),
    SKI(5, Group.PERSON),
    KAYAK(6, Group.PERSON),
    CYCLIST(20, Group.BIKE),
    BICYCLE(21, Group.BIKE),
    EBIKE(22, Group.BIKE),
    MOTORCYCLE(23, Group.BIKE),
    MOPED(24, Group.BIKE),
    CAR(40, Group.VEHICLE),
    OFFROAD(41, Group.VEHICLE),
    VAN(42, Group.VEHICLE),
    TRUCK(43, Group.VEHICLE),
    CARAVAN(44, Group.VEHICLE),
    ;

    /** The icon's name, in the app's language. */
    val label: String
        get() = when (this) {
            LETTER -> T.party.initial
            WALK -> T.party.iconWalker
            HIKE -> T.party.iconHiker
            RUN -> T.party.iconRunner
            NORDIC -> T.party.iconNordicWalker
            SKI -> T.party.iconSkier
            KAYAK -> T.party.iconKayaker
            CYCLIST -> T.party.iconCyclist
            BICYCLE -> T.party.iconBicycle
            EBIKE -> T.party.iconEbike
            MOTORCYCLE -> T.party.iconMotorcycle
            MOPED -> T.party.iconMoped
            CAR -> T.party.iconCar
            OFFROAD -> T.party.iconOffroad
            VAN -> T.party.iconVan
            TRUCK -> T.party.iconTruck
            CARAVAN -> T.party.iconCaravan
        }

    enum class Group {
        LETTER, PERSON, BIKE, VEHICLE;

        /** The group's name, in the app's language. */
        val label: String
            get() = when (this) {
                LETTER -> T.party.initial
                PERSON -> T.party.groupOnFoot
                BIKE -> T.party.groupTwoWheels
                VEHICLE -> T.party.groupVehicles
            }
    }

    companion object {
        /** Codes from newer versions that this one doesn't know show the initial. */
        fun of(code: Int): PartyIcon = entries.firstOrNull { it.code == code } ?: LETTER
    }
}

/** One filled path of an icon, flattened to polygons in its 24×24 box (y down). */
class IconPath(val subpaths: List<FloatArray>, val evenOdd: Boolean)

/**
 * The icons' shapes as plain polygons, so the Compose list, the Android map (Canvas) and the iOS
 * map (Swift, via [svg]) draw exactly the same picture.
 */
object PartyIcons {
    private val cache = HashMap<PartyIcon, List<IconPath>>()

    /** Empty for [PartyIcon.LETTER]. */
    fun paths(icon: PartyIcon): List<IconPath> = cache.getOrPut(icon) {
        when (icon) {
            PartyIcon.LETTER -> emptyList()
            PartyIcon.OFFROAD -> listOf(IconGeometry.flatten(PathParser().parsePathString(OFFROAD).toNodes(), evenOdd = false))
            else -> IconGeometry.of(vector(icon)!!)
        }
    }

    /** The icon for Swift: `N` or `E` (fill rule) then `M x y L x y … Z`, paths separated by `|`. */
    fun svg(code: Int): String = paths(PartyIcon.of(code)).joinToString("|") { p ->
        (if (p.evenOdd) "E " else "N ") + p.subpaths.joinToString(" ") { s ->
            buildString {
                for (k in s.indices step 2) append(if (k == 0) "M " else "L ").append(fmt(s[k])).append(' ').append(fmt(s[k + 1])).append(' ')
                append('Z')
            }
        }
    }

    private fun fmt(v: Float) = ((v * 100).toInt() / 100f).toString()

    fun vector(icon: PartyIcon): ImageVector? = when (icon) {
        PartyIcon.LETTER, PartyIcon.OFFROAD -> null
        PartyIcon.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        PartyIcon.HIKE -> Icons.Filled.Hiking
        PartyIcon.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        PartyIcon.NORDIC -> Icons.Filled.NordicWalking
        PartyIcon.SKI -> Icons.Filled.DownhillSkiing
        PartyIcon.KAYAK -> Icons.Filled.Kayaking
        PartyIcon.CYCLIST -> Icons.AutoMirrored.Filled.DirectionsBike
        PartyIcon.BICYCLE -> Icons.Filled.PedalBike
        PartyIcon.EBIKE -> Icons.Filled.ElectricBike
        PartyIcon.MOTORCYCLE -> Icons.Filled.Motorcycle
        PartyIcon.MOPED -> Icons.Filled.Moped
        PartyIcon.CAR -> Icons.Filled.DirectionsCar
        PartyIcon.VAN -> Icons.Filled.AirportShuttle
        PartyIcon.TRUCK -> Icons.Filled.LocalShipping
        PartyIcon.CARAVAN -> Icons.Filled.RvHookup
    }

    /**
     * A 4×4 from the side (Material has none): boxy body with a cut-out window, big wheels with
     * hubs. Outlines run clockwise and holes the other way, for the non-zero fill rule.
     */
    private const val OFFROAD =
        "M3 15.6 L3 10 L5.6 10 L7.6 5.4 L15.2 5.4 L15.2 10 L20.4 10 Q22 10 22 11.6 L22 15.6 Z " +
            "M8.9 7 L8 9.6 L13.6 9.6 L13.6 7 Z " +
            "M4.2 17 A2.8 2.8 0 1 1 9.8 17 A2.8 2.8 0 1 1 4.2 17 Z " +
            "M5.9 17 A1.1 1.1 0 1 0 8.1 17 A1.1 1.1 0 1 0 5.9 17 Z " +
            "M15.2 17 A2.8 2.8 0 1 1 20.8 17 A2.8 2.8 0 1 1 15.2 17 Z " +
            "M16.9 17 A1.1 1.1 0 1 0 19.1 17 A1.1 1.1 0 1 0 16.9 17 Z"
}

/** Turns vector path commands (lines, Bézier curves, elliptical arcs) into polygons. */
object IconGeometry {
    private const val CURVE_STEPS = 8

    fun of(vector: ImageVector): List<IconPath> {
        val out = ArrayList<IconPath>()
        fun walk(g: VectorGroup) {
            for (child in g) when (child) {
                is VectorGroup -> walk(child)
                is VectorPath -> out += flatten(child.pathData, child.pathFillType == PathFillType.EvenOdd)
            }
        }
        walk(vector.root)
        return out
    }

    fun flatten(nodes: List<PathNode>, evenOdd: Boolean): IconPath {
        val subpaths = ArrayList<FloatArray>()
        var pts = FloatBuilder()
        var x = 0f; var y = 0f // current point
        var sx = 0f; var sy = 0f // start of the subpath
        var cx = 0f; var cy = 0f // last cubic control point
        var qx = 0f; var qy = 0f // last quadratic control point
        var prev: PathNode? = null

        fun finish() {
            if (pts.size >= 6) subpaths += pts.toArray()
            pts = FloatBuilder()
        }
        fun start() { if (pts.size == 0) pts.add(x, y) }
        fun to(nx: Float, ny: Float) { start(); pts.add(nx, ny); x = nx; y = ny }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            start()
            val x0 = x; val y0 = y
            for (i in 1..CURVE_STEPS) {
                val t = i.toFloat() / CURVE_STEPS
                val u = 1 - t
                pts.add(
                    u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                    u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
                )
            }
            x = x3; y = y3; cx = x2; cy = y2
        }
        fun quad(x1: Float, y1: Float, x2: Float, y2: Float) {
            start()
            val x0 = x; val y0 = y
            for (i in 1..CURVE_STEPS) {
                val t = i.toFloat() / CURVE_STEPS
                val u = 1 - t
                pts.add(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2)
            }
            x = x2; y = y2; qx = x1; qy = y1
        }
        fun arc(rx: Float, ry: Float, phiDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
            start()
            arcPoints(x, y, rx, ry, phiDeg, large, sweep, x2, y2) { px, py -> pts.add(px, py) }
            x = x2; y = y2
        }
        val cubicBefore = { p: PathNode? ->
            p is PathNode.CurveTo || p is PathNode.RelativeCurveTo || p is PathNode.ReflectiveCurveTo || p is PathNode.RelativeReflectiveCurveTo
        }
        val quadBefore = { p: PathNode? ->
            p is PathNode.QuadTo || p is PathNode.RelativeQuadTo || p is PathNode.ReflectiveQuadTo || p is PathNode.RelativeReflectiveQuadTo
        }

        for (n in nodes) {
            when (n) {
                is PathNode.MoveTo -> { finish(); x = n.x; y = n.y; sx = x; sy = y; pts.add(x, y) }
                is PathNode.RelativeMoveTo -> { finish(); x += n.dx; y += n.dy; sx = x; sy = y; pts.add(x, y) }
                is PathNode.LineTo -> to(n.x, n.y)
                is PathNode.RelativeLineTo -> to(x + n.dx, y + n.dy)
                is PathNode.HorizontalTo -> to(n.x, y)
                is PathNode.RelativeHorizontalTo -> to(x + n.dx, y)
                is PathNode.VerticalTo -> to(x, n.y)
                is PathNode.RelativeVerticalTo -> to(x, y + n.dy)
                is PathNode.CurveTo -> cubic(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3)
                is PathNode.RelativeCurveTo -> cubic(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2, x + n.dx3, y + n.dy3)
                is PathNode.ReflectiveCurveTo -> {
                    val (rx, ry) = if (cubicBefore(prev)) 2 * x - cx to 2 * y - cy else x to y
                    cubic(rx, ry, n.x1, n.y1, n.x2, n.y2)
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val (rx, ry) = if (cubicBefore(prev)) 2 * x - cx to 2 * y - cy else x to y
                    cubic(rx, ry, x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2)
                }
                is PathNode.QuadTo -> quad(n.x1, n.y1, n.x2, n.y2)
                is PathNode.RelativeQuadTo -> quad(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2)
                is PathNode.ReflectiveQuadTo -> {
                    val (rx, ry) = if (quadBefore(prev)) 2 * x - qx to 2 * y - qy else x to y
                    quad(rx, ry, n.x, n.y)
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val (rx, ry) = if (quadBefore(prev)) 2 * x - qx to 2 * y - qy else x to y
                    quad(rx, ry, x + n.dx, y + n.dy)
                }
                is PathNode.ArcTo -> arc(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, n.isMoreThanHalf, n.isPositiveArc, n.arcStartX, n.arcStartY)
                is PathNode.RelativeArcTo -> arc(
                    n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, n.isMoreThanHalf, n.isPositiveArc, x + n.arcStartDx, y + n.arcStartDy,
                )
                PathNode.Close -> { finish(); x = sx; y = sy }
            }
            prev = n
        }
        finish()
        return IconPath(subpaths, evenOdd)
    }

    /**
     * An elliptical arc from (x1, y1) to (x2, y2) as points (the end point last), the SVG way:
     * [large] picks the longer of the two arcs, [sweep] the one drawn clockwise on screen.
     */
    fun arcPoints(
        x1: Float, y1: Float, rx0: Float, ry0: Float, phiDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float,
        add: (Float, Float) -> Unit,
    ) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0.toDouble())
        var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { add(x2, y2); return }
        val phi = phiDeg * PI / 180
        val cosP = cos(phi)
        val sinP = sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (large == sweep) -1 else 1) * sqrt(max(0.0, num / den))
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val ccx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val ccy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val start = angle(1.0, 0.0, ux, uy)
        var delta = angle(ux, uy, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
        val steps = max(2, ceil(abs(delta) / (PI / 8)).toInt())
        for (i in 1 until steps) {
            val t = start + delta * i / steps
            add(
                (ccx + rx * cos(t) * cosP - ry * sin(t) * sinP).toFloat(),
                (ccy + rx * cos(t) * sinP + ry * sin(t) * cosP).toFloat(),
            )
        }
        add(x2, y2)
    }

    private class FloatBuilder {
        private var a = FloatArray(64)
        var size = 0
            private set
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x
            a[size++] = y
        }
        fun toArray() = a.copyOf(size)
    }
}
