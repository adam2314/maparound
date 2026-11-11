package app.maparound.shared.util

// A small, forgiving XML reader with SAX-style callbacks, for GPX and similar files (Java's SAX
// parser isn't available on iOS). Handles the XML declaration, comments, CDATA, processing
// instructions, a DOCTYPE without an internal subset, the five named entities and numeric
// character references. Namespace prefixes are kept in names (callers strip them if they wish).

class XmlException(message: String) : Exception(message)

interface XmlHandler {
    fun start(name: String, attrs: Map<String, String>) {}
    fun text(text: String) {}
    fun end(name: String) {}
}

object Xml {
    /** Text from raw file bytes: UTF-8 (with or without BOM), UTF-16 with a BOM, or Latin-1 / Windows-1252 if declared. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return utf16(bytes, 2, bigEndian = true)
            if (b0 == 0xFF && b1 == 0xFE) return utf16(bytes, 2, bigEndian = false)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
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

    fun parse(bytes: ByteArray, handler: XmlHandler) = parse(decode(bytes), handler)

    /** @throws XmlException when the text isn't well-formed enough to read. */
    fun parse(xml: String, handler: XmlHandler) {
        val s = xml
        var i = 0
        val n = s.length
        val open = ArrayList<String>()
        var sawRoot = false
        // Text between tags, merged across entities and CDATA, delivered before the next tag.
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                if (open.isNotEmpty()) handler.text(text.toString())
                else if (text.isNotBlank()) throw XmlException("Text outside the root element")
                text.setLength(0)
            }
        }

        if (n > 0 && s[0] == '﻿') i = 1
        while (i < n) {
            val c = s[i]
            if (c != '<') {
                val next = s.indexOf('<', i).let { if (it < 0) n else it }
                appendDecoded(s, i, next, text)
                i = next
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val e = s.indexOf("-->", i + 4)
                    if (e < 0) throw XmlException("Unclosed comment")
                    i = e + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    val e = s.indexOf("]]>", i + 9)
                    if (e < 0) throw XmlException("Unclosed CDATA")
                    text.append(s, i + 9, e)
                    i = e + 3
                }
                s.startsWith("<?", i) -> {
                    val e = s.indexOf("?>", i + 2)
                    if (e < 0) throw XmlException("Unclosed processing instruction")
                    i = e + 2
                }
                s.startsWith("<!", i) -> {
                    // DOCTYPE (with an internal subset in brackets, skipped whole).
                    var j = i + 2
                    var depth = 0
                    while (j < n) {
                        val ch = s[j]
                        if (ch == '[') depth++ else if (ch == ']') depth-- else if (ch == '>' && depth <= 0) break
                        j++
                    }
                    if (j >= n) throw XmlException("Unclosed declaration")
                    i = j + 1
                }
                s.startsWith("</", i) -> {
                    flushText()
                    val e = s.indexOf('>', i + 2)
                    if (e < 0) throw XmlException("Unclosed end tag")
                    val name = s.substring(i + 2, e).trim()
                    if (open.isEmpty() || open.last() != name) throw XmlException("Unexpected </$name>")
                    open.removeAt(open.size - 1)
                    handler.end(name)
                    i = e + 1
                }
                else -> {
                    flushText()
                    i = startTag(s, i + 1, handler, open)
                    sawRoot = true
                }
            }
        }
        flushText()
        if (open.isNotEmpty()) throw XmlException("Unclosed <${open.last()}>")
        if (!sawRoot) throw XmlException("No XML element")
    }

    /** Reads a start tag from its name at [from]; returns the index after it. */
    private fun startTag(s: String, from: Int, handler: XmlHandler, open: MutableList<String>): Int {
        val n = s.length
        var i = from
        while (i < n && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
        val name = s.substring(from, i)
        if (name.isEmpty()) throw XmlException("Bad tag")
        val attrs = LinkedHashMap<String, String>()
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) throw XmlException("Unclosed <$name>")
            if (s[i] == '>') {
                handler.start(name, attrs)
                open += name
                return i + 1
            }
            if (s[i] == '/') {
                if (i + 1 >= n || s[i + 1] != '>') throw XmlException("Bad <$name>")
                handler.start(name, attrs)
                handler.end(name)
                return i + 2
            }
            val a = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
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
