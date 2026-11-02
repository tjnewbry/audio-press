package io.github.tjnewbry.audiopress.app

import kotlin.math.roundToInt

/** What the foreground notification shows while a bake runs. [indeterminate] is true when there is no progress to show yet. */
data class BakeNotice(
    val title: String,
    val text: String,
    val done: Int,
    val total: Int,
    val indeterminate: Boolean,
    val cancelling: Boolean,
)

/** The notification for a bake of the book called [bookTitle] in [state], or null when nothing is running. */
fun noticeFor(
    bookTitle: String,
    state: BakeState,
): BakeNotice? =
    when (state) {
        is BakeState.Downloading -> {
            BakeNotice(
                title = bookTitle,
                text = if (state.cancelling) "Cancelling..." else "Downloading ${state.quality.label}...",
                done = (state.fraction * PERCENT).roundToInt(),
                total = PERCENT,
                indeterminate = false,
                cancelling = state.cancelling,
            )
        }

        is BakeState.Baking -> {
            BakeNotice(
                title = bookTitle,
                text = if (state.cancelling) "Cancelling..." else bakingText(state),
                done = state.done,
                total = state.total,
                indeterminate = state.total <= 0,
                cancelling = state.cancelling,
            )
        }

        else -> {
            null
        }
    }

private fun bakingText(state: BakeState.Baking): String {
    val name = state.voice?.voice?.name
    return if (name != null) "Baking audio using $name..." else "Baking audio..."
}

private const val PERCENT = 100

/** One book that is being downloaded for or baked right now. */
data class ActiveBake(
    val bookId: String,
    val bookTitle: String,
    val state: BakeState,
)

/**
 * Keeps track of which books are being baked, from the state changes of their controllers, and tells [onChange] when
 * the book to show in the notification changes (or when there is none). Safe to call from any thread.
 */
class ActiveBakeTracker(
    private val onChange: (ActiveBake?) -> Unit,
) {
    private val running = LinkedHashMap<String, ActiveBake>()

    /** The bake to show, or null when none is running. */
    val current: ActiveBake? get() = synchronized(running) { running.values.firstOrNull() }

    /** A controller published [state] for the book [bookId]. */
    fun update(
        bookId: String,
        bookTitle: String,
        state: BakeState,
    ) {
        val shown =
            synchronized(running) {
                if (noticeFor(bookTitle, state) != null) running[bookId] = ActiveBake(bookId, bookTitle, state) else running.remove(bookId)
                running.values.firstOrNull()
            }
        onChange(shown)
    }
}
