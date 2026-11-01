package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule

/** The removal rules in the order they run. See `docs/cleanup-rules.md#removal-rules`. */
object DefaultRules {
    val all: List<CleanupRule> =
        listOf(
            RotatedTextRule,
            PageNumbersRule,
            RunningHeadersRule,
            LegalNoticesRule,
            CopyrightPageRule,
            ContentsAndIndexRule,
            FootnotesRule,
            FootnoteMarkersRule,
        )
}
