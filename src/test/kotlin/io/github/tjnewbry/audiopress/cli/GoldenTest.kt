package io.github.tjnewbry.audiopress.cli

import io.github.tjnewbry.audiopress.testing.Golden
import io.github.tjnewbry.audiopress.testing.MiniBook
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PDF in, transcript out, compared with hand-written expected transcripts. */
class GoldenTest {
    @TempDir
    lateinit var dir: Path

    private fun convert(vararg options: String): String {
        val pdf = MiniBook.write(dir.resolve("mini-book.pdf"))
        val output = dir.resolve("out.txt")
        assertEquals(ExitCode.SUCCESS, runCli(listOf(pdf.toString(), "-o", output.toString(), "-q") + options))
        return Files.readString(output)
    }

    @Test
    fun `mini book with page markers`() {
        Golden.assertMatches("mini-book.txt", convert())
    }

    @Test
    fun `mini book without page markers`() {
        Golden.assertMatches("mini-book-no-markers.txt", convert("--page-markers", "none"))
    }

    @Test
    fun `disabling rules keeps what they would remove`() {
        // The page numbers are in small print, so the footnotes rule would catch them too.
        val text = convert("--disable", "page-numbers,footnotes,urls")

        assertTrue("\n3\n" in text, text)
        assertTrue("www.example.com/test" in text, text)
    }
}
