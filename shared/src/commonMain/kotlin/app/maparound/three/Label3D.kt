package app.maparound.three

import app.maparound.map.WorldData

/** A name the 3D view stands up over the terrain: a place, a peak with its height, a hut… */
class Label3D(val lat: Double, val lon: Double, val name: String, val kind: Kind, val ele: Int? = null) {

    /** Kinds, most important first: when labels compete for space, the lower [rank] wins. */
    enum class Kind(val rank: Int) { COUNTRY(0), SEA(1), CITY(2), PEAK(3), TOWN(4), PASS(5), HUT(6), VILLAGE(7), WATER(8), HAMLET(9) }

    /** Lower goes first; higher peaks before lower ones. */
    val priority: Double = kind.rank + if (kind == Kind.PEAK && ele != null) -ele / 10_000.0 else 0.0

    /** The text shown: the name, and a peak's or pass's height. */
    val text: String = if (ele != null && (kind == Kind.PEAK || kind == Kind.PASS)) "$name  $ele m" else name

    companion object {
        /** Zoom from which the offline maps' names are read (the world's below). */
        const val MAP_LABELS_FROM_ZOOM = 9

        /** A label from an OSM point's tags, or null if it isn't one the 3D view shows. */
        fun fromTags(lat: Double, lon: Double, tags: Map<String, String>): Label3D? {
            // mapsforge keeps other languages after a CR: the local name comes first.
            val name = tags["name"]?.substringBefore('\r')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ele = tags["ele"]?.let { e -> Regex("""^\s*(-?[0-9]+)""").find(e)?.groupValues?.get(1)?.toIntOrNull() }
            val kind = when {
                tags["natural"] in setOf("peak", "volcano") -> Kind.PEAK
                tags["mountain_pass"] == "yes" || tags["natural"] == "saddle" -> Kind.PASS
                tags["tourism"] in setOf("alpine_hut", "wilderness_hut") -> Kind.HUT
                tags["place"] == "city" -> Kind.CITY
                tags["place"] == "town" -> Kind.TOWN
                tags["place"] == "village" -> Kind.VILLAGE
                tags["place"] in setOf("hamlet", "isolated_dwelling", "locality") -> Kind.HAMLET
                tags["natural"] == "water" || tags["place"] == "lake" -> Kind.WATER
                else -> return null
            }
            return Label3D(lat, lon, name, kind, ele.takeIf { kind == Kind.PEAK || kind == Kind.PASS || kind == Kind.HUT })
        }

        /** A label of the built-in world (countries, seas, cities, towns, lakes). */
        fun fromWorld(l: WorldData.Label): Label3D? {
            val kind = when (l.kind) {
                WorldData.Kind.COUNTRY -> Kind.COUNTRY
                WorldData.Kind.CAPITAL, WorldData.Kind.CITY -> Kind.CITY
                WorldData.Kind.TOWN -> Kind.TOWN
                WorldData.Kind.OCEAN, WorldData.Kind.SEA -> Kind.SEA
                WorldData.Kind.LAKE -> Kind.WATER
            }
            return Label3D(l.lat / 1e6, l.lon / 1e6, l.name, kind)
        }
    }
}
