package io.github.tjnewbry.audiopress.bake.kokoro.text

/**
 * Spells whole numbers out in English: the one part of number reading that is not about misaki's conventions. The
 * implementation is [EnglishNumberSpeller]. Everything else (years, decimals, currency, times, the word "and") is in [EnglishNumbers].
 */
interface NumberSpeller {
    /** `1250` as "one thousand two hundred fifty" (no "and"), `21` as "twenty-one". */
    fun cardinal(value: Long): String

    /** `21` as "twenty-first", `3` as "third". */
    fun ordinal(value: Long): String
}
