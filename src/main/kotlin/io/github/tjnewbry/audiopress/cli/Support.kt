package io.github.tjnewbry.audiopress.cli

import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** An output file could not be written (exit code 5). */
class OutputException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Writes output files atomically, as UTF-8. See `docs/usage.md`. */
object OutputFiles {
    /**
     * Writes to [target], or to stdout (stderr when [toStderr]) when [target] is "-". A file is written to
     * a temporary file in the same directory first and moved into place only when [write] succeeds.
     */
    fun write(
        target: String,
        toStderr: Boolean,
        write: (Writer) -> Unit,
    ) {
        if (target == "-") {
            val stream = if (toStderr) System.err else System.out
            OutputStreamWriter(stream, Charsets.UTF_8).let { writer ->
                write(writer)
                writer.flush()
            }
            return
        }
        val path = Path.of(target).toAbsolutePath()
        try {
            val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
            try {
                Files.newBufferedWriter(temp, Charsets.UTF_8).use(write)
                try {
                    Files.move(temp, path, REPLACE_EXISTING, ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp, path, REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: IOException) {
            throw OutputException("cannot write $target: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}

/** Messages for the user, always on stderr so stdout can carry the transcript. */
object Messages {
    fun error(message: String) = System.err.println("audio-press: error: $message")

    fun warning(message: String) = System.err.println("audio-press: warning: $message")

    fun info(message: String) = System.err.println(message)
}

/** The program version, filled in by the build. */
object Version {
    val current: String by lazy {
        Version::class.java
            .getResourceAsStream("/io/github/tjnewbry/audiopress/version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: "unknown"
    }
}
