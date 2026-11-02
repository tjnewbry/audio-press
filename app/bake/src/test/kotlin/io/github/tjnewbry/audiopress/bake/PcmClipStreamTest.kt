package io.github.tjnewbry.audiopress.bake

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmClipStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun clip(
        name: String,
        samples: ShortArray,
        rate: Int = 24000,
    ): File {
        val file = temp.newFile(name)
        PcmWavWriter(file, rate).use { w -> w.write(FloatArray(samples.size) { samples[it] / 32768f }) }
        return file
    }

    private fun sampleAt(
        bytes: ByteArray,
        byteOffset: Int,
    ): Int = ((bytes[byteOffset].toInt() and 0xFF) or (bytes[byteOffset + 1].toInt() shl 8)).toShort().toInt()

    @Test
    fun `joins the clips into one continuous stream in order`() {
        val a = clip("a.wav", ShortArray(1000) { 100 })
        val b = clip("b.wav", ShortArray(3001) { -200 })
        PcmClipStream(listOf(a, b)).use { stream ->
            assertEquals(24000, stream.sampleRate)
            assertEquals(4001L * 2, stream.totalBytes)
            val all = ByteArray(8002)
            var got = 0
            val buffer = ByteArray(700) // does not divide the clip sizes
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                buffer.copyInto(all, got, 0, n)
                got += n
            }
            assertEquals(8002, got)
            assertEquals(100, sampleAt(all, 0))
            assertEquals(100, sampleAt(all, 1998))
            assertEquals(-200, sampleAt(all, 2000))
            assertEquals(-200, sampleAt(all, 8000))
            assertEquals(-1, stream.read(buffer, 0, buffer.size))
        }
    }

    @Test
    fun `clips with different sample rates are refused`() {
        val a = clip("a.wav", ShortArray(100), 24000)
        val b = clip("b.wav", ShortArray(100), 22050)
        assertFailsWith<IOException> { PcmClipStream(listOf(a, b)) }
    }

    @Test
    fun `a missing or non-WAV clip is an IOException`() {
        assertFailsWith<IOException> { PcmClipStream(listOf(File(temp.root, "none.wav"))) }
        val junk = temp.newFile("junk.wav").apply { writeBytes(ByteArray(100)) }
        assertFailsWith<IOException> { PcmClipStream(listOf(junk)) }
    }

    @Test
    fun `an empty stream reads as the end`() {
        PcmClipStream(emptyList()).use { assertEquals(-1, it.read(ByteArray(10), 0, 10)) }
    }

    @Test
    fun `the space estimate is about a twelfth of the WAV size plus a margin`() {
        val oneHourMs = 3_600_000L
        val wavBytes = oneHourMs * 24000 * 2 / 1000
        val estimate = M4aExporter.estimatedBytes(oneHourMs)
        assertTrue(estimate > wavBytes / 12 && estimate < wavBytes / 5, "estimate $estimate for $wavBytes")
    }
}
