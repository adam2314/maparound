package app.maparound.shared.util

import app.maparound.shared.crypto.Gcm
import app.maparound.shared.crypto.aesGcmOpen
import app.maparound.shared.crypto.aesGcmSeal
import app.maparound.shared.crypto.hmacSha256
import app.maparound.shared.crypto.randomBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun fixedFormatsLikeJava() {
        assertEquals("31.776700", fixed(31.7767, 6))
        assertEquals("-74.006000", fixed(-74.006, 6))
        assertEquals("46.602", fixed(46.602, 3))
        assertEquals("0.000", fixed(0.0, 3))
        assertEquals("-0.0", fixed(-0.04, 1)) // Java keeps the sign
        assertEquals("1", fixed(0.5, 0))
        assertEquals("3", fixed(2.5, 0)) // half up, not half even
        assertEquals("0.13", fixed(0.125, 2))
        assertEquals("10.00", fixed(9.9999, 2))
        assertEquals("0.0000001", fixed(1e-7, 7))
        assertEquals("0.0000000", fixed(1e-9, 7))
        assertEquals("10000000000.0", fixed(1e10, 1))
        assertEquals("123456789.1", fixed(123456789.123, 1))
        assertEquals("35.2345000", fixed(35.2345, 7))
        assertEquals("NaN", fixed(Double.NaN, 2))
        assertEquals("05", padded(5, 2))
        assertEquals("01234", padded(1234L, 5))
        assertEquals("ffffffff", hex(-1))
        assertEquals("1a2b", hex(0x1a2bL))
    }

    @Test
    fun urlCodingLikeJava() {
        assertEquals("Ein+Gedi+%26+%22camp%22%2F%C3%A9*-._", urlEncode("Ein Gedi & \"camp\"/é*-._"))
        assertEquals("קבוצה", urlDecode(urlEncode("קבוצה")))
        assertEquals("a b+c", urlDecode("a+b%2Bc"))
        assertFailsWith<IllegalArgumentException> { urlDecode("100%") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%zz") }
    }

    @Test
    fun isoTimes() {
        assertEquals("2023-11-14T22:13:20Z", isoTime(1_700_000_000_000))
        assertEquals("2023-11-14T22:13:20.123Z", isoTime(1_700_000_000_123))
        assertEquals(1_700_000_000_000, parseIsoTime("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_000, parseIsoTime("2023-11-14T23:13:20+01:00"))
        assertEquals(1_700_000_000_500, parseIsoTime(" 2023-11-14T22:13:20.5Z "))
        assertNull(parseIsoTime("yesterday"))
        assertTrue(nowMillis() > 1_700_000_000_000)
    }

    @Test
    fun bytesBothEndians() {
        val w = ByteWriter(2).byte(0x4D).short(-2).int(0x01020304).intLe(0x01020304).longLe(0x1122334455667788)
        val b = w.toByteArray()
        assertEquals("4dfffe01020304040302018877665544332211", b.hex())
        val r = ByteReader(b)
        assertEquals(0x4D, r.u8())
        assertEquals(-2, r.short().toInt())
        assertEquals(0x01020304, r.int())
        val le = ByteReader(b, r.position, littleEndian = true)
        assertEquals(0x01020304, le.int())
        assertEquals(0x1122334455667788, le.long())
        assertEquals(0, le.remaining)
        assertFailsWith<IndexOutOfBoundsException> { le.byte() }
        assertEquals("é·", latin1(byteArrayOf(0xE9.toByte(), 0xB7.toByte())))
    }

    @Test
    fun xmlReader() {
        val events = ArrayList<String>()
        val h = object : XmlHandler {
            override fun start(name: String, attrs: Map<String, String>) {
                events += "<$name${attrs.entries.joinToString("") { " ${it.key}=${it.value}" }}>"
            }
            override fun text(text: String) {
                if (text.isNotBlank()) events += text.trim()
            }
            override fun end(name: String) {
                events += "</$name>"
            }
        }
        Xml.parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE gpx [ <!ENTITY x "y"> ]>
            <!-- a comment with <tags> -->
            <g:gpx xmlns:g="ns" a='1 &amp; 2'>
              <name>Tom &amp; Jerry &lt;3 &#x5D0;&#1489; &unknown;</name>
              <desc><![CDATA[<b>bold</b> & raw]]></desc>
              <wpt lat="1.5" lon="-2"/>
            </g:gpx>""",
            h,
        )
        assertEquals(
            listOf(
                "<g:gpx xmlns:g=ns a=1 & 2>", "<name>", "Tom & Jerry <3 אב &unknown;", "</name>",
                "<desc>", "<b>bold</b> & raw", "</desc>", "<wpt lat=1.5 lon=-2>", "</wpt>", "</g:gpx>",
            ),
            events,
        )
        assertFailsWith<XmlException> { Xml.parse("just some text", h) }
        assertFailsWith<XmlException> { Xml.parse("<a><b></a>", h) }
        assertFailsWith<XmlException> { Xml.parse("<a>", h) }
        assertFailsWith<XmlException> { Xml.parse("", h) }
    }

    @Test
    fun xmlDecodesDeclaredEncodings() {
        val latin = byteArrayOf(*"<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><n>".encodeToByteArray(), 0xE9.toByte(), *"</n>".encodeToByteArray())
        assertTrue(Xml.decode(latin).endsWith("<n>é</n>"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), *"<n>é</n>".encodeToByteArray())
        assertEquals("<n>é</n>", Xml.decode(bom))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '<'.code.toByte(), 0, 'n'.code.toByte(), 0, '>'.code.toByte(), 0)
        assertEquals("<n>", Xml.decode(utf16))
    }

    @Test
    fun unicodeDecomposition() {
        assertEquals("é", nfd("é"))
    }

    // ---- crypto: published AES-GCM test cases (McGrew & Viega 13, 14, 16) and RFC 4231-style HMAC --

    private val k16 = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"
    private val iv16 = "cafebabefacedbaddecaf888"
    private val aad16 = "feedfacedeadbeeffeedfacedeadbeefabaddad2"
    private val p16 = "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    private val c16 = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
        "76fc6ece0f4e1768cddf8853bb2d551b"

    @Test
    fun aesGcmTestVectors() {
        for (seal in listOf(::aesGcmSeal, { k: ByteArray, n: ByteArray, a: ByteArray, p: ByteArray -> Gcm.seal(k, n, a, p) })) {
            assertEquals("530f8afbc74536b9a963b4f1c4cb738b", seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0)).hex())
            assertEquals(
                "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
                seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(16)).hex(),
            )
            assertEquals(c16, seal(hex(k16), hex(iv16), hex(aad16), hex(p16)).hex())
        }
        for (open in listOf(::aesGcmOpen, { k: ByteArray, n: ByteArray, a: ByteArray, c: ByteArray -> Gcm.open(k, n, a, c) })) {
            assertEquals(p16, open(hex(k16), hex(iv16), hex(aad16), hex(c16))?.hex())
            val forged = hex(c16).also { it[3] = (it[3].toInt() xor 1).toByte() }
            assertNull(open(hex(k16), hex(iv16), hex(aad16), forged))
            assertNull(open(hex(k16), hex(iv16), ByteArray(0), hex(c16)))
            assertNull(open(hex(k16), hex(iv16), hex(aad16), ByteArray(5)))
        }
    }

    @Test
    fun gcmRoundTripsAtEveryLength() {
        val key = randomBytes(32)
        for (len in 0..70) {
            val nonce = randomBytes(12)
            val aad = randomBytes(len % 23)
            val plain = randomBytes(len)
            val sealed = aesGcmSeal(key, nonce, aad, plain)
            assertContentEquals(sealed, Gcm.seal(key, nonce, aad, plain))
            assertContentEquals(plain, Gcm.open(key, nonce, aad, sealed))
        }
    }

    @Test
    fun hmacAndRandom() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            hmacSha256("key".encodeToByteArray(), "The quick brown fox jumps over the lazy dog".encodeToByteArray()).hex(),
        )
        assertEquals(32, hmacSha256(ByteArray(32), ByteArray(0)).size)
        val a = randomBytes(32)
        assertFalse(a.contentEquals(randomBytes(32)))
        assertEquals(0, randomBytes(0).size)
    }
}
