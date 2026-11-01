package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.Transcript

/**
 * The transcript as storable paragraphs: the text to speak (see engine/docs/api.md), the kind, and the page
 * the first word came from. Segments with no words are dropped.
 */
fun Transcript.toStoredParagraphs(): List<StoredParagraph> =
    segments
        .filter { it.tokens.isNotEmpty() }
        .map { segment ->
            val text =
                buildString {
                    for (token in segment.tokens) {
                        if (isNotEmpty() && token.spaceBefore) append(' ')
                        append(token.text)
                    }
                }
            StoredParagraph(segment.kind, segment.tokens.first().page, text)
        }
