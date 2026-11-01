package io.github.tjnewbry.audiopress.bake

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes 16-bit mono PCM audio to a WAV file as it is produced, from the float samples (-1 to 1) a speech model
 * returns, and fixes the sizes in the header when closed. Memory use stays at one buffer, however long the audio.
 *
 * ```
 * PcmWavWriter(file, sampleRate = 24000).use { wav ->
 *     wav.write(samplesOfFirstSentence)
 *     wav.writeSilence(150)
 *     wav.write(samplesOfSecondSentence)
 * }
 * ```
 */
class PcmWavWriter(
    file: File,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    /** The audio written so far, in milliseconds. */
    val durationMs: Long get() = dataBytes * 1000 / (sampleRate * BYTES_PER_SAMPLE)

    init {
        out.setLength(0)
        out.write(header(0))
    }

    /** Appends [count] samples of [samples] (floats clamped to -1..1 and rounded to 16 bits). */
    fun write(
        samples: FloatArray,
        count: Int = samples.size,
    ) {
        check(!closed) { "the writer is closed" }
        require(count in 0..samples.size) { "count is outside the samples" }
        var offset = 0
        val buffer = ByteBuffer.allocate(CHUNK_SAMPLES * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        while (offset < count) {
            val n = minOf(CHUNK_SAMPLES, count - offset)
            buffer.clear()
            for (i in 0 until n) buffer.putShort(toPcm16(samples[offset + i]))
            out.write(buffer.array(), 0, n * BYTES_PER_SAMPLE)
            offset += n
            dataBytes += n * BYTES_PER_SAMPLE
        }
    }

    /** Appends [ms] milliseconds of silence. */
    fun writeSilence(ms: Long) {
        check(!closed) { "the writer is closed" }
        var remaining = ms * sampleRate / 1000 * BYTES_PER_SAMPLE
        val zeros = ByteArray(ZERO_BLOCK_BYTES)
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size.toLong()).toInt()
            out.write(zeros, 0, n)
            remaining -= n
            dataBytes += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            out.seek(0)
            out.write(header(dataBytes))
        } finally {
            out.close()
        }
    }

    private fun header(dataSize: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.ISO_8859_1))
        b.putInt((36 + dataSize).toInt())
        b.put("WAVEfmt ".toByteArray(Charsets.ISO_8859_1))
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(1) // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * BYTES_PER_SAMPLE)
        b.putShort(BYTES_PER_SAMPLE.toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.ISO_8859_1))
        b.putInt(dataSize.toInt())
        return b.array()
    }

    companion object {
        /** Kokoro's output rate. */
        const val DEFAULT_SAMPLE_RATE = 24000
        private const val BYTES_PER_SAMPLE = 2
        private const val HEADER_BYTES = 44
        private const val CHUNK_SAMPLES = 8192
        private const val ZERO_BLOCK_BYTES = 16384

        internal fun toPcm16(sample: Float): Short {
            if (sample.isNaN()) return 0
            val scaled = Math.round(sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
            return scaled.toShort()
        }
    }
}
