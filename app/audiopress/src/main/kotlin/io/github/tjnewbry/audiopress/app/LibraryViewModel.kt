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
                main.post {
                    if (reader == null) {
                        reader = ReaderSession(getApplication(), book, paragraphs, position) { store.saveProgress(book.id, it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${book.title}", e)
                openError = "\"${book.title}\" could not be opened. Import it again."
            }
        }
    }

    fun closeReader() {
        reader?.close()
        reader = null
    }

    override fun onCleared() {
        reader?.close()
        worker.shutdown()
    }

    private fun refresh() {
        books = store.list()
        loaded = true
    }

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
