package app.maparound.party

import app.maparound.i18n.T

/** A party event as a notification (Android, the web): its title, its text, and whether it's urgent (an SOS). */
data class PartyNotice(val title: String, val text: String, val urgent: Boolean)

/**
 * What [ev] says as a notification, or null when it says nothing (joins, leaves, an SOS ended).
 * [several]: in more than one party, so the party's name goes after the title.
 */
fun partyNotice(ev: PartyEvent, several: Boolean): PartyNotice? {
    val of = ev.party?.name?.takeIf { several }?.let { " · $it" }.orEmpty()
    return when (ev) {
        is PartyEvent.SosRaised -> PartyNotice(T.party.sosNotificationTitle(ev.member.displayName) + of, ev.text.ifBlank { T.party.sosNeedsHelp }, true)
        is PartyEvent.TextReceived -> PartyNotice(ev.member.displayName + of, ev.text, false)
        is PartyEvent.MeetingSet -> ev.meeting?.let { PartyNotice(T.party.newMeetingPoint + of, it.label.ifBlank { T.party.tapToSeeOnMap }, false) }
        else -> null
    }
}
