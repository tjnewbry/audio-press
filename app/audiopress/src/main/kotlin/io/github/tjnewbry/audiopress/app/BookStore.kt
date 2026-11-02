package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.SegmentKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * with backslash, tab, and line breaks in the text escaped), plus `progress.txt` (the reader's saved position,
 * rewritten while the book is read). The PDF itself is not kept.
 *
 * A book is written to a `.tmp` folder and renamed, so a crash never leaves a half-written book in the
 * list. Calls that touch different books or files may come from different threads.
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
    fun load(id: String): List<StoredParagraph> = readTranscript(File(bookFolder(id), TRANSCRIPT_FILE))

    /** Remembers where the reader is in book [id]. Written to a temporary file and moved into place, so it is never half-written. */
    fun saveProgress(
        id: String,
        position: Position,
    ) {
        val folder = bookFolder(id)
        if (!folder.isDirectory) return // the book is gone; nothing to remember
        val temporary = File(folder, "$PROGRESS_FILE.tmp")
        temporary.writeText("${position.paragraph}\t${position.offset}\n", Charsets.UTF_8)
        Files.move(temporary.toPath(), File(folder, PROGRESS_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** The saved position in book [id], or null when there is none or it cannot be read. */
    fun loadProgress(id: String): Position? =
        try {
            val parts = File(bookFolder(id), PROGRESS_FILE).readText(Charsets.UTF_8).trim().split('\t')
            if (parts.size == 2) Position(parts[0].toInt(), parts[1].toInt()) else null
        } catch (e: IOException) {
            null
        } catch (e: NumberFormatException) {
            null
        }

    /**
     * Gives book [id] the title [title] (already trimmed, not blank) by rewriting its properties file in place (through a
     * temporary file, so it is never half-written). Returns false when the book is missing or the file cannot be written.
     */
    fun rename(
        id: String,
        title: String,
    ): Boolean {
        val folder = bookFolder(id)
        val file = File(folder, METADATA_FILE)
        return try {
            val properties = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            properties.setProperty("title", title)
            val temporary = File(folder, "$METADATA_FILE.tmp")
            temporary.writer(Charsets.UTF_8).use { properties.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** The reading speed saved for book [id] (0.5 to 2, in steps of 0.05), or 1 when none is saved or it cannot be read. */
    fun loadSpeed(id: String): Float =
        try {
            PlaybackSpeed.snap(File(bookFolder(id), SPEED_FILE).readText(Charsets.UTF_8).trim().toFloat())
        } catch (e: IOException) {
            PlaybackSpeed.DEFAULT
        } catch (e: NumberFormatException) {
            PlaybackSpeed.DEFAULT
        }

    /** Remembers the reading speed of book [id] in its folder, so deleting the book removes it and deleting only the bake keeps it. */
    fun saveSpeed(
        id: String,
        speed: Float,
    ) {
        val folder = bookFolder(id)
        if (!folder.isDirectory) return // the book is gone
        val temporary = File(folder, "$SPEED_FILE.tmp")
        temporary.writeText("${PlaybackSpeed.snap(speed)}\n", Charsets.UTF_8)
        Files.move(temporary.toPath(), File(folder, SPEED_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Everything book [id] takes on the phone, in bytes: transcript, properties, progress, saved voice and baked clips. */
    fun footprintBytes(id: String): Long = folderSize(bookFolder(id))

    /** The folder for book [id]'s baked audio. It may not exist yet. */
    fun audioFolder(id: String): File = File(bookFolder(id), "bake")

    /** The file that remembers which voice book [id] was (being) baked with. */
    fun voiceFile(id: String): File = File(bookFolder(id), "bake-voice.txt")

    /**
     * Removes everything kept for book [id]: transcript, properties, progress, saved voice, and baked audio. Only that
     * book's own folder is touched; a missing book is not an error. Returns false if something could not be removed.
     */
    fun deleteBook(id: String): Boolean {
        val folder = bookFolder(id)
        // Take the properties file first, so a half-deleted book never shows in the list.
        File(folder, METADATA_FILE).delete()
        return !folder.exists() || folder.deleteRecursively()
    }

    /** Removes only book [id]'s baked audio and saved bake voice. Missing files are fine. Returns false if something stayed. */
    fun deleteBake(id: String): Boolean {
        val audio = audioFolder(id)
        val voice = voiceFile(id)
        val audioGone = !audio.exists() || audio.deleteRecursively()
        val voiceGone = !voice.exists() || voice.delete()
        return audioGone && voiceGone
    }

    private fun bookFolder(id: String): File {
        require(id.isNotEmpty() && '/' !in id && '\\' !in id && id != "." && id != "..") { "Bad book id" }
        return File(root, id)
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
        const val PROGRESS_FILE = "progress.txt"
        const val SPEED_FILE = "speed.txt"
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
