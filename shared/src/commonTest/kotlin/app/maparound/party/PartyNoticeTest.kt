package app.maparound.party

import app.maparound.i18n.T
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import kotlin.test.Test

/** A party event as a notification, alone and with several parties (PartyNotice.kt). */
class PartyNoticeTest {
    private val hike = PartyKey.create("Hike", null)
    private val dana = Member(7, "Dana")

    @Test
    fun inOnePartyAsBefore() {
        assertEquals(PartyNotice(T.party.sosNotificationTitle("Dana"), "Ankle", true), partyNotice(PartyEvent.SosRaised(dana, "Ankle", hike), several = false))
        assertEquals(PartyNotice("Dana", "Water at the spring", false), partyNotice(PartyEvent.TextReceived(dana, "Water at the spring", hike), several = false))
    }

    @Test
    fun inSeveralItSaysWhoseItIs() {
        assertEquals("Dana · Hike", partyNotice(PartyEvent.TextReceived(dana, "Hi", hike), several = true)!!.title)
        assertEquals(T.party.newMeetingPoint + " · Hike", partyNotice(PartyEvent.MeetingSet(Meeting(31.0, 35.0, "Car park", 7), hike), several = true)!!.title)
        // Joins, leaves, an SOS ended, a meeting point cleared: nothing.
        assertNull(partyNotice(PartyEvent.Joined(dana, hike), several = true))
        assertNull(partyNotice(PartyEvent.SosCancelled(dana, hike), several = true))
        assertNull(partyNotice(PartyEvent.MeetingSet(null, hike), several = true))
    }
}
