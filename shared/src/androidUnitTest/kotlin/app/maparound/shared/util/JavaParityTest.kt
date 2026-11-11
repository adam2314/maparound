package app.maparound.shared.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

/** The common replacements give exactly what the Java APIs they replace gave on Android. */
class JavaParityTest {
    @Test
    fun fixedMatchesStringFormat() {
        val r = Random(42)
        repeat(200_000) { i ->
            val v = when (i % 5) {
                0 -> r.nextDouble(-180.0, 180.0)
                1 -> (r.nextDouble(-90.0, 90.0) * 1e6).toLong() / 1e6 // typical stored coordinates
                2 -> r.nextDouble(-1.0, 1.0) * 10.0.pow(r.nextInt(-9, 12))
                3 -> r.nextInt(-100_000, 100_000) / 8.0 // exact halves and quarters
                else -> r.nextDouble(0.0, 10_000.0)
            }
            val decimals = r.nextInt(0, 8)
            assertEquals("$v %.${decimals}f", String.format(Locale.ROOT, "%.${decimals}f", v), fixed(v, decimals))
        }
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())

    @Test
    fun urlCodingMatchesJava() {
        val r = Random(7)
        val alphabet = "aZ09 .-*_~!@#$%^&()+=/?:;,'\"<>éßשלוםمرحبا\n\t"
        repeat(5_000) {
            val s = String(CharArray(r.nextInt(0, 30)) { alphabet[r.nextInt(alphabet.length)] })
            val e = URLEncoder.encode(s, "UTF-8")
            assertEquals(e, urlEncode(s))
            assertEquals(URLDecoder.decode(e, "UTF-8"), urlDecode(e))
        }
    }
}
