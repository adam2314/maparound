package app.maparound.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferRateTest {
    private val mb = 1_000_000L

    @Test
    fun steadyAfterAFewSeconds() {
        val r = TransferRate()
        r.add(0, 0)
        r.add(1000, mb)
        r.add(2000, 2 * mb)
        // Two seconds: not enough to go on yet.
        assertNull(r.perSecond)
        assertNull(r.secondsLeft(10 * mb))
        r.add(3000, 3 * mb)
        assertEquals(mb.toDouble(), r.perSecond)
        assertEquals(7L, r.secondsLeft(10 * mb))
        assertEquals(TransferRate.Estimate(mb.toDouble(), 7), r.estimate(10 * mb))
    }

    @Test
    fun followsTheLastTenSeconds() {
        val r = TransferRate()
        var done = 0L
        for (s in 0..20) { r.add(s * 1000L, done); done += mb }
        // Twice as fast from here: after ten seconds only that counts.
        for (s in 21..31) { done += 2 * mb; r.add(s * 1000L, done) }
        assertEquals(2.0 * mb, r.perSecond!!, 0.15 * mb)
    }

    @Test
    fun noTimeLeftWhileNothingComes() {
        val r = TransferRate()
        for (s in 0..12) r.add(s * 1000L, 5 * mb)
        assertEquals(0.0, r.perSecond)
        assertNull(r.secondsLeft(10 * mb))
    }

    @Test
    fun startingOverIsMeasuredAfresh() {
        val r = TransferRate()
        for (s in 0..5) r.add(s * 1000L, s * mb)
        r.add(6000, 0)
        assertNull(r.perSecond)
        // Done: no time left; the same moment again just updates the amount.
        val d = TransferRate()
        d.add(0, 0)
        d.add(4000, 4 * mb)
        d.add(4000, 5 * mb)
        assertEquals(0L, d.secondsLeft(5 * mb))
    }
}
