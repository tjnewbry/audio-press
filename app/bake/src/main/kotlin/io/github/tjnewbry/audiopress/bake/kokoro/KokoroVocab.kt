package io.github.tjnewbry.audiopress.bake.kokoro

import java.io.IOException

/**
 * Turns misaki phoneme strings into the token ids Kokoro was trained with. The map comes from the `vocab` object of
 * Kokoro's `config.json` (Apache-2.0): one id per phoneme character. Characters the model does not know are dropped,
 * as Kokoro's own code does.
 */
class KokoroVocab(
    private val ids: Map<Int, Int>,
) {
    /** The ids for [phonemes], without the pad tokens, and how many characters had no id. */
    fun encode(phonemes: String): IntArray {
        val out = ArrayList<Int>(phonemes.length)
        var i = 0
        while (i < phonemes.length) {
            val codePoint = phonemes.codePointAt(i)
            ids[codePoint]?.let { out += it }
            i += Character.charCount(codePoint)
        }
        return out.toIntArray()
    }

    val size: Int get() = ids.size

    companion object {
        /** The id the model uses for padding, placed before and after the phonemes. */
        const val PAD_ID = 0

        /** Reads the `vocab` object of a Kokoro `config.json`. */
        fun fromConfigJson(json: String): KokoroVocab {
            val root = MiniJson(json).parseValue() as? Map<*, *> ?: throw IOException("config.json is not an object")
            val vocab = root["vocab"] as? Map<*, *> ?: throw IOException("config.json has no vocab")
            val ids = HashMap<Int, Int>()
            for ((key, value) in vocab) {
                val symbol = key as String
                if (symbol.codePointCount(0, symbol.length) != 1) throw IOException("vocab key '$symbol' is not one character")
                ids[symbol.codePointAt(0)] = (value as Number).toInt()
            }
            return KokoroVocab(ids)
        }
    }
}

/** A small JSON reader (objects, arrays, strings, numbers, true, false, null), enough for config files. */
internal class MiniJson(
    private val text: String,
) {
    private var pos = 0

    fun parseValue(): Any? {
        skipSpaces()
        val c = text.getOrNull(pos) ?: throw IOException("unexpected end of JSON")
        return when {
            c == '{' -> parseObject()
            c == '[' -> parseArray()
            c == '"' -> parseString()
            text.startsWith("true", pos) -> true.also { pos += 4 }
            text.startsWith("false", pos) -> false.also { pos += 5 }
            text.startsWith("null", pos) -> null.also { pos += 4 }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++
        skipSpaces()
        if (text[pos] == '}') return result.also { pos++ }
        while (true) {
            skipSpaces()
            val key = parseString()
            skipSpaces()
            expect(':')
            result[key] = parseValue()
            skipSpaces()
            when (text.getOrNull(pos++)) {
                ',' -> continue
                '}' -> return result
                else -> throw IOException("bad JSON object at $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val result = ArrayList<Any?>()
        pos++
        skipSpaces()
        if (text[pos] == ']') return result.also { pos++ }
        while (true) {
            result += parseValue()
            skipSpaces()
            when (text.getOrNull(pos++)) {
                ',' -> continue
                ']' -> return result
                else -> throw IOException("bad JSON array at $pos")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = text.getOrNull(pos++) ?: throw IOException("unterminated JSON string")
            when (c) {
                '"' -> {
                    return out.toString()
                }

                '\\' -> {
                    when (val e = text.getOrNull(pos++)) {
                        'n' -> {
                            out.append('\n')
                        }

                        't' -> {
                            out.append('\t')
                        }

                        'r' -> {
                            out.append('\r')
                        }

                        'b' -> {
                            out.append('\b')
                        }

                        'f' -> {
                            out.append('\u000C')
                        }

                        'u' -> {
                            out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }

                        '"', '\\', '/' -> {
                            out.append(e)
                        }

                        else -> {
                            throw IOException("bad JSON escape")
                        }
                    }
                }

                else -> {
                    out.append(c)
                }
            }
        }
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val token = text.substring(start, pos)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: throw IOException("bad JSON number '$token'")
    }

    private fun expect(c: Char) {
        if (text.getOrNull(pos) != c) throw IOException("expected '$c' at $pos")
        pos++
    }

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
