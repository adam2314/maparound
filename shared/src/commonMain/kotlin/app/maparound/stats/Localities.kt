package app.maparound.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The age groups of a locality's residents: 0–5, 6–18, 19–45, 46–55, 56–64, 65 and over. */
const val AGE_GROUPS = 6

/** One of Israel's localities, as the Central Bureau of Statistics lists it, with what else is known of it. */
data class Locality(
    /** The CBS's locality code. */
    val code: Int,
    val nameHe: String,
    val nameEn: String,
    val lat: Double,
    val lon: Double,
    val population: Int,
    /** Arabs among them (the rest: Jews and others), when known. */
    val arabs: Int?,
    val district: String,
    /** The CBS's form code: 120–193 Jewish towns by size, 250–290 Arab towns, 310 moshav… */
    val form: Int,
    /** 1 Jewish, 2 Arab, 3 Bedouin tribe, 4 mixed; 0 unknown. */
    val religion: Int,
    val founded: Int?,
    val altitudeM: Int?,
    /** The socio-economic cluster, 1 (lowest) to 10, when known. */
    val cluster: Int?,
    /** Residents by [AGE_GROUPS] (the Population Authority's registry), when known. */
    val ages: List<Int>?,
) {
    val agesTotal: Int get() = ages?.sum() ?: 0
}

/**
 * Israel's localities from the open data portal (data.gov.il, a CKAN site): the CBS's list of
 * localities, the Population Authority's residents by age group, and the socio-economic clusters.
 */
object IsraelLocalities {
    const val API = "https://data.gov.il/api/3/action/"

    /** The datasets; their newest table is the first one with data. */
    const val LOCALITIES = "localities-in-israel"
    const val AGES = "residents_in_israel_by_communities_and_age_groups"
    const val CLUSTERS = "social_economic_cluster"

    fun packageUrl(dataset: String) = "${API}package_show?id=$dataset"

    fun tableUrl(resourceId: String) = "${API}datastore_search?resource_id=$resourceId&limit=5000"

    private val json = Json { ignoreUnknownKeys = true }

    /** The newest table of a dataset ([packageUrl]'s answer): its resource id, or null. */
    fun newestTable(packageJson: String): String? = runCatching {
        json.parseToJsonElement(packageJson).jsonObject["result"]!!.jsonObject["resources"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["datastore_active"]?.jsonPrimitive?.contentOrNull == "true" }
            ?.get("id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** The rows of a table ([tableUrl]'s answer). */
    fun records(tableJson: String): List<JsonObject> = runCatching {
        json.parseToJsonElement(tableJson).jsonObject["result"]!!.jsonObject["records"]!!.jsonArray.map { it.jsonObject }
    }.getOrDefault(emptyList())

    /**
     * The localities with people and a place, from the CBS's rows, joined with the age groups'
     * and the clusters' rows by locality code.
     */
    fun parse(localities: List<JsonObject>, ages: List<JsonObject>, clusters: List<JsonObject>): List<Locality> {
        val agesBy = HashMap<Int, List<Int>>()
        for (r in ages) {
            val code = r.int("סמל_ישוב") ?: continue
            // The first age group is 0–5 in some months' tables, 0–6 in others.
            val groups = listOf(
                r.int("גיל_0_5") ?: r.int("גיל_0_6"), r.int("גיל_6_18"), r.int("גיל_19_45"),
                r.int("גיל_46_55"), r.int("גיל_56_64"), r.int("גיל_65_פלוס"),
            )
            if (groups.all { it != null }) agesBy[code] = groups.map { it!! }
        }
        val clusterBy = HashMap<Int, Int>()
        for (r in clusters) {
            val code = r.int("LOCALITY SYMBOL") ?: continue
            val c = r.keys.firstOrNull { it.startsWith("ESHKOL") }?.let { r.int(it) } ?: continue
            if (c in 1..10) clusterBy[code] = c
        }
        return localities.mapNotNull { r ->
            val code = r.int("סמל יישוב") ?: return@mapNotNull null
            val population = r.intStarting("סך הכל אוכלוסייה")?.takeIf { it > 0 } ?: return@mapNotNull null
            val (lat, lon) = r.long("קואורדינטות")?.let(Itm::fromCbs) ?: return@mapNotNull null
            Locality(
                code = code,
                nameHe = r.text("שם יישוב").trim(),
                nameEn = r.text("שם יישוב באנגלית").trim().ifEmpty { r.text("תעתיק").trim() },
                lat = lat,
                lon = lon,
                population = population,
                arabs = r.intStarting("ערבים"),
                district = r.text("שם מחוז").trim(),
                form = r.intStarting("צורת יישוב") ?: 0,
                religion = r.int("דת יישוב") ?: 0,
                founded = r.int("שנת ייסוד")?.takeIf { it in 1000..2100 },
                altitudeM = r.intStarting("גובה ממוצע"),
                cluster = clusterBy[code],
                ages = agesBy[code],
            )
        }
    }

    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.text(key: String): String = (value(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long? = (value(key) as? JsonPrimitive)?.contentOrNull?.trim()
        ?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    /** The first field whose name starts so (the CBS puts the year in some: "… 2023 - ארעי"). */
    private fun JsonObject.intStarting(prefix: String): Int? = keys.firstOrNull { it.startsWith(prefix) }?.let { int(it) }
}
