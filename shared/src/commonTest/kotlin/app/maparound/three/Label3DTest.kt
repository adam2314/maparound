package app.maparound.three

import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

class Label3DTest {
    @Test
    fun readsPlacesPeaksAndHuts() {
        val peak = Label3D.fromTags(46.5, 9.8, mapOf("natural" to "peak", "name" to "Piz Bernina\rname:de\bBernina", "ele" to "4049 m"))!!
        assertEquals(Label3D.Kind.PEAK, peak.kind)
        assertEquals("Piz Bernina", peak.name)
        assertEquals(4049, peak.ele)
        assertEquals("Piz Bernina  4049 m", peak.text)
        assertEquals(Label3D.Kind.HUT, Label3D.fromTags(0.0, 0.0, mapOf("tourism" to "alpine_hut", "name" to "Refugi"))!!.kind)
        assertEquals(Label3D.Kind.VILLAGE, Label3D.fromTags(0.0, 0.0, mapOf("place" to "village", "name" to "Ordino"))!!.kind)
        // Without a name, or something else: nothing.
        assertNull(Label3D.fromTags(0.0, 0.0, mapOf("natural" to "peak", "ele" to "2000")))
        assertNull(Label3D.fromTags(0.0, 0.0, mapOf("amenity" to "bench", "name" to "Bench")))
    }

    @Test
    fun higherPeaksAndBiggerPlacesComeFirst() {
        val big = Label3D(0.0, 0.0, "A", Label3D.Kind.PEAK, 4000)
        val small = Label3D(0.0, 0.0, "B", Label3D.Kind.PEAK, 1200)
        val city = Label3D(0.0, 0.0, "C", Label3D.Kind.CITY)
        val village = Label3D(0.0, 0.0, "D", Label3D.Kind.VILLAGE)
        val sorted = listOf(village, small, big, city).sortedBy { it.priority }
        assertEquals(listOf("C", "A", "B", "D"), sorted.map { it.name })
        assertTrue(big.priority < small.priority)
    }
}
