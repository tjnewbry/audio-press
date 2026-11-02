package io.github.tjnewbry.audiopress.bake.kokoro.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import java.io.File
import java.nio.LongBuffer

/**
 * Says words the dictionaries do not have, with PeterReid's small BART grapheme-to-phoneme model (about 750,000
 * parameters, trained on misaki's own gold dictionary; the model is the ONNX export by nekwo). It is what misaki itself
 * uses for unknown words. Good on English-like and invented words, only fair on names from other languages.
 *
 * - There is only a US model for now, so [accent] is ignored: British voices use the US model for unknown words, while
 *   their dictionaries still give British pronunciations for known ones. A per-accent file can be passed later.
 * - Decoding is greedy, for at most [MAX_STEPS] steps. Misaki's own call stops at 20, which cuts long words off.
 * - It is case-sensitive, as the model was trained, and a character it does not know counts as an unknown symbol.
 *
 * The session is created on first use and freed by [close]. Use it from one thread at a time.
 */
class BartG2pFallback(
    private val modelFiles: Map<KokoroAccent, File>,
) : UnknownWordFallback,
    AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<File, OrtSession>()

    override fun phonemize(
        word: String,
        accent: KokoroAccent,
    ): String? {
        val file = modelFiles[accent] ?: modelFiles[KokoroAccent.AMERICAN] ?: return null
        val session =
            sessions.getOrPut(
                file,
            ) { env.createSession(file.path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }) }
        val phonemes = decode(session, encode(word))
        return phonemes.ifEmpty { null }
    }

    private fun decode(
        session: OrtSession,
        input: LongArray,
    ): String {
        val out = ArrayList<Long>(MAX_STEPS + 1)
        out += BOS
        OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { encoder ->
            for (step in 0 until MAX_STEPS) {
                val next = nextId(session, encoder, out.toLongArray())
                out += next.toLong()
                if (next == EOS) break
            }
        }
        return toPhonemes(out)
    }

    /** One decoding step: the most likely next id after the ids decoded so far. */
    private fun nextId(
        session: OrtSession,
        encoder: OnnxTensor,
        decoded: LongArray,
    ): Int =
        OnnxTensor.createTensor(env, LongBuffer.wrap(decoded), longArrayOf(1, decoded.size.toLong())).use { decoder ->
            session.run(mapOf("input_ids" to encoder, "decoder_input_ids" to decoder)).use { result ->
                argmaxOfLastStep((result[0] as OnnxTensor).floatBuffer, decoded.size)
            }
        }

    /** The id with the highest score at the last decoder position of `logits` shaped [1, steps, VOCAB]. */
    private fun argmaxOfLastStep(
        logits: java.nio.FloatBuffer,
        steps: Int,
    ): Int {
        val start = (steps - 1) * VOCAB
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (id in 0 until VOCAB) {
            val score = logits.get(start + id)
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        private const val BOS = 1L
        private const val EOS = 2
        private const val UNKNOWN = 3L
        private const val VOCAB = 63

        /** The model has 64 positions; the start symbol takes one. */
        const val MAX_STEPS = 64
        private const val MAX_WORD_CHARS = 62

        /** The characters the model reads, by id (the first four are placeholders for the special ids). From the model's config.json. */
        const val GRAPHEME_CHARS = "____AIOWYbdfhijklmnpstuvwz'-.BCDEFGHJKLMNPQRSTUVXZacegoqrxy"

        /** The phoneme characters the model writes, by id. From the model's config.json. */
        const val PHONEME_CHARS = "____AIOWYbdfhijklmnpstuvwzæðŋɑɔəɛɜɡɪɹɾʃʊʌʒʔʤʧˈˌθᵊᵻ"

        /** `[1] + the id of each character (3 if the model does not know it) + [2]`. Longer words are cut. */
        internal fun encode(word: String): LongArray {
            val ids = word.take(MAX_WORD_CHARS).map { c -> GRAPHEME_CHARS.indexOf(c).let { if (it < 0) UNKNOWN else it.toLong() } }
            return (listOf(BOS) + ids + listOf(EOS.toLong())).toLongArray()
        }

        /** Drops the special ids (0 to 3) and writes the rest as phoneme characters. */
        internal fun toPhonemes(ids: List<Long>): String =
            buildString {
                for (id in ids) if (id > UNKNOWN && id < PHONEME_CHARS.length) append(PHONEME_CHARS[id.toInt()])
            }
    }
}
