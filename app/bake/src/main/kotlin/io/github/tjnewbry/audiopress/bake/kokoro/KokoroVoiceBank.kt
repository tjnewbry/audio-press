package io.github.tjnewbry.audiopress.bake.kokoro

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads voice styles from `voices-v1.0.bin`, which is a zip of NumPy arrays: one `<voice id>.npy` per voice, holding
 * 510 rows of 256 float32 values (shape 510 x 1 x 256). Row `n - 1` is the style for an utterance of `n` phoneme
 * tokens, as in Kokoro's own pipeline. Each voice is read once and kept (about 0.5 MB).
 */
class KokoroVoiceBank(
    file: File,
) : Closeable {
    private val zip = ZipFile(file)
    private val voices = HashMap<String, FloatArray>()

    /** The 256-value style for [voiceId] when the text has [tokenCount] phoneme tokens (without the two pads). */
    @Synchronized
    fun style(
        voiceId: String,
        tokenCount: Int,
    ): FloatArray {
        val all = voices.getOrPut(voiceId) { load(voiceId) }
        val row = (tokenCount - 1).coerceIn(0, ROWS - 1)
        return all.copyOfRange(row * STYLE_SIZE, (row + 1) * STYLE_SIZE)
    }

    private fun load(voiceId: String): FloatArray {
        val entry = zip.getEntry("$voiceId.npy") ?: throw IOException("the voices file has no voice $voiceId")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return parseNpy(bytes, voiceId)
    }

    override fun close() = zip.close()

    companion object {
        const val ROWS = 510
        const val STYLE_SIZE = 256

        /** The float32 values of a `.npy` file holding [ROWS] x 1 x [STYLE_SIZE] values, in file order. */
        internal fun parseNpy(
            bytes: ByteArray,
            name: String = "array",
        ): FloatArray {
            if (bytes.size < 12 || bytes[0] != 0x93.toByte() || String(bytes, 1, 5, Charsets.ISO_8859_1) != "NUMPY") {
                throw IOException("$name is not a NumPy array")
            }
            val order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val major = bytes[6].toInt()
            val headerLength = if (major == 1) order.getShort(8).toInt() and 0xFFFF else order.getInt(8)
            val dataStart = (if (major == 1) 10 else 12) + headerLength
            val header = String(bytes, dataStart - headerLength, headerLength, Charsets.ISO_8859_1)
            if (!header.contains("'<f4'") ||
                header.contains("'fortran_order': True")
            ) {
                throw IOException("$name is not little-endian float32 data")
            }
            val count = ROWS * STYLE_SIZE
            if (bytes.size - dataStart != count * 4) throw IOException("$name has the wrong size for a voice")
            val values = FloatArray(count)
            order.position(dataStart)
            order.asFloatBuffer().get(values)
            return values
        }
    }
}
