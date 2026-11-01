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
import io.github.tjnewbry.audiopress.core.TextSource
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
}

/** Turns a file-picker result into a [TextSource]. The one place that knows how PDFs are read. */
fun interface PdfOpener {
    fun open(
        uri: Uri,
        displayName: String,
    ): TextSource
}

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
    private val opener = PdfOpener { uri, name -> AndroidPdfTextSource.open(application, uri, name) }

    // Baking has its own thread (it is long), and its controllers outlive the reader screen so a bake carries on.
    private val bakeWorker = Executors.newSingleThreadExecutor { Thread(it, "bake") }
    private val baker: BakeService = KokoroBakeService() // simulated voices until the Kokoro runtime lands in :bake
    private val bakes = mutableMapOf<String, BookBakeController>()

    init {
        worker.execute {
            AndroidPdfTextSource.clearWorkFiles(application)
            refresh()
        }
    }

    fun importPdf(uri: Uri) {
        if (importState is ImportState.Working) return
        val fileName = queryFileName(uri) ?: "document.pdf"
        importState = ImportState.Working(fileName)
        val token = CancellationToken() // a new one for every import
        importToken = token
        worker.execute {
            val result =
                try {
                    opener.open(uri, fileName).use { source ->
                        BookImporter(store).import(source, titleFromFileName(fileName), token) { fraction ->
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
                            )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${book.title}", e)
                openError = "\"${book.title}\" could not be opened. Import it again."
            }
        }
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
        reader?.close()
        worker.shutdown()
        bakes.values.forEach { it.cancel() }
        bakeWorker.shutdown()
    }

    /** The bake controller for [book], made once and kept while the app runs. Call on the worker thread. */
    private fun bakeFor(
        book: Book,
        utterances: List<Utterance>,
    ): BookBakeController =
        bakes.getOrPut(book.id) {
            BookBakeController(utterances, store.audioFolder(book.id), baker, bakeWorker, choiceFile = store.voiceFile(book.id))
        }

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
