package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.PcmWavWriter
import io.github.tjnewbry.audiopress.bake.SynthesisResult
import io.github.tjnewbry.audiopress.core.Box
import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.Segment
import io.github.tjnewbry.audiopress.core.SegmentKind
import io.github.tjnewbry.audiopress.core.Span
import io.github.tjnewbry.audiopress.core.SpeechChunker
import io.github.tjnewbry.audiopress.core.Token
import io.github.tjnewbry.audiopress.core.Transcript
import java.io.File

/**
 * The model-specific part of Kokoro: turns one short piece of text into audio samples. The ONNX implementation
 * (and its phonemizer) plugs in here; everything around it (splitting, pauses, WAV writing, cancellation, error
 * mapping) is [KokoroSpeechSynthesizer].
 *
 * An engine is used by one thread at a time. [close] frees the model.
 */
interface KokoroEngine : AutoCloseable {
    /** The samples per second of the audio [synthesize] returns. Kokoro's is 24,000. */
    val sampleRate: Int get() = PcmWavWriter.DEFAULT_SAMPLE_RATE

    /**
     * Speaks [text] (at most [KokoroTextSplitter.MAX_CHARS] characters, a sentence or two) as float samples from -1
     * to 1. It may throw [OutOfMemoryError]; that is reported as running out of memory.
     */
    fun synthesize(
        text: String,
        voice: KokoroVoice,
        speed: Float,
    ): FloatArray
}

/**
 * Splits text into pieces short enough for one run of the Kokoro model, which takes at most 510 phoneme tokens
 * (about 400 characters of English; the limit here is lower to leave room). It cuts at sentence ends first, with the
 * same rules as the live player's [SpeechChunker] (abbreviations, initials, closing quotes), then after commas, then
 * at spaces.
 */
object KokoroTextSplitter {
    const val MAX_CHARS = 250

    /** The pieces of [text], in order, without leading or trailing spaces. Empty for blank text. */
    fun split(
        text: String,
        maxChars: Int = MAX_CHARS,
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val box = Box(0f, 0f, 0f, 0f)
        val tokens =
            trimmed.split(' ', '\n', '\t', '\r').filter { it.isNotEmpty() }.mapIndexed {
                i,
                word,
                ->
                Token(word, i > 0, listOf(Span(1, box)))
            }
        val transcript = Transcript(listOf(Segment(SegmentKind.PARAGRAPH, tokens)))
        return SpeechChunker(maxChars).chunk(transcript).map { it.text }
    }
}

/**
 * A [KokoroSynthesizer] built from a [KokoroEngine]: splits each text into pieces, runs the engine on them one at a
 * time on the calling thread, joins them with short pauses, and writes a 16-bit WAV.
 *
 * [cancellation] is checked before every piece, so a cancel takes effect within one engine run (a second or two on a
 * phone, since a model run cannot be interrupted). An engine that runs out of memory gives
 * [SynthesisResult.OUT_OF_MEMORY]; any other failure gives [SynthesisResult.FAILED]. Closing it closes the engine.
 */
class KokoroSpeechSynthesizer(
    private val engine: KokoroEngine,
    private val voice: KokoroVoice,
    private val speed: Float = 1f,
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
) : KokoroSynthesizer {
    override fun synthesize(
        text: String,
        target: File,
        cancellation: CancellationToken?,
    ): SynthesisResult {
        try {
            PcmWavWriter(target, engine.sampleRate).use { wav ->
                for ((i, piece) in KokoroTextSplitter.split(text).withIndex()) {
                    if (cancellation?.isCancelled == true) return SynthesisResult.CANCELLED
                    val samples = engine.synthesize(piece, voice, speed)
                    if (i > 0) wav.writeSilence(pauseMs)
                    wav.write(samples)
                }
            }
            return SynthesisResult.SUCCESS
        } catch (e: OutOfMemoryError) {
            return SynthesisResult.OUT_OF_MEMORY
        } catch (e: Exception) {
            return SynthesisResult.FAILED
        }
    }

    override fun close() = engine.close()

    companion object {
        /** Between two pieces of one utterance, on top of the pause the model puts after punctuation. */
        const val DEFAULT_PAUSE_MS = 120L
    }
}
