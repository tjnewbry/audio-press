package io.github.tjnewbry.audiopress.bake

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Where the samples of a 16-bit mono PCM WAV file are. */
internal data class PcmClip(
    val sampleRate: Int,
    val dataOffset: Long,
    val dataBytes: Long,
)

/** Reads WAV headers for the exporter. A data size of 0 or one larger than the file means "to the end", as in [Wav]. */
internal object PcmWavHeader {
    fun read(file: File): PcmClip {
        if (!file.isFile) throw IOException("${file.name} is missing")
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val riff = ByteArray(12)
            if (length < riff.size) throw IOException("${file.name} is not a WAV file")
            raf.readFully(riff)
            if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(riff, 8, 4, Charsets.ISO_8859_1) != "WAVE") {
                throw IOException("${file.name} is not a WAV file")
            }
            var rate = 0
            var position = 12L
            val header = ByteArray(8)
            while (position + header.size <= length) {
                raf.seek(position)
                raf.readFully(header)
                val id = String(header, 0, 4, Charsets.ISO_8859_1)
                val size = int(header, 4)
                val body = position + header.size
                if (id == "fmt " && size >= FMT_BYTES) {
                    val fmt = ByteArray(FMT_BYTES)
                    raf.readFully(fmt)
                    val format = short(fmt, 0)
                    val channels = short(fmt, 2)
                    rate = int(fmt, 4).toInt()
                    val bits = short(fmt, 14)
                    if (format != 1 || channels != 1 || bits != 16) {
                        throw IOException("${file.name} is not 16-bit mono PCM audio")
                    }
                } else if (id == "data") {
                    if (rate <= 0) throw IOException("${file.name} has no format information")
                    val available = length - body
                    val bytes = if (size == 0L || size > available) available else size
                    return PcmClip(rate, body, bytes and 1L.inv())
                }
                position = body + size + (size and 1L)
            }
            throw IOException("${file.name} has no audio data")
        }
    }

    private fun short(
        b: ByteArray,
        o: Int,
    ) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun int(
        b: ByteArray,
        o: Int,
    ): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (b[o + i].toLong() and 0xFF)
        return value
    }

    private const val FMT_BYTES = 16
}

/**
 * The sample data of [files] as one continuous byte stream, read a buffer at a time so memory stays small however
 * long the book is. All clips must share one sample rate ([sampleRate]); [totalBytes] is the size of the whole stream.
 */
internal class PcmClipStream(
    private val files: List<File>,
) : Closeable {
    private val clips: List<PcmClip> = files.map { PcmWavHeader.read(it) }

    val sampleRate: Int = clips.firstOrNull()?.sampleRate ?: 0
    val totalBytes: Long = clips.sumOf { it.dataBytes }

    private var index = 0
    private var remaining = 0L
    private var current: RandomAccessFile? = null

    init {
        val odd = clips.indexOfFirst { it.sampleRate != sampleRate }
        if (odd >= 0) throw IOException("${files[odd].name} has a different sample rate from the first clip")
    }

    /** Reads up to [length] bytes into [buffer] at [offset], or returns -1 at the end of the last clip. */
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var filled = 0
        while (filled < length) {
            if (remaining == 0L && !openNext()) break
            val want = minOf((length - filled).toLong(), remaining).toInt()
            val n = current!!.read(buffer, offset + filled, want)
            if (n < 0) {
                remaining = 0 // the file is shorter than its header says: carry on with the next clip
                continue
            }
            remaining -= n
            filled += n
        }
        return if (filled == 0 && length > 0) -1 else filled
    }

    private fun openNext(): Boolean {
        current?.close()
        current = null
        if (index >= clips.size) return false
        val clip = clips[index]
        current = RandomAccessFile(files[index], "r").also { it.seek(clip.dataOffset) }
        remaining = clip.dataBytes
        index++
        return true
    }

    override fun close() {
        current?.close()
        current = null
    }
}
