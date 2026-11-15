package app.maparound.raster

import app.maparound.i18n.T
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.toDegrees
import app.maparound.shared.util.toRadians
import app.maparound.tools.Ellipsoid
import app.maparound.tools.Itm
import app.maparound.tools.TransverseMercator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: where a GeoTIFF lies (its geo keys: the coordinate system, and the transform from
// pixels to it). Unit-tested.

/**
 * A GeoTIFF map: its [image], the reduced copies of it in the file ([overviews], most detailed
 * first) and where it lies ([calibration]).
 */
class GeoTiff(val file: TiffFile) {
    val image: TiffImage = file.images.first { !it.mask }
    val overviews: List<TiffImage> = file.images
        .filter { it !== image && !it.mask && it.width < image.width && it.samples == image.samples && it.photometric == image.photometric }
        .sortedByDescending { it.width }
    val calibration: Calibration = calibrationOf(image)

    companion object {
        // Tags.
        private const val PIXEL_SCALE = 33550
        private const val TIEPOINTS = 33922
        private const val TRANSFORMATION = 34264
        private const val KEYS = 34735
        private const val KEY_DOUBLES = 34736
        private const val KEY_TEXT = 34737

        // Geo keys.
        private const val MODEL_TYPE = 1024
        private const val RASTER_TYPE = 1025
        private const val CITATION = 1026
        private const val GEOGRAPHIC_TYPE = 2048
        private const val GEOG_CITATION = 2049
        private const val GEOG_DATUM = 2050
        private const val GEOG_ELLIPSOID = 2056
        private const val GEOG_SEMI_MAJOR = 2057
        private const val GEOG_INV_FLATTENING = 2059
        private const val PROJECTED_TYPE = 3072
        private const val PROJ_CITATION = 3073
        private const val PROJECTION = 3074
        private const val PROJ_METHOD = 3075
        private const val LINEAR_UNITS = 3076
        private const val STD_PARALLEL_1 = 3078
        private const val STD_PARALLEL_2 = 3079
        private const val NAT_ORIGIN_LON = 3080
        private const val NAT_ORIGIN_LAT = 3081
        private const val FALSE_EASTING = 3082
        private const val FALSE_NORTHING = 3083
        private const val FALSE_ORIGIN_LON = 3084
        private const val FALSE_ORIGIN_LAT = 3085
        private const val FALSE_ORIGIN_EASTING = 3086
        private const val FALSE_ORIGIN_NORTHING = 3087
        private const val CENTER_LON = 3088
        private const val CENTER_LAT = 3089
        private const val SCALE_AT_NAT_ORIGIN = 3092
        private const val SCALE_AT_CENTER = 3093

        private const val USER_DEFINED = 32767

        /** The geo keys: number → whole number, number(s) or text. */
        internal class Keys(private val values: Map<Int, Any>) {
            fun int(key: Int): Int? = (values[key] as? Int)
            fun double(key: Int): Double? = (values[key] as? DoubleArray)?.firstOrNull() ?: (values[key] as? Int)?.toDouble()
            fun text(key: Int): String? = values[key] as? String
            val all: String get() = values.values.filterIsInstance<String>().joinToString(" ")
        }

        internal fun keys(img: TiffImage): Keys {
            val dir = img.longs(KEYS) ?: return Keys(emptyMap())
            val doubles = img.doubles(KEY_DOUBLES)
            val text = img.text(KEY_TEXT)
            val out = HashMap<Int, Any>()
            val n = dir.getOrNull(3)?.toInt() ?: 0
            for (k in 0 until n) {
                val p = 4 + k * 4
                if (p + 3 >= dir.size) break
                val id = dir[p].toInt()
                val location = dir[p + 1].toInt()
                val count = dir[p + 2].toInt()
                val value = dir[p + 3].toInt()
                when (location) {
                    0 -> out[id] = value
                    KEY_DOUBLES -> doubles?.let { d -> if (value + count <= d.size) out[id] = d.copyOfRange(value, value + count) }
                    KEY_TEXT -> text?.let { t -> if (value + count <= t.length) out[id] = t.substring(value, value + count).trimEnd('|', '\u0000') }
                }
            }
            return Keys(out)
        }

        /** Where the image lies: its projection, and its pixels' corners to the projection's units. */
        fun calibrationOf(img: TiffImage): Calibration {
            val keys = keys(img)
            val projected = (keys.int(MODEL_TYPE) ?: (if (keys.int(PROJECTED_TYPE) != null) 1 else 2)) == 1
            // Feet to metres, for projected maps in feet.
            val unit = if (!projected) 1.0 else when (keys.int(LINEAR_UNITS)) {
                9002 -> 0.3048
                9003 -> 1200.0 / 3937.0
                9005 -> 0.3047972654
                else -> 1.0
            }
            val projection = if (projected) projected(keys, unit) else geographic(keys)
            var a = transform(img) ?: throw TiffException(T.offline.tiffNoPlace)
            if (keys.int(RASTER_TYPE) == 2) {
                // Coordinates of pixel centres: to their corners.
                a = doubleArrayOf(a[0] - (a[1] + a[2]) / 2, a[1], a[2], a[3] - (a[4] + a[5]) / 2, a[4], a[5])
            }
            if (unit != 1.0) a = DoubleArray(6) { a[it] * unit }
            return Calibration(projection, a, keys.text(GEOG_CITATION) ?: "")
        }

        /** pixel → model: X = a0 + a1·px + a2·py, Y = a3 + a4·px + a5·py. */
        private fun transform(img: TiffImage): DoubleArray? {
            img.doubles(TRANSFORMATION)?.takeIf { it.size >= 16 }?.let { m ->
                return doubleArrayOf(m[3], m[0], m[1], m[7], m[4], m[5])
            }
            val ties = img.doubles(TIEPOINTS) ?: return null
            val scale = img.doubles(PIXEL_SCALE)
            if (scale != null && scale.size >= 2 && ties.size >= 6 && scale[0] != 0.0 && scale[1] != 0.0) {
                val (i, j, x, y) = listOf(ties[0], ties[1], ties[3], ties[4])
                return doubleArrayOf(x - i * scale[0], scale[0], 0.0, y + j * scale[1], 0.0, -scale[1])
            }
            // Ground control points alone: the affine transform through them.
            val n = ties.size / 6
            if (n < 3) return null
            return Calibration.fitAffine(
                List(n) { ties[it * 6] }, List(n) { ties[it * 6 + 1] },
                List(n) { doubleArrayOf(ties[it * 6 + 3], ties[it * 6 + 4]) },
            )
        }

        private fun geographic(keys: Keys): MapProjection {
            val datum = Datum.of(keys.int(GEOGRAPHIC_TYPE), keys.int(GEOG_DATUM))
            return if (datum.shift == null) LatLon else object : MapProjection {
                override val name = "Latitude/Longitude (${datum.name})"
                override fun forward(lat: Double, lon: Double): DoubleArray = datum.fromWgs84(lat, lon).let { doubleArrayOf(it.lon, it.lat) }
                override fun inverse(x: Double, y: Double): GeoPoint = datum.toWgs84(y, x)
            }
        }

        private fun projected(keys: Keys, unit: Double): MapProjection {
            val code = keys.int(PROJECTED_TYPE)
            if (code != null && code != USER_DEFINED) {
                epsg(code)?.let { return it }
            }
            // UTM by its projection code (16001…16060 north, 16101…16160 south).
            keys.int(PROJECTION)?.let { p ->
                val datum = Datum.of(keys.int(GEOGRAPHIC_TYPE), keys.int(GEOG_DATUM))
                if (p in 16001..16060) return utm(p - 16000, true, datum)
                if (p in 16101..16160) return utm(p - 16100, false, datum)
            }
            // Defined in the file: by its method and parameters.
            val method = keys.int(PROJ_METHOD)
            if (method != null) userDefined(method, keys, unit)?.let { return it }
            // Last: a UTM zone named in the citation ("WGS 84 / UTM zone 36N").
            Regex("""UTM\s*zone\s*(\d{1,2})\s*([NS])?""", RegexOption.IGNORE_CASE).find(keys.all)?.let { m ->
                val zone = m.groupValues[1].toInt()
                if (zone in 1..60) return utm(zone, !m.groupValues[2].equals("S", true), Datum.WGS84)
            }
            throw TiffException(T.offline.unsupportedProjection(keys.text(PROJ_CITATION) ?: keys.text(CITATION) ?: code?.let { "EPSG:$it" } ?: "?"))
        }

        /** A projection described in the file; its false origin in the file's units ([unit] metres). */
        private fun userDefined(method: Int, k: Keys, unit: Double): MapProjection? {
            val datum = Datum.of(k.int(GEOGRAPHIC_TYPE), k.int(GEOG_DATUM), k.int(GEOG_ELLIPSOID), k.double(GEOG_SEMI_MAJOR), k.double(GEOG_INV_FLATTENING))
            val fe = (k.double(FALSE_EASTING) ?: k.double(FALSE_ORIGIN_EASTING) ?: 0.0) * unit
            val fn = (k.double(FALSE_NORTHING) ?: k.double(FALSE_ORIGIN_NORTHING) ?: 0.0) * unit
            val lon0 = k.double(NAT_ORIGIN_LON) ?: k.double(FALSE_ORIGIN_LON) ?: k.double(CENTER_LON) ?: 0.0
            val lat0 = k.double(NAT_ORIGIN_LAT) ?: k.double(FALSE_ORIGIN_LAT) ?: k.double(CENTER_LAT) ?: 0.0
            val k0 = k.double(SCALE_AT_NAT_ORIGIN) ?: k.double(SCALE_AT_CENTER) ?: 1.0
            return when (method) {
                1 -> transverseMercator("Transverse Mercator", datum, lat0, lon0, k0, fe, fn)
                7 -> mercator("Mercator", datum, lon0, k0, fe, fn)
                8 -> lambert("Lambert Conformal Conic", datum, k.double(STD_PARALLEL_1) ?: lat0, k.double(STD_PARALLEL_2) ?: lat0, lat0, lon0, 1.0, fe, fn)
                9 -> lambert("Lambert Conformal Conic", datum, lat0, lat0, lat0, lon0, k0, fe, fn)
                else -> null
            }
        }

        /** The coordinate systems by EPSG number that maps come in most. */
        fun epsg(code: Int): MapProjection? = when (code) {
            in 32601..32660 -> utm(code - 32600, true, Datum.WGS84)
            in 32701..32760 -> utm(code - 32700, false, Datum.WGS84)
            in 25828..25838 -> utm(code - 25800, true, Datum.WGS84, "ETRS89")
            in 26901..26923 -> utm(code - 26900, true, Datum.WGS84, "NAD83")
            in 26703..26722 -> utm(code - 26700, true, Datum.NAD27, "NAD27")
            in 23028..23038 -> utm(code - 23000, true, Datum.ED50, "ED50")
            in 28348..28358 -> utm(code - 28300, false, Datum.WGS84, "GDA94")
            in 7846..7859 -> utm(code - 7800, false, Datum.WGS84, "GDA2020")
            in 31466..31469 -> transverseMercator("Gauss-Krüger zone ${code - 31464}", Datum.DHDN, 0.0, (code - 31464) * 3.0, 1.0, (code - 31464) * 1e6 + 500_000.0, 0.0)
            in 2391..2394 -> transverseMercator("KKJ zone ${code - 2390}", Datum.KKJ, 0.0, 18.0 + (code - 2390) * 3.0, 1.0, (code - 2390) * 1e6 + 500_000.0, 0.0)
            2039 -> object : MapProjection {
                override val name = "Israeli TM Grid (ITM)"
                override fun forward(lat: Double, lon: Double) = Itm.from(GeoPoint(lat, lon))?.let { doubleArrayOf(it.first, it.second) } ?: doubleArrayOf(Double.NaN, Double.NaN)
                override fun inverse(x: Double, y: Double) = Itm.toPoint(x, y)
            }
            2100 -> transverseMercator("Greek Grid (GGRS87)", Datum.GGRS87, 0.0, 24.0, 0.9996, 500_000.0, 0.0)
            27700 -> transverseMercator("British National Grid", Datum.OSGB36, 49.0, -2.0, 0.9996012717, 400_000.0, -100_000.0)
            29902, 2157 -> if (code == 2157) transverseMercator("Irish Transverse Mercator", Datum.WGS84, 53.5, -8.0, 0.99982, 600_000.0, 750_000.0)
                else transverseMercator("Irish Grid", Datum.IRISH, 53.5, -8.0, 1.000035, 200_000.0, 250_000.0)
            3006 -> transverseMercator("SWEREF99 TM", Datum.WGS84, 0.0, 15.0, 0.9996, 500_000.0, 0.0)
            3067 -> transverseMercator("ETRS-TM35FIN", Datum.WGS84, 0.0, 27.0, 0.9996, 500_000.0, 0.0)
            2180 -> transverseMercator("Poland CS92", Datum.WGS84, 0.0, 19.0, 0.9993, 500_000.0, -5_300_000.0)
            3765 -> transverseMercator("Croatia HTRS96/TM", Datum.WGS84, 0.0, 16.5, 0.9999, 500_000.0, 0.0)
            3794 -> transverseMercator("Slovenia D96/TM", Datum.WGS84, 0.0, 15.0, 0.9999, 500_000.0, -5_000_000.0)
            2193 -> transverseMercator("New Zealand TM", Datum.WGS84, 0.0, 173.0, 0.9996, 1_600_000.0, 10_000_000.0)
            2154 -> lambert("Lambert-93", Datum.WGS84, 49.0, 44.0, 46.5, 3.0, 1.0, 700_000.0, 6_600_000.0)
            3034 -> lambert("ETRS89 Lambert Conic", Datum.WGS84, 35.0, 65.0, 52.0, 10.0, 1.0, 4_000_000.0, 2_800_000.0)
            31370 -> lambert("Belgian Lambert 72", Datum.BELGE72, 51.16666723333333, 49.8333339, 90.0, 4.367486666666666, 1.0, 150_000.013, 5_400_088.438)
            3857, 900913, 3785, 102100, 102113 -> WebMercator
            3395 -> mercator("World Mercator", Datum.WGS84, 0.0, 1.0, 0.0, 0.0)
            4326, 4258, 4269 -> LatLon
            else -> null
        }

        private fun utm(zone: Int, north: Boolean, datum: Datum, system: String? = null) =
            transverseMercator(
                (system?.let { "$it / " } ?: "") + "UTM $zone${if (north) "N" else "S"}",
                datum, 0.0, zone * 6.0 - 183, 0.9996, 500_000.0, if (north) 0.0 else 10_000_000.0,
            )

        private fun transverseMercator(name: String, datum: Datum, lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double): MapProjection {
            val tm = TransverseMercator(datum.ellipsoid, lat0, lon0, k0, fe, fn)
            return projection(name, datum, { lat, lon -> tm.forward(lat, lon).let { doubleArrayOf(it.first, it.second) } }, tm::inverse)
        }

        /** Mercator on the ellipsoid (one standard parallel at the equator, scale [k0]). */
        private fun mercator(name: String, datum: Datum, lon0: Double, k0: Double, fe: Double, fn: Double): MapProjection {
            val el = datum.ellipsoid
            val e = sqrt(el.e2)
            val r = el.a * k0
            return projection(name, datum, { lat, lon ->
                val phi = toRadians(lat)
                val s = sin(phi)
                doubleArrayOf(fe + r * toRadians(lon - lon0), fn + r * ln(tan(PI / 4 + phi / 2) * ((1 - e * s) / (1 + e * s)).pow(e / 2)))
            }) { x, y ->
                val t = exp(-(y - fn) / r)
                var phi = PI / 2 - 2 * atan(t)
                repeat(8) {
                    val s = e * sin(phi)
                    phi = PI / 2 - 2 * atan(t * ((1 - s) / (1 + s)).pow(e / 2))
                }
                GeoPoint(toDegrees(phi), lon0 + toDegrees((x - fe) / r))
            }
        }

        /** Lambert Conformal Conic with two standard parallels (or one, the same twice, scaled by [k0]). */
        private fun lambert(name: String, datum: Datum, lat1: Double, lat2: Double, lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double): MapProjection {
            val el = datum.ellipsoid
            val e = sqrt(el.e2)
            fun m(phi: Double) = cos(phi) / sqrt(1 - el.e2 * sin(phi) * sin(phi))
            fun t(phi: Double): Double {
                if (abs(phi - PI / 2) < 1e-12) return 0.0
                val s = e * sin(phi)
                return tan(PI / 4 - phi / 2) / ((1 - s) / (1 + s)).pow(e / 2)
            }
            val p1 = toRadians(lat1)
            val p2 = toRadians(lat2)
            val n = if (abs(lat1 - lat2) < 1e-10) sin(p1) else (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
            val f = m(p1) / (n * t(p1).pow(n))
            val rho0 = el.a * f * k0 * t(toRadians(lat0)).pow(n)
            return projection(name, datum, { lat, lon ->
                val rho = el.a * f * k0 * t(toRadians(lat)).pow(n)
                val theta = n * toRadians(lon - lon0)
                doubleArrayOf(fe + rho * sin(theta), fn + rho0 - rho * cos(theta))
            }) { x, y ->
                val dx = x - fe
                val dy = rho0 - (y - fn)
                val rho = sign(n) * sqrt(dx * dx + dy * dy)
                val theta = atan2(sign(n) * dx, sign(n) * dy)
                val tt = (rho / (el.a * f * k0)).pow(1 / n)
                var phi = PI / 2 - 2 * atan(tt)
                repeat(10) {
                    val s = e * sin(phi)
                    phi = PI / 2 - 2 * atan(tt * ((1 - s) / (1 + s)).pow(e / 2))
                }
                GeoPoint(toDegrees(phi), lon0 + toDegrees(theta / n))
            }
        }

        /** A projection on [datum]'s ellipsoid, taking and giving WGS 84 (shifted when the datum differs). */
        private fun projection(name: String, datum: Datum, forward: (Double, Double) -> DoubleArray, inverse: (Double, Double) -> GeoPoint) =
            object : MapProjection {
                override val name = name
                override fun forward(lat: Double, lon: Double): DoubleArray = datum.fromWgs84(lat, lon).let { forward(it.lat, it.lon) }
                override fun inverse(x: Double, y: Double): GeoPoint = inverse(x, y).let { datum.toWgs84(it.lat, it.lon) }
            }

        private val LatLon = object : MapProjection {
            override val name = "Latitude/Longitude"
            override fun forward(lat: Double, lon: Double) = doubleArrayOf(lon, lat)
            override fun inverse(x: Double, y: Double) = GeoPoint(y, x)
        }

        /** Spherical ("Web") Mercator, as the online maps. */
        private val WebMercator = object : MapProjection {
            private val r = 6_378_137.0
            override val name = "Web Mercator"
            override fun forward(lat: Double, lon: Double) =
                doubleArrayOf(r * toRadians(lon), r * ln(tan(PI / 4 + toRadians(lat.coerceIn(-85.06, 85.06)) / 2)))
            override fun inverse(x: Double, y: Double) = GeoPoint(toDegrees(2 * atan(exp(y / r)) - PI / 2), toDegrees(x / r))
        }
    }
}

/**
 * A geodetic datum: its ellipsoid and the shift of its centre from WGS 84 (three parameters: good
 * to a few metres, plenty for a map image). ETRS89, NAD83, GDA and the like are WGS 84 here.
 */
internal class Datum(val name: String, val ellipsoid: Ellipsoid, val shift: DoubleArray?) {
    fun toWgs84(lat: Double, lon: Double): GeoPoint {
        val s = shift ?: return GeoPoint(lat, lon)
        val (x, y, z) = ellipsoid.toEcef(lat, lon)
        return Ellipsoid.WGS84.fromEcef(x + s[0], y + s[1], z + s[2])
    }

    fun fromWgs84(lat: Double, lon: Double): GeoPoint {
        val s = shift ?: return GeoPoint(lat, lon)
        val (x, y, z) = Ellipsoid.WGS84.toEcef(lat, lon)
        return ellipsoid.fromEcef(x - s[0], y - s[1], z - s[2])
    }

    companion object {
        private val BESSEL = Ellipsoid(6_377_397.155, 299.1528128)
        private val INTERNATIONAL = Ellipsoid(6_378_388.0, 297.0)
        private val AIRY = Ellipsoid(6_377_563.396, 299.3249646)
        private val AIRY_MODIFIED = Ellipsoid(6_377_340.189, 299.3249646)
        private val CLARKE_1866 = Ellipsoid(6_378_206.4, 294.9786982)
        private val KRASSOWSKY = Ellipsoid(6_378_245.0, 298.3)

        val WGS84 = Datum("WGS 84", Ellipsoid.WGS84, null)
        val GGRS87 = Datum("GGRS87", Ellipsoid.GRS80, doubleArrayOf(-199.87, 74.79, 246.62))
        val OSGB36 = Datum("OSGB36", AIRY, doubleArrayOf(371.0, -112.0, 434.0))
        val IRISH = Datum("TM65", AIRY_MODIFIED, doubleArrayOf(482.5, -130.6, 564.6))
        val DHDN = Datum("DHDN", BESSEL, doubleArrayOf(598.1, 73.7, 418.2))
        val ED50 = Datum("ED50", INTERNATIONAL, doubleArrayOf(-87.0, -98.0, -121.0))
        val NAD27 = Datum("NAD27", CLARKE_1866, doubleArrayOf(-8.0, 160.0, 176.0))
        val KKJ = Datum("KKJ", INTERNATIONAL, doubleArrayOf(-96.062, -82.428, -121.753))
        val BELGE72 = Datum("Belge 1972", INTERNATIONAL, doubleArrayOf(-106.869, 52.2978, -103.724))
        val PULKOVO = Datum("Pulkovo 1942", KRASSOWSKY, doubleArrayOf(25.0, -141.0, -78.0))

        /** By the geo keys: a geographic system or datum by EPSG number, or an ellipsoid. */
        fun of(geographic: Int?, datum: Int?, ellipsoid: Int? = null, semiMajor: Double? = null, inverseFlattening: Double? = null): Datum {
            when (geographic ?: datum?.let { it - 2000 }) {
                4121 -> return GGRS87
                4277 -> return OSGB36
                4299 -> return IRISH
                4314 -> return DHDN
                4230 -> return ED50
                4267 -> return NAD27
                4123 -> return KKJ
                4313 -> return BELGE72
                4284 -> return PULKOVO
            }
            val el = when (ellipsoid) {
                7004 -> BESSEL
                7022 -> INTERNATIONAL
                7001 -> AIRY
                7008 -> CLARKE_1866
                7024 -> KRASSOWSKY
                7019 -> Ellipsoid.GRS80
                else -> if (semiMajor != null && inverseFlattening != null && semiMajor > 6e6 && inverseFlattening > 250) Ellipsoid(semiMajor, inverseFlattening) else null
            }
            return if (el == null) WGS84 else Datum("ellipsoid $ellipsoid", el, null)
        }
    }
}
