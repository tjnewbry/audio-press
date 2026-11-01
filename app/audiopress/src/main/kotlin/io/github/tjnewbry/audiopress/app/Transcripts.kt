package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.TextSource
import io.github.tjnewbry.audiopress.core.Transcript

/** The transcript as text to speak: one string per paragraph or heading, as engine/docs/api.md describes. */
fun Transcript.paragraphTexts(): List<String> =
    segments.map { segment ->
        buildString {
            for (token in segment.tokens) {
                if (isNotEmpty() && token.spaceBefore) append(' ')
                append(token.text)
            }
        }
    }

/** Runs the engine over [source]. This blocks, so call it off the main thread. */
fun convertToParagraphs(
    source: TextSource,
    sourceName: String,
): List<String> = Converter().convert(source, sourceName, ConversionOptions()).transcript.paragraphTexts()
