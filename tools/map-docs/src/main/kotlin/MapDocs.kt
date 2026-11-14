// The website's map legend and the maps' data schema, in English and Hebrew:
//   docs/map/legend.html, docs/map/schema.html, docs/he/map/legend.html, docs/he/map/schema.html,
//   docs/map/symbols/*.svg
// The legend is the app's own (MapLegend: the style's rules put to sample features), with each
// entry's tags, the zoom it shows from and how it's drawn; the schema is my-pipeline's tag
// mapping (what MapAround's maps keep, from which zoom) and what the pipeline adds itself.
//
// Usage (from the repo root): ./gradlew -p tools/map-docs run --args="--pipeline ../my-pipeline"
//   [--theme app/src/main/assets/themes/topo.xml] [--out docs]

import app.maparound.i18n.I18n
import app.maparound.map.legend.MapLegend
import org.w3c.dom.Comment
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** One `osm-tag` of the tag mapping. [poi]: under `<pois>` (else `<ways>`); [ours]: MapAround's additions. */
data class MappedTag(
    val key: String,
    val value: String,
    val poi: Boolean,
    val zoom: Int,
    val renderable: Boolean,
    val ours: Boolean,
    val equivalents: List<String>,
) {
    fun matches(k: String, v: String) = key == k && (value == "%s" || value == v || v in equivalents)
}

fun readMapping(file: File): List<MappedTag> {
    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file)
    val root = doc.documentElement
    val defaultZoom = root.getAttribute("default-zoom-appear").toIntOrNull() ?: 16
    val out = ArrayList<MappedTag>()
    var ours = false
    val nodes = root.childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n is Comment && "MapAround additions" in n.data) ours = true
        if (n !is Element || (n.tagName != "pois" && n.tagName != "ways")) continue
        val tags = n.getElementsByTagName("osm-tag")
        for (j in 0 until tags.length) {
            val t = tags.item(j) as Element
            out += MappedTag(
                key = t.getAttribute("key"),
                value = t.getAttribute("value"),
                poi = n.tagName == "pois",
                zoom = t.getAttribute("zoom-appear").toIntOrNull() ?: defaultZoom,
                renderable = t.getAttribute("renderable") != "false",
                ours = ours,
                equivalents = t.getAttribute("equivalent-values").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }
    return out
}

// ---- the words around the generated tables --------------------------------------------------------

class Words(val he: Boolean) {
    fun t(en: String, heb: String) = if (he) heb else en

    val lang get() = if (he) "he" else "en"
    val other get() = if (he) "English" else "עברית"
    val legendTitle get() = t("Map legend", "מקרא המפה")
    val schemaTitle get() = t("What the maps contain", "מה יש במפות")
    val guides get() = t("All guides", "כל המדריכים")
    val what get() = t("What", "מה")
    val tags get() = t("OpenStreetMap tags", "תגים ב־OpenStreetMap")
    val fromZoom get() = t("From zoom", "מזום")
    val drawnAs get() = t("Drawn as", "איך מצויר")
    val value get() = t("Value", "ערך")
    val notes get() = t("Notes", "הערות")
    val area get() = t("area", "שטח")
    val pattern get() = t("pattern", "תבנית")
    val outline get() = t("outline", "קו מתאר")
    val line get() = t("line", "קו")
    val dashes get() = t("dashes", "מקווקו")
    val offset get() = t("beside the way", "לצד הדרך")
    val dot get() = t("dot", "נקודה")
    val icon get() = t("icon", "סמל")
    val shading get() = t("hill shading", "הצללת תבליט")
    val notInMaps get() = t("not in MapAround's maps", "לא נשמר במפות של MapAround")
    val fromElevation get() = t("drawn from the elevation data", "מצויר מנתוני הגובה")
    val detail get() = t("kept as a detail of what it's on", "נשמר כפרט של מה שהוא עליו")
    val addition get() = t("MapAround", "MapAround")
    val also get() = t("also", "גם")
    val anyValue get() = t("any (the text)", "כל ערך (הטקסט)")
    val kind get() = t("On", "על")
    val point get() = t("points", "נקודות")
    val lineOrArea get() = t("lines, areas", "קווים, שטחים")
    fun zoomTitle(style: Int?, data: Int?) = listOfNotNull(
        style?.let { t("the style from $it", "הסגנון מזום $it") },
        data?.let { t("the map data from $it", "נתוני המפה מזום $it") },
    ).joinToString(" · ")
}

// ---- what the keys are, for the schema ------------------------------------------------------------

/** The keys worth a word ([en] to [he]); the others link to the OpenStreetMap wiki only. */
val KEYS = mapOf(
    "highway" to ("Roads, tracks and paths, and things along them." to "כבישים, דרכי עפר ושבילים, ומה שלאורכם."),
    "tracktype" to ("How firm a track is: grade1 paved or hard … grade5 soft (sand, grass)." to "עד כמה דרך עפר מהודקת: grade1 סלולה או קשה … grade5 רכה (חול, עשב)."),
    "sac_scale" to ("How hard a path is to walk (the Swiss Alpine Club's scale)." to "דרגת הקושי של שביל הליכה (הסולם של מועדון האלפים השוויצרי)."),
    "mtb:scale" to ("How hard a path is on a mountain bike." to "דרגת הקושי של שביל לאופני הרים."),
    "trail_visibility" to ("How well a path can be seen on the ground." to "עד כמה השביל נראה בשטח."),
    "surface" to ("What the way is made of: asphalt, gravel, dirt, sand…" to "ממה עשויה הדרך: אספלט, חצץ, עפר, חול…"),
    "smoothness" to ("How smooth a road or track is to drive." to "עד כמה הדרך חלקה לנסיעה."),
    "4wd_only" to ("Only for four-wheel drive." to "רק לרכב 4×4."),
    "natural" to ("Landforms, water and land cover: peaks, cliffs, springs, woods, sand…" to "תבליט, מים וכיסוי קרקע: פסגות, מצוקים, מעיינות, יערות, חול…"),
    "waterway" to ("Rivers, streams, ditches and waterfalls." to "נהרות, נחלים, תעלות ומפלים."),
    "landuse" to ("What the land is used for: forest, farmland, homes…" to "שימושי קרקע: יער, חקלאות, מגורים…"),
    "leisure" to ("Parks, nature reserves, picnic tables, fire pits…" to "פארקים, שמורות טבע, שולחנות פיקניק, מדורות…"),
    "tourism" to ("Huts, camp sites, viewpoints, hotels…" to "בקתות, חניוני לילה, תצפיות, מלונות…"),
    "amenity" to ("Services: drinking water, shelters, parking, fuel…" to "שירותים: מי שתייה, מחסות, חניה, דלק…"),
    "man_made" to ("Built things: cairns, wells, towers, embankments…" to "דברים בנויים: גלעדים, בארות, מגדלים, סוללות…"),
    "historic" to ("Ruins, archaeological sites, castles, wayside shrines…" to "חורבות, אתרים ארכאולוגיים, מבצרים, מקדשי דרכים…"),
    "boundary" to ("Borders and protected areas." to "גבולות ושטחים מוגנים."),
    "admin_level" to ("Which border: 2 is a country's." to "איזה גבול: 2 הוא גבול מדינה."),
    "place" to ("Places by size, for their names: cities, towns, villages, farms…" to "מקומות לפי גודל, לשמותיהם: ערים, עיירות, כפרים, חוות…"),
    "railway" to ("Railways and their stations." to "מסילות ברזל ותחנותיהן."),
    "aerialway" to ("Cable cars, chair lifts and ski lifts." to "רכבלים, מעליות כיסא ומעליות סקי."),
    "barrier" to ("Walls, fences, gates, stiles, cattle grids…" to "חומות, גדרות, שערים, מדרגות מעבר, מחסומי בקר…"),
    "ford" to ("Where a way crosses water without a bridge." to "איפה שדרך חוצה מים בלי גשר."),
    "building" to ("Buildings." to "מבנים."),
    "sport" to ("Sports: climbing and others." to "ספורט: טיפוס ועוד."),
    "shop" to ("Shops." to "חנויות."),
    "information" to ("Guideposts, boards and maps along the way." to "שלטי הכוונה, לוחות ומפות לאורך הדרך."),
    "drinking_water" to ("Whether the water is drinkable." to "האם המים ראויים לשתייה."),
    "wikipedia" to ("The place's Wikipedia article, for the app's link to it." to "הערך של המקום בוויקיפדיה, לקישור באפליקציה."),
    "wikidata" to ("The place's Wikidata item, for the app's link to its article." to "הפריט של המקום בוויקינתונים, לקישור באפליקציה לערך."),
    "trail" to ("A marked hiking route's line (made by MapAround, below)." to "קו של מסלול הליכה מסומן (נבנה ב־MapAround, למטה)."),
    "route" to ("Routes (ferries and others)." to "מסלולים (מעבורות ועוד)."),
    "power" to ("Power lines and towers." to "קווי חשמל ועמודים."),
    "access" to ("Who may use the way." to "מי רשאי להשתמש בדרך."),
    "bridge" to ("Bridges." to "גשרים."),
    "tunnel" to ("Tunnels." to "מנהרות."),
    "wetland" to ("Kinds of wetland." to "סוגי ביצות."),
    "wood" to ("Kinds of wood." to "סוגי יער."),
    "religion" to ("A place of worship's religion." to "הדת של מקום התפילה."),
)

// ---- HTML -----------------------------------------------------------------------------------------

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)
fun alpha(argb: Long): Double = ((argb ushr 24) and 0xFF) / 255.0
fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(Locale.ROOT, "%.1f", f)
fun anchor(key: String) = "k-" + key.replace(Regex("[^A-Za-z0-9_-]"), "-")

/** A site URL through Jekyll, so it works under the site's base path. */
fun url(path: String) = "{{ '$path' | relative_url }}"

private const val SAMPLE_W = 72
private const val SAMPLE_H = 28
private const val PX = 1.6 // the theme's pixels, as the app's legend draws them (LegendUi)

/** The sample as the app draws it (LegendUi.LegendSample), as SVG. */
fun sampleSvg(s: MapLegend.Sample, W: Int = SAMPLE_W, H: Int = SAMPLE_H): String {
    val icon = minOf(20, H - 4)
    val b = StringBuilder("<svg class=\"sample\" width=\"$W\" height=\"$H\" viewBox=\"0 0 $W $H\" aria-hidden=\"true\">")
    if (s.hillshade) {
        b.append("<defs><linearGradient id=\"hs$W\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#F7F3EA\"/><stop offset=\"1\" stop-color=\"#8A7E6C\"/></linearGradient></defs>")
        b.append("<rect width=\"$W\" height=\"$H\" rx=\"4\" fill=\"url(#hs$W)\"/></svg>")
        return b.toString()
    }
    b.append("<rect width=\"$W\" height=\"$H\" rx=\"4\" fill=\"#F4F1E8\"/>")
    for (a in s.areas) {
        a.fill?.let { b.append("<rect width=\"$W\" height=\"$H\" rx=\"4\" fill=\"${hex(it)}\" fill-opacity=\"${alpha(it)}\"/>") }
        if (a.stroke != null && a.strokeWidth > 0f) {
            val w = (a.strokeWidth * PX).coerceIn(1.0, 4.0)
            b.append("<rect x=\"${w / 2}\" y=\"${w / 2}\" width=\"${W - w}\" height=\"${H - w}\" rx=\"4\" fill=\"none\" stroke=\"${hex(a.stroke!!)}\" stroke-opacity=\"${alpha(a.stroke!!)}\" stroke-width=\"$w\"/>")
        }
    }
    for (l in s.lines) {
        val w = (l.width * PX).coerceIn(0.8, H * 0.7)
        val y = H / 2.0 + l.offset * PX
        val dash = l.dash?.joinToString(",") { String.format(Locale.ROOT, "%.1f", (it * PX).coerceAtLeast(1.0)) }
        b.append("<line x1=\"4\" y1=\"$y\" x2=\"${W - 4}\" y2=\"$y\" stroke=\"${hex(l.color)}\" stroke-opacity=\"${alpha(l.color)}\" stroke-width=\"$w\"")
        b.append(if (dash != null) " stroke-dasharray=\"$dash\" stroke-linecap=\"butt\"/>" else " stroke-linecap=\"round\"/>")
    }
    for (c in s.circles) {
        val r = (c.radius * PX).coerceIn(2.0, H / 2.0)
        val fill = c.fill?.let { "fill=\"${hex(it)}\" fill-opacity=\"${alpha(it)}\"" } ?: "fill=\"none\""
        val stroke = c.stroke?.takeIf { c.strokeWidth > 0f }?.let { " stroke=\"${hex(it)}\" stroke-width=\"${(c.strokeWidth * PX).coerceAtLeast(1.0)}\"" } ?: ""
        b.append("<circle cx=\"${W / 2}\" cy=\"${H / 2}\" r=\"$r\" $fill$stroke/>")
    }
    s.symbol?.let { b.append("<image href=\"${url("/map/" + it)}\" x=\"${(W - icon) / 2}\" y=\"${(H - icon) / 2}\" width=\"$icon\" height=\"$icon\"/>") }
    b.append("</svg>")
    return b.toString()
}

fun swatch(argb: Long) = "<span class=\"swatch\" style=\"background:${hex(argb)};opacity:${alpha(argb).coerceAtLeast(0.3)}\"></span><code>${hex(argb)}</code>"

/** How it's drawn, in words: areas, lines (widths in the style's pixels), dots, the icon. */
fun drawnAs(s: MapLegend.Sample, w: Words): String {
    if (s.hillshade) return w.shading
    val parts = ArrayList<String>()
    for (a in s.areas) {
        if (a.fill == null && (a.stroke == null || a.strokeWidth <= 0f)) {
            parts += "${w.area}, ${w.pattern}"
            continue
        }
        val p = StringBuilder(w.area)
        a.fill?.let { p.append(" ").append(swatch(it)) }
        if (a.stroke != null && a.strokeWidth > 0f) p.append(", ${w.outline} ").append(swatch(a.stroke!!)).append(" ${fmt(a.strokeWidth)}")
        parts += p.toString()
    }
    for (l in s.lines) {
        val p = StringBuilder("${w.line} ").append(swatch(l.color)).append(" ${fmt(l.width)}")
        l.dash?.let { d -> p.append(", ${w.dashes} ").append(d.joinToString(",") { fmt(it) }) }
        if (l.offset != 0f) p.append(", ${w.offset}")
        parts += p.toString()
    }
    for (c in s.circles) {
        val p = StringBuilder("${w.dot} ${fmt(c.radius)}")
        c.fill?.let { p.append(" ").append(swatch(it)) }
        parts += p.toString()
    }
    s.symbol?.let { parts += "${w.icon} <code>${esc(it.substringAfterLast('/'))}</code>" }
    return parts.joinToString("<br>")
}

fun frontMatter(title: String, w: Words) = buildString {
    append("---\n")
    append("title: \"${title.replace("\"", "\\\"")} · MapAround\"\n")
    append("lang: ${w.lang}\n")
    append("---\n")
    append("<!-- Generated by tools/map-docs (MapDocs.kt): edit the style, the tag mapping or the tool, not this page. -->\n")
}

/** The page's place: the other language, the other page, the guides. */
fun nav(w: Words, page: String) = buildString {
    val otherBase = if (w.he) "/map/" else "/he/map/"
    val guides = if (w.he) "/he/guide/" else "/guide/"
    val sibling = if (page == "legend") "schema" to w.schemaTitle else "legend" to w.legendTitle
    append("<p class=\"doc-nav\"><a href=\"${url(otherBase + page)}\">${w.other}</a> · ")
    append("<a href=\"${url((if (w.he) "/he/map/" else "/map/") + sibling.first)}\">${esc(sibling.second)}</a> · ")
    append("<a href=\"${url(guides)}\">${w.guides}</a></p>\n")
}

// ---- the legend -----------------------------------------------------------------------------------

fun legendPage(sections: List<MapLegend.Section>, mapping: List<MappedTag>, w: Words, source: String): String = buildString {
    append(frontMatter(w.legendTitle, w))
    append(nav(w, "legend"))
    append("<h1>${w.legendTitle}</h1>\n")
    append("<p>").append(w.t(
        "What the lines, areas and symbols on MapAround's offline map mean, as the <strong>Topo</strong> style draws them with all its options on. " +
            "It's the app's own legend (<strong>Layers → Legend</strong>), made from the style's rules, with each feature's OpenStreetMap tags, " +
            "the zoom it shows from (the style's and the map data's, whichever is later) and how it's drawn: colours, widths and dashes in the style's pixels.",
        "מה פירוש הקווים, השטחים והסמלים במפה האופליין של MapAround, כפי שסגנון <strong>Topo</strong> מצייר אותם כשכל האפשרויות שלו פעילות. " +
            "זה המקרא של האפליקציה עצמה (<strong>שכבות ← מקרא</strong>), שנבנה מהכללים של הסגנון, עם התגים של כל פריט ב־OpenStreetMap, " +
            "הזום שממנו הוא מופיע (של הסגנון ושל נתוני המפה, המאוחר מביניהם) ואיך הוא מצויר: צבעים, עוביים וקווקוו בפיקסלים של הסגנון.",
    )).append("</p>\n")
    append("<p class=\"note\">").append(w.t("Made from", "נבנה מ־")).append(" ").append(source).append("</p>\n")
    for (sec in sections) {
        append("<h2>${esc(sec.title)}</h2>\n<div class=\"table-wrap\"><table class=\"legend\">\n")
        append("<thead><tr><th></th><th>${w.what}</th><th>${w.fromZoom}</th></tr></thead><tbody>\n")
        for (e in sec.entries) {
            // Contour lines aren't in the map files: the app draws them from the elevation data.
            val contour = "contour" in e.tags
            val (dataZoom, missing) = if (contour) null to false else dataZoom(e.tags, mapping)
            val zoom = listOfNotNull(e.fromZoom, dataZoom).maxOrNull()
            // The name; under it its tags (to the schema) and how it's drawn, small: three columns fit a phone.
            append("<tr><td>${sampleSvg(e.sample)}</td><td><strong>${esc(e.label)}</strong><div class=\"tags\">")
            append(e.tags.entries.joinToString(" ") { (k, v) -> "<a class=\"tag\" href=\"schema#${anchor(k)}\"><code>${esc(k)}=${esc(v)}</code></a>" })
            append("</div>")
            if (missing) append("<div class=\"note\">${w.notInMaps}</div>")
            if (contour) append("<div class=\"note\">${w.fromElevation}</div>")
            append("<div class=\"drawn\">${drawnAs(e.sample, w)}</div>")
            append("</td><td class=\"zoom\" title=\"${esc(w.zoomTitle(e.fromZoom, dataZoom))}\">${zoom ?: ""}</td></tr>\n")
        }
        append("</tbody></table></div>\n")
    }
}

/**
 * The zoom from which MapAround's maps have a feature with [tags] (the latest of its drawn tags'
 * first zooms), and whether some of its tags aren't kept at all.
 */
fun dataZoom(tags: Map<String, String>, mapping: List<MappedTag>): Pair<Int?, Boolean> {
    var missing = false
    var zoom: Int? = null
    for ((k, v) in tags) {
        val found = mapping.filter { it.matches(k, v) }
        if (found.isEmpty()) {
            missing = true
            continue
        }
        found.filter { it.renderable }.minOfOrNull { it.zoom }?.let { z -> zoom = maxOf(zoom ?: z, z) }
    }
    return zoom to missing
}

// ---- the schema -----------------------------------------------------------------------------------

fun schemaPage(mapping: List<MappedTag>, w: Words, source: String): String = buildString {
    append(frontMatter(w.schemaTitle, w))
    append(nav(w, "schema"))
    append("<h1>${w.schemaTitle}</h1>\n")
    append("<p>").append(w.t(
        "MapAround's own offline maps are OpenStreetMap, built every month by <a href=\"https://github.com/adam2314/my-pipeline\">my-pipeline</a> in the mapsforge format. " +
            "This is what they keep: every tag, and the zoom from which the map has it (zoom levels as the app counts them; a style can draw it from later on). " +
            "Tags marked <span class=\"badge\">MapAround</span> are its additions for the outdoors; the others are mapsforge's standard set. " +
            "What each one means is on the <a href=\"https://wiki.openstreetmap.org/\">OpenStreetMap wiki</a> (the links).",
        "המפות האופליין של MapAround הן OpenStreetMap, שנבנות כל חודש ב־<a href=\"https://github.com/adam2314/my-pipeline\">my-pipeline</a> בפורמט mapsforge. " +
            "זה מה שנשמר בהן: כל תג, והזום שממנו הוא נמצא במפה (רמות הזום כמו שהאפליקציה סופרת אותן; סגנון יכול לצייר אותו מזום מאוחר יותר). " +
            "תגים שמסומנים <span class=\"badge\">MapAround</span> הם התוספות שלה לשטח; האחרים הם הסט הרגיל של mapsforge. " +
            "מה המשמעות של כל אחד כתוב ב<a href=\"https://wiki.openstreetmap.org/\">ויקי של OpenStreetMap</a> (הקישורים).",
    )).append("</p>\n")
    append("<p class=\"note\">").append(w.t("Made from", "נבנה מ־")).append(" ").append(source).append("</p>\n")

    // What the pipeline makes itself, beyond keeping tags.
    append("<h2>${w.t("Made by MapAround", "נבנה ב־MapAround")}</h2>\n<ul>\n")
    append("<li>").append(w.t(
        "<strong>Marked trails.</strong> OpenStreetMap's hiking and foot route relations become lines on their ways: <code>trail=hiking</code> (from zoom 11) with " +
            "<code>trail_colour</code> (the waymark colour from <code>osmc:symbol</code> or <code>colour</code>: red, blue, green, yellow, black, white, orange, purple, brown, other), " +
            "<code>trail_offset</code> (their place side by side when up to four colours share a way) and, on the first, <code>trail_n</code>, <code>name</code> and <code>ref</code>.",
        "<strong>שבילים מסומנים.</strong> יחסי המסלולים של הליכה ורגל ב־OpenStreetMap הופכים לקווים על הדרכים שלהם: <code>trail=hiking</code> (מזום 11) עם " +
            "<code>trail_colour</code> (צבע הסימון מ־<code>osmc:symbol</code> או <code>colour</code>: אדום, כחול, ירוק, צהוב, שחור, לבן, כתום, סגול, חום, אחר), " +
            "<code>trail_offset</code> (המקום שלהם זה לצד זה כשעד ארבעה צבעים חולקים דרך) ובראשון גם <code>trail_n</code>, <code>name</code> ו־<code>ref</code>.",
    )).append("</li>\n<li>").append(w.t(
        "<strong>Country borders.</strong> The lines of the country border relations get <code>boundary=administrative</code> and <code>admin_level=2</code> themselves " +
            "(and <code>disputed=yes</code> on disputed borders), so every map draws them.",
        "<strong>גבולות מדינות.</strong> הקווים של יחסי גבולות המדינות מקבלים בעצמם <code>boundary=administrative</code> ו־<code>admin_level=2</code> " +
            "(ו־<code>disputed=yes</code> בגבולות שנויים במחלוקת), כך שכל מפה מציירת אותם.",
    )).append("</li>\n<li>").append(w.t(
        "<strong>Kept for the app.</strong> <code>smoothness</code> on tracks, and <code>wikipedia</code> and <code>wikidata</code> on places, for the links to their articles.",
        "<strong>נשמר בשביל האפליקציה.</strong> <code>smoothness</code> בדרכי עפר, ו־<code>wikipedia</code> ו־<code>wikidata</code> במקומות, לקישורים לערכים שלהם.",
    )).append("</li>\n</ul>\n")

    // Every tag, by key (one anchor each: the legend links there), with a list of the keys first.
    append("<h2>${w.t("Every tag", "כל התגים")}</h2>\n")
    val byKey = mapping.groupBy { it.key }.toSortedMap()
    append("<p class=\"keys\">")
    append(byKey.keys.joinToString(" ") { k -> "<a href=\"#${anchor(k)}\"><code>${esc(k)}</code></a>" })
    append("</p>\n")
    for ((key, tags) in byKey) {
        append("<h3 id=\"${anchor(key)}\"><a href=\"https://wiki.openstreetmap.org/wiki/Key:${esc(key)}\"><code>${esc(key)}</code></a></h3>\n")
        KEYS[key]?.let { append("<p>${esc(if (w.he) it.second else it.first)}</p>\n") }
        append("<div class=\"table-wrap\"><table class=\"schema\"><thead><tr><th>${w.value}</th><th>${w.fromZoom}</th></tr></thead><tbody>\n")
        for (t in tags.sortedWith(compareBy({ !it.renderable }, { !it.poi }, { it.zoom }, { it.value }))) {
            val value = if (t.value == "%s") w.anyValue else
                "<a href=\"https://wiki.openstreetmap.org/wiki/Tag:${esc(key)}%3D${esc(t.value)}\"><code>${esc(t.value)}</code></a>"
            // Under the value, small: what it's on, and the notes. Two columns fit a phone.
            val notes = listOfNotNull(
                if (t.poi) w.point else w.lineOrArea,
                "<span class=\"badge\">${w.addition}</span>".takeIf { t.ours },
                w.detail.takeIf { !t.renderable },
                t.equivalents.takeIf { it.isNotEmpty() }?.let { "${w.also}: " + it.joinToString(", ") { v -> "<code>${esc(v)}</code>" } },
            ).joinToString(" · ")
            append("<tr><td>$value<div class=\"note\">$notes</div></td><td class=\"zoom\">${if (t.renderable) t.zoom.toString() else ""}</td></tr>\n")
        }
        append("</tbody></table></div>\n")
    }
}

// ---- the run --------------------------------------------------------------------------------------

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val theme = File(opts["theme"] ?: "app/src/main/assets/themes/topo.xml")
    val pipeline = File(opts["pipeline"] ?: error("--pipeline <my-pipeline checkout> is needed (its tag-mapping.xml)"))
    val out = File(opts["out"] ?: "docs")
    val mapping = readMapping(File(pipeline, "tag-mapping.xml"))
    val commit = runCatching {
        ProcessBuilder("git", "-C", pipeline.path, "rev-parse", "--short", "HEAD").start().inputStream.bufferedReader().readText().trim()
    }.getOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{6,}")) }
    val themeXml = theme.readText()

    val symbols = HashSet<String>()
    for (he in listOf(false, true)) {
        val w = Words(he)
        I18n.apply(w.lang)
        val sections = MapLegend.build(themeXml, categories = null, zooms = true)
        sections.forEach { s -> s.entries.forEach { e -> e.sample.symbol?.let(symbols::add) } }
        val dir = File(out, if (he) "he/map" else "map").apply { mkdirs() }
        val style = "<a href=\"https://github.com/adam2314/maparound/blob/main/app/src/main/assets/themes/topo.xml\"><code>topo.xml</code></a>"
        val tagMapping = "<a href=\"https://github.com/adam2314/my-pipeline/blob/${commit ?: "main"}/tag-mapping.xml\">my-pipeline's <code>tag-mapping.xml</code>${commit?.let { " ($it)" } ?: ""}</a>"
        File(dir, "legend.html").writeText(legendPage(sections, mapping, w, "$style · $tagMapping"))
        File(dir, "schema.html").writeText(schemaPage(mapping, w, tagMapping))
        println("${w.lang}: ${sections.sumOf { it.entries.size }} legend entries in ${sections.size} sections, ${mapping.size} tags")
    }

    // The style's icons, from the mapsforge-themes jar as the map reads them.
    val symbolDir = File(out, "map")
    for (s in symbols.sorted()) {
        val bytes = object {}.javaClass.getResourceAsStream("/assets/$s")?.use { it.readBytes() }
        if (bytes == null) {
            println("no symbol $s")
            continue
        }
        File(symbolDir, s).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
    println("${symbols.size} symbols")
}
