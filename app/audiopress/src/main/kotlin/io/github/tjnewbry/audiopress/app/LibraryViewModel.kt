package io.github.tjnewbry.audiopress.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.ConversionCancelledException
import io.github.tjnewbry.audiopress.core.InputUnreadableException
import io.github.tjnewbry.audiopress.pdfsource.AndroidPdfTextSource
import java.io.File
import java.util.concurrent.Executors

sealed interface ImportState {
    data object Idle : ImportState

    /** [progress] is 0 to 1 and covers reading and analyzing; [cancelling] is true after the user pressed Cancel. */
    data class Working(
        val fileName: String,
        val progress: Float = 0f,
        val cancelling: Boolean = false,
    ) : ImportState

    data class Failed(
        val message: String,
    ) : ImportState

    /** The chosen PDF is being copied and opened, before the import screen appears. */
    data class Preparing(
        val fileName: String,
    ) : ImportState
}

/**
 * A PDF the user picked and that waits on the import screen for a page range and a name. [file] is a private copy that stays
 * until the import ends or is cancelled; [title] is the PDF's own title, if it has one.
 */
class PendingImport(
    val file: File,
    val fileName: String,
    val title: String?,
    val totalPages: Int,
)

/** The library: the list of books, importing a PDF, and the book that is open. Survives screen rotation. */
class LibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    var books: List<Book> by mutableStateOf(emptyList())
        private set

    /** The ids of books whose audio is fully baked; the library marks them. */
    var bakedBooks: Set<String> by mutableStateOf(emptySet())
        private set
    var importState: ImportState by mutableStateOf(ImportState.Idle)
        private set
    var reader: ReaderSession? by mutableStateOf(null)
        private set

    /** The picked PDF waiting on the import screen, or null. */
    var pending: PendingImport? by mutableStateOf(null)
        private set
    var loaded: Boolean by mutableStateOf(false)
        private set
    var openError: String? by mutableStateOf(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var importToken: CancellationToken? = null

    // One thread for all storage and conversion work, so imports and reads never overlap.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "library") }
    private val store = BookStore(File(application.filesDir, "books"))
    private val stagingFolder = File(application.cacheDir, "import-staging")

    // Baking lives in the application, not here, so a bake carries on when this screen goes away (see [BakeCenter]).
    private val center = (application as AudioPressApp).bakeCenter
    private val baker: BakeService get() = center.baker

    init {
        worker.execute {
            AndroidPdfTextSource.clearWorkFiles(application)
            stagingFolder.deleteRecursively() // copies left by an import that was interrupted
            refresh()
        }
    }

    /** Copies the picked PDF aside and opens it just far enough to learn its length and title; then the import screen shows. */
    fun importPdf(uri: Uri) {
        if (importState is ImportState.Working || importState is ImportState.Preparing || pending != null) return
        val fileName = queryFileName(uri) ?: "document.pdf"
        importState = ImportState.Preparing(fileName)
        worker.execute {
            var copy: File? = null
            importState =
                try {
                    val target = copyAside(uri).also { copy = it }
                    AndroidPdfTextSource.open(getApplication(), target, fileName).use { source ->
                        pending = PendingImport(target, fileName, source.title, source.totalPageCount)
                    }
                    ImportState.Idle
                } catch (e: Exception) {
                    Log.w(TAG, "Opening $fileName failed", e)
                    copy?.delete()
                    ImportState.Failed(importErrorMessage(fileName, e))
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Opening $fileName ran out of memory", e)
                    copy?.delete()
                    ImportState.Failed(importErrorMessage(fileName, e))
                }
        }
    }

    private fun copyAside(uri: Uri): File {
        stagingFolder.mkdirs()
        val target = File.createTempFile("pick-", ".pdf", stagingFolder)
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw InputUnreadableException(target.name, e)
        }
        return target
    }

    /** Leaves the import screen without importing, and deletes the copy of the PDF. */
    fun cancelPending() {
        pending?.file?.delete()
        pending = null
    }

    /**
     * Imports pages [range] (the whole PDF when null) of the pending PDF as a book named [title]. The progress shows on the
     * library screen; the copy of the PDF is deleted when the import ends, however it ends.
     */
    fun importPending(
        range: IntRange?,
        title: String,
    ) {
        val chosen = pending ?: return
        pending = null
        val fileName = chosen.fileName
        importState = ImportState.Working(title)
        val token = CancellationToken() // a new one for every import
        importToken = token
        worker.execute {
            val result =
                try {
                    val pages = range?.takeUnless { it.first == 1 && it.last == chosen.totalPages }
                    AndroidPdfTextSource.open(getApplication(), chosen.file, fileName, pages).use { source ->
                        BookImporter(store).import(source, title, token) { fraction ->
                            // Called on this worker thread; Compose state may be written from any thread.
                            (importState as? ImportState.Working)?.let { importState = it.copy(progress = fraction) }
                        }
                    }
                    ImportState.Idle
                } catch (e: ConversionCancelledException) {
                    ImportState.Idle // the user cancelled: not an error, nothing to show
                } catch (e: Exception) {
                    Log.w(TAG, "Import of $fileName failed", e)
                    ImportState.Failed(importErrorMessage(fileName, e))
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Import of $fileName ran out of memory", e)
                    ImportState.Failed(importErrorMessage(fileName, e))
                } finally {
                    chosen.file.delete()
                }
            refresh()
            importState = result
        }
    }

    fun cancelImport() {
        importToken?.cancel()
        (importState as? ImportState.Working)?.let { importState = it.copy(cancelling = true) }
    }

    fun dismissImportError() {
        if (importState is ImportState.Failed) importState = ImportState.Idle
    }

    fun open(book: Book) {
        if (reader != null) return
        openError = null
        worker.execute {
            try {
                val paragraphs = store.load(book.id)
                val position = store.loadProgress(book.id)
                val speed = store.loadSpeed(book.id)
                // Split the book and find its words here, off the main thread, so opening a huge book does not freeze the screen.
                val utterances = speechUtterances(paragraphs).onEach { it.words }
                val bake = bakeFor(book, utterances)
                main.post {
                    if (reader == null) {
                        reader =
                            ReaderSession(
                                getApplication(),
                                book,
                                paragraphs,
                                utterances,
                                position,
                                { store.saveProgress(book.id, it) },
                                bake,
                                speed,
                                { store.saveSpeed(book.id, it) },
                            )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${book.title}", e)
                openError = "\"${book.title}\" could not be opened. Import it again."
            }
        }
    }

    /**
     * Deletes [book] and everything kept for it ([everything] true), or only its baked audio and saved bake voice.
     * A running download or bake of the book is cancelled first and waited for, so nothing writes while files go.
     */
    fun delete(
        book: Book,
        everything: Boolean,
    ) {
        if (reader != null) return // only from the library, where no book is open or playing
        openError = null
        worker.execute {
            val deletion = BookDeletion(store, center)
            when (if (everything) deletion.deleteAll(book) else deletion.deleteBake(book)) {
                DeleteResult.DONE -> {
                    // Nothing to report.
                }

                DeleteResult.STILL_BAKING -> {
                    openError =
                        "\"${book.title}\" is still baking and did not stop. Cancel the bake and try again."
                }

                DeleteResult.INCOMPLETE -> {
                    openError = "Some files of \"${book.title}\" could not be deleted."
                }
            }
            refresh()
        }
    }

    /** Renames [book] to [newTitle] (trimmed); a blank or unchanged title is ignored. The library list refreshes. */
    fun rename(
        book: Book,
        newTitle: String,
    ) {
        val title = newTitle.trim()
        if (title.isEmpty() || title == book.title) return
        worker.execute {
            if (!store.rename(book.id, title)) openError = "\"${book.title}\" could not be renamed."
            refresh()
        }
    }

    /** Exports the baked audio of [book] as one .m4a file to the document [target] the user chose; see [ExportCenter]. */
    fun exportBakedAudio(
        book: Book,
        target: Uri,
    ) {
        val exports = (getApplication<Application>() as AudioPressApp).exportCenter
        exports.start(book, store.audioFolder(book.id), { store.footprintBytes(book.id) }, target)
    }

    /** Pauses the open book, if any, so the position is saved. */
    fun pausePlayback() {
        reader?.pausePlayback()
    }

    fun closeReader() {
        reader?.close()
        reader = null
        worker.execute { refresh() } // a bake may have finished while the book was open
    }

    override fun onCleared() {
        pending?.file?.delete()
        reader?.close()
        worker.shutdown()
    }

    /** The bake controller for [book], made once and kept while the app runs. Call on the worker thread. */
    private fun bakeFor(
        book: Book,
        utterances: List<Utterance>,
    ): BookBakeController = center.controllerFor(book, utterances, store.audioFolder(book.id), store.voiceFile(book.id))

    private fun refresh() {
        val list = store.list()
        bakedBooks = list.filter { isBaked(it) }.map { it.id }.toSet()
        books = list
        loaded = true
    }

    private fun isBaked(book: Book): Boolean = baker.available && baker.readManifest(store.audioFolder(book.id))?.complete == true

    private fun queryFileName(uri: Uri): String? =
        try {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: RuntimeException) {
            null // Not every provider can be queried; the name is only for titles and messages.
        }

    private companion object {
        const val TAG = "AudioPress"
    }
}
