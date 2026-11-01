package io.github.tjnewbry.audiopress.bake

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WavTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads the length of a WAV file`() {
        val file = temp.newFile("a.wav")
        Wav.writeSilence(file, 1500)

        assertEquals(1500L, Wav.durationMs(file))
        assertEquals(44L + 72000L, file.length(), "1.5 s of 24 kHz 16-bit mono")
    }

    @Test
    fun `a data size of zero or too large means until the end of the file`() {
        val file = temp.newFile("stream.wav")
        Wav.writeSilence(file, 1000)
        val bytes = file.readBytes()
        for (size in listOf(0, -1)) {
            for (i in 0..3) bytes[40 + i] = (size shr (8 * i)).toByte()
            file.writeBytes(bytes)

            assertEquals(1000L, Wav.durationMs(file), "data size $size")
        }
    }

    @Test
    fun `skips chunks it does not know, even odd-sized ones`() {
        val plain = temp.newFile("plain.wav")
        Wav.writeSilence(plain, 1000)
        val bytes = plain.readBytes()
        val list = "LIST".toByteArray() + byteArrayOf(3, 0, 0, 0) + byteArrayOf(1, 2, 3, 0) // 3 bytes plus a pad byte
        val withList = temp.newFile("list.wav")
        withList.writeBytes(bytes.copyOfRange(0, 36) + list + bytes.copyOfRange(36, bytes.size))

        assertEquals(1000L, Wav.durationMs(withList))
    }

    @Test
    fun `files that are not WAV, or are missing, or are too short give null`() {
        assertNull(Wav.durationMs(temp.newFile("text.wav").apply { writeText("this is not a wave file at all, just text, long enough") }))
        assertNull(Wav.durationMs(temp.newFile("tiny.wav").apply { writeBytes(ByteArray(10)) }))
        assertNull(Wav.durationMs(temp.root.resolve("missing.wav")))
        assertNull(Wav.durationMs(temp.root))
    }

    @Test
    fun `a WAV with no audio has zero length`() {
        val file = temp.newFile("empty.wav")
        Wav.writeSilence(file, 0)

        assertEquals(0L, Wav.durationMs(file))
    }
}
