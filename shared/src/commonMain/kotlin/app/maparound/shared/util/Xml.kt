package app.maparound.shared.util

import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

// A small, forgiving, streaming XML reader with SAX-style callbacks, for GPX and Overpass files
// (Java's SAX parser isn't available on iOS). Handles the XML declaration, comments, CDATA,
// processing instructions, a DOCTYPE (internal subset skipped), the five named entities and
// numeric character references. Namespace prefixes are kept in names (callers strip them).

class XmlException(message: String) : Exception(message)

interface XmlHandler {
    fun start(name: String, attrs: Map<String, String>) {}
    fun text(text: String) {}
    fun end(name: String) {}
}

object Xml {
    private val COMMENT = "<!--".encodeUtf8()
    private val COMMENT_END = "-->".encodeUtf8()
    private val CDATA = "<![CDATA[".encodeUtf8()
    private val CDATA_END = "]]>".encodeUtf8()
    private val PROC = "<?".encodeUtf8()
    private val PI_END = "?>".encodeUtf8()
    private val DECL = "<!".encodeUtf8()
    private val END_TAG = "</".encodeUtf8()
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF8_BOM = ByteString.of(*BOM)
    private const val LT = '<'.code.toByte()
    private const val GT = '>'.code.toByte()

    /** Text from raw file bytes: UTF-8 (with or without BOM), UTF-16 with a BOM, or Latin-1 / Windows-1252 if declared. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return utf16(bytes, 2, bigEndian = true)
            if (b0 == 0xFF && b1 == 0xFE) return utf16(bytes, 2, bigEndian = false)
        }
        if (bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) {
            return bytes.decodeToString(3, bytes.size)
        }
        val head = latin1(bytes.copyOf(minOf(bytes.size, 200)))
        val encoding = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(head)?.groupValues?.get(1)?.lowercase()
        return when (encoding) {
            "iso-8859-1", "latin1", "latin-1", "iso8859-1", "windows-1252", "cp1252", "us-ascii", "ascii" -> latin1(bytes)
            else -> bytes.decodeToString()
        }
    }

    private fun utf16(b: ByteArray, from: Int, bigEndian: Boolean): String {
        val n = (b.size - from) / 2
        val chars = CharArray(n) { i ->
            val hi = b[from + 2 * i].toInt() and 0xFF
            val lo = b[from + 2 * i + 1].toInt() and 0xFF
            (if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar()
        }
        return chars.concatToString()
    }

    /** Whole files: any of the encodings [decode] knows. */
    fun parse(bytes: ByteArray, handler: XmlHandler) = parse(decode(bytes), handler)

    fun parse(xml: String, handler: XmlHandler) = parse(Buffer().writeUtf8(xml), handler)

    /**
     * Streams UTF-8 XML from [source] (large downloads never sit in memory whole).
     * @throws XmlException when the text isn't well-formed enough to read.
     */
    fun parse(source: BufferedSource, handler: XmlHandler) {
        val open = ArrayList<String>()
        var sawRoot = false
        // Text between tags, merged across entities and CDATA, delivered before the next tag.
        val text = StringBuilder()

        fun flushText() {
            if (text.isEmpty()) return
            if (open.isNotEmpty()) handler.text(text.toString())
            else if (text.isNotBlank()) throw XmlException("Text outside the root element")
            text.setLength(0)
        }

        if (source.rangeEquals(0, UTF8_BOM)) source.skip(3)
        while (!source.exhausted()) {
            val lt = source.indexOf(LT)
            if (lt != 0L) {
                val raw = if (lt < 0) source.readUtf8() else source.readUtf8(lt)
                appendDecoded(raw, 0, raw.length, text)
                continue
            }
            when {
                source.rangeEquals(0, COMMENT) -> {
                    val e = source.indexOf(COMMENT_END, 4)
                    if (e < 0) throw XmlException("Unclosed comment")
                    source.skip(e + 3)
                }
                source.rangeEquals(0, CDATA) -> {
                    val e = source.indexOf(CDATA_END, 9)
                    if (e < 0) throw XmlException("Unclosed CDATA")
                    source.skip(9)
                    text.append(source.readUtf8(e - 9))
                    source.skip(3)
                }
                source.rangeEquals(0, PROC) -> {
                    val e = source.indexOf(PI_END, 2)
                    if (e < 0) throw XmlException("Unclosed processing instruction")
                    source.skip(e + 2)
                }
                source.rangeEquals(0, DECL) -> skipDeclaration(source)
                source.rangeEquals(0, END_TAG) -> {
                    flushText()
                    val e = source.indexOf(GT)
                    if (e < 0) throw XmlException("Unclosed end tag")
                    val name = source.readUtf8(e).substring(2).trim()
                    source.skip(1)
                    if (open.isEmpty() || open.last() != name) throw XmlException("Unexpected </$name>")
                    open.removeAt(open.size - 1)
                    handler.end(name)
                }
                else -> {
                    flushText()
                    startTag(readTag(source), handler, open)
                    sawRoot = true
                }
            }
        }
        flushText()
        if (open.isNotEmpty()) throw XmlException("Unclosed <${open.last()}>")
        if (!sawRoot) throw XmlException("No XML element")
    }

    /** DOCTYPE and other `<!…>` declarations, with an internal subset in brackets. */
    private fun skipDeclaration(source: BufferedSource) {
        var depth = 0
        source.skip(2)
        while (true) {
            if (source.exhausted()) throw XmlException("Unclosed declaration")
            when (source.readByte().toInt().toChar()) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return
                else -> Unit
            }
        }
    }

    /** The text between `<` and the `>` that ends the tag (a `>` inside quotes doesn't). */
    private fun readTag(source: BufferedSource): String {
        var end = source.indexOf(GT)
        while (true) {
            if (end < 0) throw XmlException("Unclosed tag")
            val candidate = source.peek().readUtf8(end)
            var quote = 0.toChar()
            for (c in candidate) {
                if (quote.code == 0) {
                    if (c == '"' || c == '\'') quote = c
                } else if (c == quote) {
                    quote = 0.toChar()
                }
            }
            if (quote.code == 0) {
                source.skip(end + 1)
                return candidate.substring(1)
            }
            end = source.indexOf(GT, end + 1)
        }
    }

    /** A start tag's text (after `<`): name, attributes, maybe `/` at the end. */
    private fun startTag(s: String, handler: XmlHandler, open: MutableList<String>) {
        val n = s.length
        var i = 0
        while (i < n && !s[i].isWhitespace() && s[i] != '/') i++
        val name = s.substring(0, i)
        if (name.isEmpty()) throw XmlException("Bad tag")
        val attrs = LinkedHashMap<String, String>()
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) {
                handler.start(name, attrs)
                open += name
                return
            }
            if (s[i] == '/') {
                if (s.substring(i + 1).isNotBlank()) throw XmlException("Bad <$name>")
                handler.start(name, attrs)
                handler.end(name)
                return
            }
            val a = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace() && s[i] != '/') i++
            val key = s.substring(a, i)
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') throw XmlException("Attribute without value in <$name>")
            i++
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || (s[i] != '"' && s[i] != '\'')) throw XmlException("Unquoted attribute in <$name>")
            val q = s[i]
            val e = s.indexOf(q, i + 1)
            if (e < 0) throw XmlException("Unclosed attribute in <$name>")
            attrs[key] = StringBuilder().also { appendDecoded(s, i + 1, e, it) }.toString()
            i = e + 1
        }
    }

    /** Appends s[from, to) with entities and character references replaced. */
    private fun appendDecoded(s: String, from: Int, to: Int, out: StringBuilder) {
        var i = from
        while (i < to) {
            val amp = s.indexOf('&', i).let { if (it < 0 || it >= to) to else it }
            out.append(s, i, amp)
            if (amp >= to) return
            val semi = s.indexOf(';', amp)
            if (semi < 0 || semi >= to || semi - amp > 12) {
                out.append('&') // a stray '&': keep it
                i = amp + 1
                continue
            }
            val ent = s.substring(amp + 1, semi)
            val decoded: String? = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
            } else {
                out.append('&')
                i = amp + 1
            }
        }
    }

    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    /** Escapes text for element content and double-quoted attributes. */
    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
