package io.github.tjnewbry.audiopress.bake

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmWavWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun shorts(bytes: ByteArray): List<Short> {
        val b = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return List((bytes.size - 44) / 2) { b.short }
    }

    @Test
    fun `writes a valid WAV whose length is read back by the header scanner`() {
        val file = temp.newFile("a.wav")

        PcmWavWriter(file, 24000).use { wav ->
            wav.write(FloatArray(24000) { 0.1f }) // 1 s
            wav.writeSilence(500)
            wav.write(FloatArray(12000)) // 0.5 s
        }

        assertEquals(2000L, Wav.durationMs(file))
        assertEquals(44L + 2 * 48000, file.length())
    }

    @Test
    fun `floats become 16-bit samples, clamped and rounded, with NaN as silence`() {
        val file = temp.newFile("b.wav")

        PcmWavWriter(file).use { it.write(floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -3f, Float.NaN)) }

        assertEquals(listOf<Short>(0, 32767, -32767, 16384, 32767, -32767, 0), shorts(file.readBytes()))
    }

    @Test
    fun `the header sizes are correct`() {
        val file = temp.newFile("c.wav")
        PcmWavWriter(file, 24000).use { it.write(FloatArray(1000)) }

        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(36 + 2000, b.getInt(4))
        assertEquals(24000, b.getInt(24))
        assertEquals(48000, b.getInt(28))
        assertEquals(2000, b.getInt(40))
    }

    @Test
    fun `audio written in pieces equals audio written at once, and count limits what is written`() {
        val samples = FloatArray(20000) { (it % 100) / 100f }
        val a = temp.newFile("d.wav")
        val b = temp.newFile("e.wav")

        PcmWavWriter(a).use { it.write(samples) }
        PcmWavWriter(b).use {
            it.write(samples, 7000)
            it.write(samples.copyOfRange(7000, 20000))
        }

        assertEquals(a.readBytes().toList(), b.readBytes().toList())
        val c = temp.newFile("f.wav")
        PcmWavWriter(c).use { it.write(samples, 10) }
        assertEquals(44L + 20, c.length())
    }

    @Test
    fun `an empty file is a valid WAV of zero length, and a closed writer cannot be written to`() {
        val file = temp.newFile("g.wav")
        val wav = PcmWavWriter(file)
        wav.close()
        wav.close()

        assertEquals(0L, Wav.durationMs(file))
        assertFailsWith<IllegalStateException> { wav.write(FloatArray(1)) }
        assertFailsWith<IllegalStateException> { wav.writeSilence(10) }
    }

    @Test
    fun `an existing file is overwritten and the duration is tracked while writing`() {
        val file = temp.newFile("h.wav").apply { writeBytes(ByteArray(5000) { 7 }) }

        PcmWavWriter(file, 24000).use {
            it.write(FloatArray(2400))
            assertEquals(100L, it.durationMs)
        }

        assertEquals(44L + 4800, file.length())
    }
}
