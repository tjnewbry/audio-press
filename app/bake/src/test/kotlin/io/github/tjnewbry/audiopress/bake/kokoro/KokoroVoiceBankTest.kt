package io.github.tjnewbry.audiopress.bake.kokoro

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KokoroVoiceBankTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A `.npy` file (version 1.0) with 510 x 1 x 256 float32 values, where value (row, i) is row + i / 1000. */
    private fun npy(
        descr: String = "<f4",
        rows: Int = 510,
        shape: String = "(510, 1, 256)",
    ): ByteArray {
        val header = "{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"
        val padded = header + " ".repeat((64 - (10 + header.length + 1) % 64) % 64) + "\n"
        val data = ByteBuffer.allocate(rows * 256 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until rows) for (i in 0 until 256) data.putFloat(row + i / 1000f)
        val out = ByteBuffer.allocate(10 + padded.length + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out
            .put(byteArrayOf(0x93.toByte()))
            .put("NUMPY".toByteArray())
            .put(1)
            .put(0)
            .putShort(padded.length.toShort())
        out.put(padded.toByteArray()).put(data.array())
        return out.array()
    }

    private fun voicesFile(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("voices.bin")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc =
                    java.util.zip
                        .CRC32()
                        .also { it.update(bytes) }
                        .value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `the style for n tokens is row n minus 1 of the voice`() {
        KokoroVoiceBank(voicesFile("af_bella.npy" to npy(), "am_santa.npy" to npy())).use { bank ->
            val style = bank.style("af_bella", tokenCount = 42)

            assertEquals(256, style.size)
            assertEquals(41f, style[0])
            assertEquals(41.255f, style[255], 0.001f)
            assertEquals(0f, bank.style("am_santa", 1)[0])
        }
    }

    @Test
    fun `token counts outside the table use the first or last row`() {
        KokoroVoiceBank(voicesFile("af_bella.npy" to npy())).use { bank ->
            assertEquals(0f, bank.style("af_bella", 0)[0])
            assertEquals(509f, bank.style("af_bella", 510)[0])
            assertEquals(509f, bank.style("af_bella", 9999)[0])
        }
    }

    @Test
    fun `an unknown voice or a damaged array is an error with a message`() {
        KokoroVoiceBank(voicesFile("af_bella.npy" to npy(), "bad.npy" to ByteArray(100), "int.npy" to npy(descr = "<i8"))).use { bank ->
            assertTrue(assertFailsWith<IOException> { bank.style("am_nobody", 5) }.message!!.contains("am_nobody"))
            assertFailsWith<IOException> { bank.style("bad", 5) }
            assertFailsWith<IOException> { bank.style("int", 5) }
        }
    }

    @Test
    fun `an array with the wrong number of rows is rejected`() {
        KokoroVoiceBank(voicesFile("short.npy" to npy(rows = 100, shape = "(100, 1, 256)"))).use { bank ->
            assertFailsWith<IOException> { bank.style("short", 5) }
        }
    }
}
