package io.github.tjnewbry.audiopress.app

/** What the app needs from the baking side to delete a book safely; [BakeCenter] provides it. */
interface BakeStopper {
    /** Cancels any download or bake of [bookId] and waits for it to end. False if it did not stop in time. */
    fun stopAndWait(bookId: String): Boolean

    /** The book's baked audio is gone from disk: show it as not baked. */
    fun bakeDeleted(bookId: String)

    /** The whole book is gone: drop what is kept for it. */
    fun forget(bookId: String)
}

enum class DeleteResult { DONE, STILL_BAKING, INCOMPLETE }

/**
 * Deletes a book, or only its bake. A running download or bake is stopped first and waited for, so nothing writes
 * while files go. Only the book's own folder is touched; the downloaded voice models are shared and never removed.
 * Blocks, so call it off the main thread.
 */
class BookDeletion(
    private val store: BookStore,
    private val baking: BakeStopper,
) {
    fun deleteAll(book: Book): DeleteResult = delete(book, everything = true)

    fun deleteBake(book: Book): DeleteResult = delete(book, everything = false)

    private fun delete(
        book: Book,
        everything: Boolean,
    ): DeleteResult {
        if (!baking.stopAndWait(book.id)) return DeleteResult.STILL_BAKING
        val removed = if (everything) store.deleteBook(book.id) else store.deleteBake(book.id)
        if (everything) baking.forget(book.id) else baking.bakeDeleted(book.id)
        return if (removed) DeleteResult.DONE else DeleteResult.INCOMPLETE
    }
}
