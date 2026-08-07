package com.lanfps.shared

/**
 * Tiny dependency-free JSON reader.
 *
 * The project is not allowed to pull in a JSON library, and we only need to read
 * a small, well-formed arena file — so this is a compact recursive-descent parser
 * that produces plain Kotlin types:
 *
 *  object -> LinkedHashMap<String, Any?>
 *  array  -> ArrayList<Any?>
 *  number -> Double
 *  string -> String
 *  bool   -> Boolean
 *  null   -> null
 *
 * It works identically on the JVM server and on Android.
 */
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonException("trailing content at index ${p.index}")
        return v
    }

    // ---- typed accessors -------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun asObject(v: Any?): Map<String, Any?> =
        v as? Map<String, Any?> ?: throw JsonException("expected object, got ${v?.javaClass?.simpleName}")

    @Suppress("UNCHECKED_CAST")
    fun asArray(v: Any?): List<Any?> =
        v as? List<Any?> ?: throw JsonException("expected array, got ${v?.javaClass?.simpleName}")

    fun num(v: Any?): Double = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Number -> v.toDouble()
        else -> throw JsonException("expected number, got ${v?.javaClass?.simpleName}")
    }

    fun float(v: Any?): Float = num(v).toFloat()

    fun str(v: Any?): String = v as? String ?: throw JsonException("expected string")

    fun Map<String, Any?>.floatOr(key: String, def: Float): Float =
        this[key]?.let { float(it) } ?: def

    fun Map<String, Any?>.strOr(key: String, def: String): String =
        this[key] as? String ?: def

    // ---- parser ----------------------------------------------------------

    private class Parser(val s: String) {
        var index = 0

        fun atEnd(): Boolean = index >= s.length

        fun skipWhitespace() {
            while (index < s.length) {
                val c = s[index]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++
                } else if (c == '/' && index + 1 < s.length && s[index + 1] == '/') {
                    // Tolerate // line comments so the arena file can be annotated.
                    while (index < s.length && s[index] != '\n') index++
                } else {
                    break
                }
            }
        }

        fun parseValue(): Any? {
            if (atEnd()) throw JsonException("unexpected end of input")
            return when (val c = s[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else throw JsonException("unexpected character '$c' at $index")
                }
            }
        }

        fun parseLiteral(lit: String, value: Any?): Any? {
            if (!s.startsWith(lit, index)) throw JsonException("bad literal at $index")
            index += lit.length
            return value
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++ }
                    '}' -> { index++; return map }
                    else -> throw JsonException("expected ',' or '}' at $index")
                }
            }
        }

        fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return list }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++ }
                    ']' -> { index++; return list }
                    else -> throw JsonException("expected ',' or ']' at $index")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                when (val c = s[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("bad escape")
                        when (val e = s[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && s[index] in '0'..'9') index++
            if (!atEnd() && s[index] == '.') {
                index++
                while (!atEnd() && s[index] in '0'..'9') index++
            }
            if (!atEnd() && (s[index] == 'e' || s[index] == 'E')) {
                index++
                if (!atEnd() && (s[index] == '+' || s[index] == '-')) index++
                while (!atEnd() && s[index] in '0'..'9') index++
            }
            val text = s.substring(start, index)
            return text.toDoubleOrNull() ?: throw JsonException("bad number '$text'")
        }

        fun peek(): Char = if (atEnd()) '\u0000' else s[index]

        fun expect(c: Char) {
            if (atEnd() || s[index] != c) throw JsonException("expected '$c' at $index")
            index++
        }
    }
}
