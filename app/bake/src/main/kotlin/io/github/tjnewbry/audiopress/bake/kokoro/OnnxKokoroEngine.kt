package io.github.tjnewbry.audiopress.bake.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Turns text into misaki phonemes, the alphabet Kokoro was trained on (see `docs`: misaki's English G2P). */
interface KokoroPhonemizer {
    /** The phonemes for [text], spoken by [voice] (its accent picks the US or GB pronunciations). */
    fun phonemize(
        text: String,
        voice: KokoroVoice,
    ): String
}

/**
 * Runs Kokoro-82M with ONNX Runtime on the CPU. One session is created for the whole bake (loading takes a few
 * seconds and a lot of memory) and closed with [close].
 *
 * - [threads] is the intra-op thread count. More threads than fast cores can be slower, so measure (the benchmark test
 *   does) and start from 2.
 * - Each run takes at most [MAX_TOKENS] phoneme tokens with a pad token at each end. A piece with more tokens is cut
 *   in two at a sentence end, comma, or space and the audio is joined.
 * - The style vector is row `tokens - 1` of the voice, as in Kokoro's own pipeline.
 * - Output with NaN or infinite samples, or silence for a real phrase, is an error ([IllegalStateException]), not
 *   audio, so a broken run is retried instead of baked.
 *
 * Use it from one thread at a time. A run cannot be interrupted, so cancelling waits for the current piece.
 */
class OnnxKokoroEngine(
    files: KokoroModelFiles,
    private val vocab: KokoroVocab,
    private val phonemizer: KokoroPhonemizer,
    threads: Int = DEFAULT_THREADS,
) : KokoroEngine {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    private val session: OrtSession = env.createSession(files.model.path, options)
    private val voices = KokoroVoiceBank(files.voices)

    override val sampleRate: Int get() = SAMPLE_RATE

    override fun synthesize(
        text: String,
        voice: KokoroVoice,
        speed: Float,
    ): FloatArray {
        val phonemes = phonemizer.phonemize(text, voice)
        val ids = vocab.encode(phonemes)
        if (ids.isEmpty()) return FloatArray(0)
        if (ids.size <= MAX_TOKENS) return run(ids, voice, speed)
        // Too long for one run: cut the text in two and speak both halves.
        val halves = KokoroTextSplitter.split(text, maxChars = maxOf(MIN_SPLIT_CHARS, text.length / 2))
        if (halves.size < 2) return run(ids.copyOf(MAX_TOKENS), voice, speed)
        return halves.map { synthesize(it, voice, speed) }.reduce { a, b -> a + b }
    }

    /** Runs the model on [ids] (1 to [MAX_TOKENS] phoneme ids, without the pads). Used by the benchmark too. */
    fun run(
        ids: IntArray,
        voice: KokoroVoice,
        speed: Float,
    ): FloatArray {
        require(ids.size in 1..MAX_TOKENS) { "a run takes 1 to $MAX_TOKENS tokens, not ${ids.size}" }
        val padded = LongArray(ids.size + 2).also { for (i in ids.indices) it[i + 1] = ids[i].toLong() }
        val style = voices.style(voice.id, ids.size)
        OnnxTensor.createTensor(env, LongBuffer.wrap(padded), longArrayOf(1, padded.size.toLong())).use { tokens ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong())).use { styleTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { speedTensor ->
                    val inputs = mapOf(TOKENS to tokens, STYLE to styleTensor, SPEED to speedTensor)
                    session.run(inputs).use { result ->
                        val audio = (result[0] as OnnxTensor).floatBuffer
                        val samples = FloatArray(audio.remaining())
                        audio.get(samples)
                        check(isValid(samples, ids.size)) { "the model produced invalid audio" }
                        return samples
                    }
                }
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } finally {
            try {
                options.close()
            } finally {
                try {
                    voices.close()
                } finally {
                    (phonemizer as? AutoCloseable)?.close()
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 24000

        /** The model's limit on phoneme tokens, not counting the pad at each end. */
        const val MAX_TOKENS = 510
        const val DEFAULT_THREADS = 2

        private const val TOKENS = "tokens"
        private const val STYLE = "style"
        private const val SPEED = "speed"
        private const val MIN_SPLIT_CHARS = 20
        private const val REAL_PHRASE_TOKENS = 5

        /** False for audio with NaN or infinite samples, or only zeros for [tokens] phonemes (a real phrase, not a pause). */
        internal fun isValid(
            samples: FloatArray,
            tokens: Int,
        ): Boolean {
            var allZero = true
            for (s in samples) {
                if (s.isNaN() || s.isInfinite()) return false
                if (s != 0f) allZero = false
            }
            return !(allZero && tokens >= REAL_PHRASE_TOKENS)
        }
    }
}
