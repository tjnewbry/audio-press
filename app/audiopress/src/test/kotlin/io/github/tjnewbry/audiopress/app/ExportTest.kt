package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.bake.ExportCancelledException
import io.github.tjnewbry.audiopress.bake.ExportResult
import io.github.tjnewbry.audiopress.bake.ExportStorageFullException
import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the success dialog says exactly what was asked for`() {
        assertEquals(
            "Export successful!\n\n" +
                "The exported audio file is 21.4 MB.\n\n" +
                "The book in Audio Press still takes up 1.8 GB.\n\n" +
                "You can use the player in Audio Press to follow along with the text, " +
                "but if that is not important to you then consider deleting the bake data to save space.",
            exportSuccessText(21_400_000, 1_823_000_000),
        )
    }

    @Test
    fun `the suggested file name is the title with m4a, without characters a file cannot have`() {
        assertEquals("A Short History of Bread.m4a", suggestedExportName("A Short History of Bread"))
        assertEquals("a_b_c.m4a", suggestedExportName("a/b:c"))
        assertEquals("audiobook.m4a", suggestedExportName("  "))
    }

    // The flow: encode to a temp file, copy to the chosen place, clean up.

    private val states = mutableListOf<ExportState>()
    private val target = ByteArrayOutputStream()
    private var targetDeleted = false
    private lateinit var temp: File

    private fun job(
        encode: (File, CancellationToken, (Float) -> Unit) -> ExportResult,
        open: () -> OutputStream = { target },
    ): ExportJob {
        temp = File(folder.root, "export.m4a")
        return ExportJob("Book", temp, encode, open, { targetDeleted = true }, { 5_000L }, { states += it })
    }

    @Test
    fun `a good export copies the temp file to the target, reports the real sizes and removes the temp file`() {
        val job =
            job({ out, _, progress ->
                out.writeBytes(ByteArray(300) { 7 })
                progress(0.5f)
                ExportResult(300, 1_000)
            })

        val ended = job.run()

        assertEquals(ExportState.Done("Book", 300, 5_000), ended)
        assertEquals(300, target.size())
        assertFalse(temp.exists())
        assertFalse(targetDeleted)
        assertEquals(ExportState.Working("Book", 0f), states.first())
        assertTrue(states.contains(ExportState.Working("Book", 0.5f)))
    }

    @Test
    fun `a cancel while encoding removes the temp file and the half-made target, and is not a failure`() {
        val job =
            job({ out, _, _ ->
                out.writeBytes(ByteArray(10))
                throw ExportCancelledException()
            })

        assertEquals(ExportState.Idle, job.run())
        assertFalse(temp.exists())
        assertTrue(targetDeleted)
    }

    @Test
    fun `a cancel while copying stops the copy and cleans up`() {
        lateinit var running: ExportJob
        running =
            job({ out, _, _ ->
                out.writeBytes(ByteArray(200_000))
                ExportResult(200_000, 1)
            }, open = {
                running.cancel() // the user taps Cancel just as the copy starts
                target
            })

        assertEquals(ExportState.Idle, running.run())
        assertEquals(0, target.size())
        assertFalse(temp.exists())
        assertTrue(targetDeleted)
    }

    @Test
    fun `an exporter failure shows its own message and cleans up`() {
        val job = job({ _, _, _ -> throw ExportStorageFullException(50_000_000) })

        val ended = job.run()

        assertEquals(ExportState.Failed("Book", "Not enough free storage to export this book. It needs about 48 MB."), ended)
        assertFalse(temp.exists())
        assertTrue(targetDeleted)
    }

    @Test
    fun `a target that cannot be written is a failure with a message, and the temp file is removed`() {
        val job =
            job({ out, _, _ ->
                out.writeBytes(ByteArray(10))
                ExportResult(10, 1)
            }, open = { throw IOException("no space left") })

        val ended = job.run() as ExportState.Failed

        assertTrue("no space left" in ended.message)
        assertFalse(temp.exists())
        assertTrue(targetDeleted)
    }
}
