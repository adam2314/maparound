package app.maparound.stats

import app.maparound.i18n.T
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One class of a figure on the map: its legend label and colour ("#rrggbb"). */
data class StatClass(val label: String, val color: String)

/** What colours the countries (Our World in Data's charts). */
enum class CountryIndicator(val slug: String, private val breaks: DoubleArray) {
    POPULATION("population", doubleArrayOf(1e6, 5e6, 20e6, 50e6, 100e6, 300e6)),
    DENSITY("population-density", doubleArrayOf(10.0, 25.0, 50.0, 100.0, 200.0, 500.0)),
    GDP("gdp-per-capita-worldbank", doubleArrayOf(2_000.0, 5_000.0, 10_000.0, 20_000.0, 35_000.0, 50_000.0)),
    LIFE("life-expectancy", doubleArrayOf(60.0, 65.0, 70.0, 75.0, 80.0, 83.0)),
    MEDIAN_AGE("median-age", doubleArrayOf(20.0, 25.0, 30.0, 35.0, 40.0, 45.0));

    val title: String get() = T.stats.countryIndicator(this)

    /** The value as shown ("9.8 million", "412 per km²", "$47,000", "82.7 years"). */
    fun format(v: Double): String = T.stats.countryValue(this, v)

    val classes: List<StatClass>
        get() = (0..breaks.size).map { i ->
            val label = when (i) {
                0 -> T.stats.under(format(breaks[0]))
                breaks.size -> T.stats.andOver(format(breaks.last()))
                else -> T.stats.range(format(breaks[i - 1]), format(breaks[i]))
            }
            StatClass(label, SEQUENTIAL_7[i])
        }

    fun classOf(v: Double): Int = breaks.indexOfFirst { v < it }.let { if (it < 0) breaks.size else it }
}

/** What colours the localities. */
enum class LocalityIndicator {
    SIZE, CHILDREN, YOUNG_ADULTS, ELDERLY, ARABS, TYPE, RELIGION, FOUNDED, ALTITUDE, CLUSTER, DISTRICT;

    val title: String get() = T.stats.localityIndicator(this)

    /** Whether it's from the age groups (the Population Authority's data). */
    val fromAges: Boolean get() = this == CHILDREN || this == YOUNG_ADULTS || this == ELDERLY

    val classes: List<StatClass>
        get() = when (this) {
            SIZE -> seq(listOf("<1,000", "1,000–4,999", "5,000–19,999", "20,000–99,999", "100,000+").map(T.stats::people))
            CHILDREN -> seq(pct(25, 30, 35, 45))
            YOUNG_ADULTS -> seq(pct(30, 35, 40, 45))
            ELDERLY -> seq(pct(8, 12, 16, 20))
            ARABS -> seq(pct(10, 50, 90), SEQUENTIAL_4)
            TYPE -> cat(T.stats.localityTypes)
            RELIGION -> cat(T.stats.religions)
            FOUNDED -> seq(listOf(T.stats.before(1948), "1948–1966", "1967–1989", T.stats.since(1990)), SEQUENTIAL_4)
            ALTITUDE -> seq(listOf(T.stats.belowSeaLevel, "0–199 m", "200–499 m", "500–799 m", T.stats.andOver("800 m")).map { ltrM(it) })
            CLUSTER -> listOf("1–2", "3–4", "5–6", "7–8", "9–10").zip(DIVERGING_5) { l, c -> StatClass(l, c) }
            DISTRICT -> cat(DISTRICTS.map(T.stats::district))
        }

    /** The class of [l], or null when it isn't known. */
    fun classOf(l: Locality): Int? = when (this) {
        SIZE -> step(l.population.toDouble(), 1_000.0, 5_000.0, 20_000.0, 100_000.0)
        CHILDREN -> share(l) { it[0] + it[1] }?.let { step(it, 25.0, 30.0, 35.0, 45.0) }
        YOUNG_ADULTS -> share(l) { it[2] }?.let { step(it, 30.0, 35.0, 40.0, 45.0) }
        ELDERLY -> share(l) { it[5] }?.let { step(it, 8.0, 12.0, 16.0, 20.0) }
        ARABS -> l.arabs?.let { step(100.0 * it / l.population, 10.0, 50.0, 90.0) } ?: (if (l.religion == 1) 0 else null)
        TYPE -> typeOf(l.form)
        RELIGION -> (l.religion - 1).takeIf { it in 0..3 }
        FOUNDED -> l.founded?.let { step(it.toDouble(), 1948.0, 1967.0, 1990.0) }
        ALTITUDE -> l.altitudeM?.let { step(it.toDouble(), 0.0, 200.0, 500.0, 800.0) }
        CLUSTER -> l.cluster?.let { (it - 1) / 2 }
        DISTRICT -> DISTRICTS.indexOfFirst { l.district.startsWith(it) }.takeIf { it >= 0 }
    }

    companion object {
        /** The CBS's districts, as its names start ("אזור יהודה ושומרון", with or without the second ה). */
        val DISTRICTS = listOf("ירושלים", "הצפון", "חיפה", "המרכז", "תל אביב", "הדרום", "אזור יהודה")

        /** The kind of place by the CBS's form code: [StatsTexts.localityTypes]' order. */
        fun typeOf(form: Int): Int = when (form) {
            in 100..199 -> 0 // Jewish town or city
            in 200..299 -> 1 // Arab town
            310, 320 -> 2 // moshav
            330 -> 3 // kibbutz
            370 -> 4 // community settlement
            350 -> 5 // moshava
            in 400..499 -> 6 // Arab or Bedouin village
            else -> 7 // institution, other
        }

        private fun step(v: Double, vararg breaks: Double): Int = breaks.indexOfFirst { v < it }.let { if (it < 0) breaks.size else it }

        private inline fun share(l: Locality, part: (List<Int>) -> Int): Double? {
            val a = l.ages ?: return null
            val total = a.sum()
            return if (total <= 0) null else 100.0 * part(a) / total
        }

        private fun pct(vararg b: Int): List<String> =
            listOf("<${b[0]}%") + (1 until b.size).map { "${b[it - 1]}–${b[it]}%" } + listOf("${b.last()}%+")

        private fun seq(labels: List<String>, colors: List<String> = SEQUENTIAL_5) = labels.zip(colors) { l, c -> StatClass(l, c) }

        private fun cat(labels: List<String>) = labels.mapIndexed { i, l -> StatClass(l, CATEGORICAL[i % CATEGORICAL.size]) }

        /** Numbers and units read left to right in a right-to-left sentence. */
        private fun ltrM(s: String) = app.maparound.i18n.ltr(s)
    }
}

/** Colours (ColorBrewer and Tableau's, colour-blind safe where they can be). */
val SEQUENTIAL_7 = listOf("#ffffcc", "#c7e9b4", "#7fcdbb", "#41b6c4", "#1d91c0", "#225ea8", "#0c2c84")
val SEQUENTIAL_5 = listOf("#fef0d9", "#fdcc8a", "#fc8d59", "#e34a33", "#b30000")
val SEQUENTIAL_4 = listOf("#fdcc8a", "#fc8d59", "#e34a33", "#b30000")
val DIVERGING_5 = listOf("#d7191c", "#fdae61", "#ffffbf", "#a6d96a", "#1a9641")
val CATEGORICAL = listOf("#4e79a7", "#f28e2b", "#59a14f", "#e15759", "#76b7b2", "#edc948", "#b07aa1", "#9c755f")
const val NO_DATA = "#9e9e9e"

/** The data view's map layers, as GeoJSON for the map's data sources. */
object StatsLayers {
    /** The tap key of a country ("ISR") or a locality (its code). */
    const val TAP_COUNTRY = "data:c:"
    const val TAP_LOCALITY = "data:l:"

    /** A locality's circle: bigger with more people (by the square root, so areas follow the counts). */
    fun radius(population: Int): Double = (3 + sqrt(population.toDouble()) / 14).coerceIn(3.0, 30.0)

    /** The localities coloured by [indicator], without the classes in [hidden] (unknown: -1). */
    fun localities(all: List<Locality>, indicator: LocalityIndicator, hidden: Set<Int>): String {
        val classes = indicator.classes
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        // Small ones last, so they're drawn on top of the big ones.
        for (l in all.sortedByDescending { it.population }) {
            val c = indicator.classOf(l) ?: -1
            if (c in hidden) continue
            val color = if (c < 0) NO_DATA else classes[c].color
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .append(round6(l.lon)).append(',').append(round6(l.lat))
                .append("""]},"properties":{"tap":"$TAP_LOCALITY${l.code}","color":"$color","r":""")
                .append((radius(l.population) * 10).roundToInt() / 10.0).append("}}")
        }
        return sb.append("]}").toString()
    }

    /**
     * The countries ([outlines]: their features' JSON by ISO code) coloured by [values] of
     * [indicator], without the classes in [hidden] (unknown: -1).
     */
    fun countries(outlines: Map<String, String>, indicator: CountryIndicator, values: Map<String, Figure>, hidden: Set<Int>): String {
        val classes = indicator.classes
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for ((iso, geometry) in outlines) {
            val c = values[iso]?.let { indicator.classOf(it.value) } ?: -1
            if (c in hidden) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":""").append(geometry)
                .append(""","properties":{"tap":"$TAP_COUNTRY$iso","color":"${if (c < 0) NO_DATA else classes[c].color}"}}""")
        }
        return sb.append("]}").toString()
    }

    private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6
}
