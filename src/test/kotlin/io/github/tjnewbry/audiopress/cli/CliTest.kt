package io.github.tjnewbry.audiopress.cli

import io.github.tjnewbry.audiopress.testing.fixturePdf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    @TempDir
    lateinit var dir: Path

    private val book: Path by lazy {
        fixturePdf(dir.resolve("book.pdf")) {
            page {
                text("THE TEST BOOK", x = 150f, y = 40f, size = 9f)
                text("The keeper climbed the stair.", x = 72f, y = 100f)
            }
            page { text("Second page.", x = 72f, y = 100f) }
        }
    }

    @Test
    fun `version exits successfully`() {
        assertEquals(ExitCode.SUCCESS, runCli(listOf("--version")))
    }

    @Test
    fun `writes the transcript next to the input by default`() {
        assertEquals(ExitCode.SUCCESS, runCli(listOf(book.toString(), "-q")))

        assertEquals(
            "[[page 1]]\nTHE TEST BOOK\n\nThe keeper climbed the stair.\n\n[[page 2]]\nSecond page.\n",
            Files.readString(dir.resolve("book.txt")),
        )
    }

    @Test
    fun `writes a report of removals when asked`() {
        val report = dir.resolve("report.txt")

        assertEquals(
            ExitCode.SUCCESS,
            runCli(listOf(book.toString(), "-o", dir.resolve("out.txt").toString(), "--report", report.toString(), "-q")),
        )

        val text = Files.readString(report)
        assertTrue(text.startsWith("Audio Press report\n"), text)
        assertTrue("Disabled: none" in text, text)
        assertTrue("Summary:" in text, text)
    }

    @Test
    fun `refuses to overwrite the input file`() {
        assertEquals(ExitCode.USAGE_ERROR, runCli(listOf(book.toString(), "-o", book.toString())))
    }

    @Test
    fun `a missing input file is an input error`() {
        assertEquals(ExitCode.INPUT_ERROR, runCli(listOf(dir.resolve("missing.pdf").toString())))
    }

    @Test
    fun `a PDF without text is a no-text error`() {
        val blank = fixturePdf(dir.resolve("scan.pdf")) { page() }

        assertEquals(ExitCode.NO_TEXT, runCli(listOf(blank.toString(), "-o", dir.resolve("scan.txt").toString())))
    }

    @Test
    fun `an output file that cannot be written is an output error`() {
        val target = dir.resolve("no-such-folder").resolve("out.txt")

        assertEquals(ExitCode.OUTPUT_ERROR, runCli(listOf(book.toString(), "-o", target.toString(), "-q")))
    }

    @Test
    fun `a bad page range is a usage error`() {
        assertEquals(ExitCode.USAGE_ERROR, runCli(listOf("--pages", "5-3", book.toString())))
    }

    @Test
    fun `pages beyond the end of the document are a usage error`() {
        assertEquals(ExitCode.USAGE_ERROR, runCli(listOf("--pages", "7-9", book.toString())))
    }

    @Test
    fun `an unknown rule is a usage error`() {
        assertEquals(ExitCode.USAGE_ERROR, runCli(listOf("--disable", "page-numbers,no-such-rule", book.toString())))
    }

    @Test
    fun `an unknown option is a usage error`() {
        assertEquals(ExitCode.USAGE_ERROR, runCli(listOf("--no-such-option", book.toString())))
    }

    @Test
    fun `dump-layout writes the lines of the selected pages`() {
        val dump = dir.resolve("layout.txt")

        val code =
            runCli(listOf(book.toString(), "--pages", "1", "-o", dir.resolve("out.txt").toString(), "--dump-layout", dump.toString(), "-q"))

        assertEquals(ExitCode.SUCCESS, code)
        val text = Files.readString(dump)
        assertTrue("== Page 1" in text, text)
        assertTrue("\"THE TEST BOOK\"" in text, text)
        assertTrue("\"The keeper climbed the stair.\"" in text, text)
        assertTrue("Page 2" !in text, text)
    }
}
