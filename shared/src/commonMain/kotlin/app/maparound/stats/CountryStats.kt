package app.maparound.stats

/** A country's figure: its value and the year it's for. */
data class Figure(val value: Double, val year: Int)

/**
 * The countries' figures from Our World in Data (CC BY 4.0; their sources' terms too): one chart's
 * CSV per [CountryIndicator], every year; the latest year with a value is kept, projections left out.
 */
object CountryStats {
    fun url(indicator: CountryIndicator) =
        "https://ourworldindata.org/grapher/${indicator.slug}.csv?v=1&csvType=full&useColumnShortNames=true"

    /**
     * The latest figure per country (ISO 3166 alpha-3 code) from a chart's CSV
     * (`entity,code,year,<value>[,<more>]`), up to [lastYear]. Regions (no code, or OWID_…) are left out.
     */
    fun parse(csv: String, lastYear: Int): Map<String, Figure> {
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) return emptyMap()
        val header = splitCsv(lines.next())
        val codeAt = header.indexOf("code")
        val yearAt = header.indexOf("year")
        // The value: the first column after the year (the estimates, before any projection's).
        val valueAt = if (yearAt >= 0) yearAt + 1 else -1
        if (codeAt < 0 || yearAt < 0 || valueAt >= header.size) return emptyMap()
        val out = HashMap<String, Figure>()
        for (line in lines) {
            if (line.isBlank()) continue
            val f = splitCsv(line)
            val code = f.getOrNull(codeAt).orEmpty()
            if (code.length != 3) continue
            val year = f.getOrNull(yearAt)?.toIntOrNull() ?: continue
            if (year > lastYear) continue
            val value = f.getOrNull(valueAt)?.toDoubleOrNull() ?: continue
            val old = out[code]
            if (old == null || year > old.year) out[code] = Figure(value, year)
        }
        return out
    }

    /** One CSV line's fields ("…" quoted when they hold commas). */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
