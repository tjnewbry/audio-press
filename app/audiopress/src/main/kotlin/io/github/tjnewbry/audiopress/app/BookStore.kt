package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.SegmentKind
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** One imported book. [importedAt] is milliseconds since the epoch. */
data class Book(
    val id: String,
    val title: String,
    val pageCount: Int,
    val wordCount: Int,
    val importedAt: Long,
)

/** One paragraph or heading of a book's transcript, with the physical page its first word came from. */
data class StoredParagraph(
    val kind: SegmentKind,
    val page: Int,
    val text: String,
)

/**
 * Keeps imported books in [root] (the app passes `filesDir/books`). Each book is a folder `<id>/` holding
 * `book.properties` (the metadata) and `transcript.txt` (one line per paragraph: `KIND<tab>PAGE<tab>text`,
 * with backslash, tab, and line breaks in the text escaped). The PDF itself is not kept.
 *
 * A book is written to a `.tmp` folder and renamed, so a crash never leaves a half-written book in the
 * list. Call from one thread at a time.
 */
class BookStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun save(
        title: String,
        pageCount: Int,
        wordCount: Int,
        paragraphs: List<StoredParagraph>,
    ): Book {
        val book = Book(newId(), title, pageCount, wordCount, clock())
        val staging = File(root, book.id + TEMP_SUFFIX)
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Cannot create $staging")
            writeMetadata(File(staging, METADATA_FILE), book)
            writeTranscript(File(staging, TRANSCRIPT_FILE), paragraphs)
            if (!staging.renameTo(File(root, book.id))) throw IOException("Cannot move $staging into place")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        return book
    }

    /** The books, newest first. A folder that cannot be read is skipped, and leftovers of a failed save are removed. */
    fun list(): List<Book> {
        val folders = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        folders.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.deleteRecursively() }
        return folders
            .filterNot { it.name.endsWith(TEMP_SUFFIX) }
            .mapNotNull { readMetadata(File(it, METADATA_FILE), it.name) }
            .sortedByDescending { it.importedAt }
    }

    /** The paragraphs of book [id], in reading order. Throws [IOException] when the book is missing or unreadable. */
    fun load(id: String): List<StoredParagraph> {
        require(id.isNotEmpty() && '/' !in id && '\\' !in id && id != "." && id != "..") { "Bad book id" }
        return readTranscript(File(File(root, id), TRANSCRIPT_FILE))
    }

    private fun writeMetadata(
        file: File,
        book: Book,
    ) {
        val properties =
            Properties().apply {
                setProperty("id", book.id)
                setProperty("title", book.title)
                setProperty("pageCount", book.pageCount.toString())
                setProperty("wordCount", book.wordCount.toString())
                setProperty("importedAt", book.importedAt.toString())
            }
        file.writer(Charsets.UTF_8).use { properties.store(it, null) }
    }

    private fun readMetadata(
        file: File,
        folderName: String,
    ): Book? =
        try {
            val properties = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            Book(
                id = folderName,
                title = properties.getProperty("title") ?: return null,
                pageCount = properties.getProperty("pageCount")?.toInt() ?: return null,
                wordCount = properties.getProperty("wordCount")?.toInt() ?: return null,
                importedAt = properties.getProperty("importedAt")?.toLong() ?: return null,
            )
        } catch (e: IOException) {
            null
        } catch (e: NumberFormatException) {
            null
        }

    private fun writeTranscript(
        file: File,
        paragraphs: List<StoredParagraph>,
    ) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            for (paragraph in paragraphs) {
                out.write("${paragraph.kind.name}\t${paragraph.page}\t${escape(paragraph.text)}\n")
            }
        }
    }

    private fun readTranscript(file: File): List<StoredParagraph> =
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .filter { it.isNotEmpty() }
                .map { line ->
                    val parts = line.split('\t', limit = 3)
                    if (parts.size != 3) throw IOException("Damaged transcript in $file")
                    try {
                        StoredParagraph(SegmentKind.valueOf(parts[0]), parts[1].toInt(), unescape(parts[2]))
                    } catch (e: IllegalArgumentException) {
                        throw IOException("Damaged transcript in $file", e)
                    }
                }.toList()
        }

    private companion object {
        const val METADATA_FILE = "book.properties"
        const val TRANSCRIPT_FILE = "transcript.txt"
        const val TEMP_SUFFIX = ".tmp"

        fun escape(text: String) =
            text
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

        fun unescape(text: String): String =
            buildString {
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    if (c == '\\' && i + 1 < text.length) {
                        i++
                        append(
                            when (val next = text[i]) {
                                't' -> '\t'
                                'n' -> '\n'
                                'r' -> '\r'
                                else -> next
                            },
                        )
                    } else {
                        append(c)
                    }
                    i++
                }
            }
    }
}
