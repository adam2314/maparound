package app.maparound.map.maplibre

import app.maparound.map.PartyMarker
import app.maparound.party.PartyPalette

/**
 * Names of the marker images on the MapLibre map. The platform draws each image from its name
 * when the map first asks for it (iOS: MarkerImages.swift), so labels, emoji and every script are
 * drawn by the system's text engine. Fields are separated by U+001F; colours are ARGB hex.
 *
 * - `place␟<ring colour>␟<emoji>␟<label>`: a saved place, white disc with the emoji, label below;
 * - `party␟<member|sos|meet|radio>␟<colour>␟<label>␟<0|1 stale>␟<icon code>␟<symbol colour>`: a party marker;
 * - `wp␟<number>␟<0 start|1 via|2 end>`: a numbered route waypoint;
 * - `letter␟<text>␟<colour>`: map tool points A and B;
 * - `me`: the own position; `pin`: the dropped pin (its tip is the point).
 *
 * Every image is centred on its point except `pin`.
 */
object MarkerIcons {
    const val SEP = "\u001F"
    const val ME = "me"
    const val PIN = "pin"

    fun place(color: Int, emoji: String, label: String): String =
        listOf("place", argb(color), emoji, label.take(24)).joinToString(SEP)

    /**
     * [icon]: a member's [app.maparound.party.PartyIcon] code (0: their initial); the platform
     * gets its shape from `PartyIcons.svg`. The icon or initial is drawn in [onColor].
     */
    fun party(kind: PartyMarker.Kind, color: Int, label: String, stale: Boolean, icon: Int = 0): String =
        listOf(
            "party", kind.name.lowercase(), argb(color), label.take(18), if (stale) "1" else "0",
            icon.toString(), argb(PartyPalette.onColor(color)),
        ).joinToString(SEP)

    /** [kind]: 0 for the start, 2 for the end, 1 for points in between. */
    fun waypoint(number: Int, kind: Int): String = listOf("wp", number.toString(), kind.toString()).joinToString(SEP)

    fun letter(text: String, color: Int): String = listOf("letter", text, argb(color)).joinToString(SEP)

    fun argb(color: Int): String = (color.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}
