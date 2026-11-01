package io.github.tjnewbry.audiopress.bake

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Reads the length of WAV files, the format `TextToSpeech.synthesizeToFile` writes. Plain Kotlin, no Android classes. */
object Wav {
    /**
     * The playing time of the WAV file in milliseconds, or null if [file] is missing or is not a readable WAV.
     * A data size of 0 or one larger than the file (written by engines that stream) is taken to mean "to the end".
     */
    fun durationMs(file: File): Long? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { raf -> scan(raf) }
        } catch (e: IOException) {
            null
        }
    }

    private fun scan(raf: RandomAccessFile): Long? {
        val length = raf.length()
        if (length < MIN_BYTES) return null
        val riff = ByteArray(12)
        raf.readFully(riff)
        if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(riff, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var byteRate = 0L
        var position = 12L
        val header = ByteArray(8)
        while (position + header.size <= length) {
            raf.seek(position)
            raf.readFully(header)
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val size = littleEndian(header, 4)
            val body = position + header.size
            if (id == "fmt " && size >= FMT_BYTES) {
                val fmt = ByteArray(FMT_BYTES)
                raf.readFully(fmt)
                byteRate = littleEndian(fmt, 8)
            } else if (id == "data") {
                val available = length - body
                val bytes = if (size == 0L || size > available) available else size
                return if (byteRate > 0) bytes * 1000 / byteRate else null
            }
            position = body + size + (size and 1L)
        }
        return null
    }

    private fun littleEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    /** Writes [durationMs] of silence (16-bit mono PCM) to [file], for utterances the voice has nothing to say for. */
    fun writeSilence(
        file: File,
        durationMs: Long,
        sampleRate: Int = SILENCE_SAMPLE_RATE,
    ) {
        val dataBytes = (durationMs * sampleRate / 1000).toInt() * 2
        val out = ByteArrayOutputStream(MIN_BYTES + dataBytes)

        fun int(value: Int) = repeat(4) { out.write(value shr (8 * it) and 0xFF) }

        fun short(value: Int) = repeat(2) { out.write(value shr (8 * it) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        int(36 + dataBytes)
        out.write("WAVEfmt ".toByteArray(Charsets.ISO_8859_1))
        int(16)
        short(1) // PCM
        short(1) // mono
        int(sampleRate)
        int(sampleRate * 2)
        short(2)
        short(16)
        out.write("data".toByteArray(Charsets.ISO_8859_1))
        int(dataBytes)
        out.write(ByteArray(dataBytes))
        file.writeBytes(out.toByteArray())
    }

    private const val MIN_BYTES = 44
    private const val FMT_BYTES = 16
    private const val SILENCE_SAMPLE_RATE = 24000
}
