package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages
import io.github.tjnewbry.audiopress.core.removedBy

/** `rotated-text`: removes text that is not horizontal, such as notices printed up the page margin. */
object RotatedTextRule : CleanupRule {
    override val id = RuleId.ROTATED_TEXT

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document.keptPages(removed).flatMap { page ->
            page.keptLines(removed).filterNot { it.horizontal }.map { it.removedBy(id) }
        }
}
