package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.MiniJson
import java.io.File
import java.io.IOException

/**
 * One of misaki's pronunciation dictionaries (`us_gold.json`, `gb_silver.json`, ...; Apache-2.0, from
 * hexgrad/misaki). It maps a word to its phonemes, or to a set of phonemes by part of speech (`DEFAULT`, `NOUN`,
 * `VERB`, `ADJ`, `ADV`, `None`, ...).
 *
 * Like misaki's `grow_dictionary`, a lower-case word also finds the entry of its capitalized form and the reverse
 * ("paris" finds "Paris"), but the variants are worked out on lookup instead of being stored, which halves the memory.
 */
class MisakiDictionary(
    private val entries: Map<String, Any?>,
) {
    val size: Int get() = entries.size

    /** The entry for [word]: a phoneme `String`, or a `Map<String, String?>` by part of speech, or null. */
    operator fun get(word: String): Any? {
        entries[word]?.let { return it }
        if (word.length < 2) return null
        val lower = word.lowercase()
        return when {
            word == lower -> capitalize(word).takeIf { it != word }?.let { entries[it] }
            word == capitalize(lower) -> entries[lower]
            else -> null
        }
    }

    operator fun contains(word: String): Boolean = get(word) != null

    private fun capitalize(word: String) = word.replaceFirstChar { it.uppercase() }

    companion object {
        /** Reads a misaki dictionary from its JSON text. */
        fun parse(json: String): MisakiDictionary {
            @Suppress("UNCHECKED_CAST")
            val entries = MiniJson(json).parseValue() as? Map<String, Any?> ?: throw IOException("the dictionary is not a JSON object")
            return MisakiDictionary(entries)
        }

        fun load(file: File): MisakiDictionary = parse(file.readText(Charsets.UTF_8))
    }
}
