package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.GithubKokoroCatalog
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroModelStore
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroPhonemizer
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice
import java.io.File
import java.io.IOException

/**
 * The whole English front end for Kokoro, built from the downloaded files: the text normalizer, misaki's dictionaries
 * (loaded when a voice of that accent is first used, and kept for the length of the bake), and the unknown-word
 * model. Close it to free them.
 */
class KokoroFrontEnd(
    private val dictionaries: (name: String) -> File?,
    private val unknownWordModel: File?,
) : KokoroPhonemizer,
    AutoCloseable {
    private val lexicons = HashMap<KokoroAccent, MisakiLexicon>()
    private val bart: BartG2pFallback? = unknownWordModel?.let { BartG2pFallback(mapOf(KokoroAccent.AMERICAN to it)) }
    private val phonemizer =
        MisakiEnglishPhonemizer(
            EnglishTextNormalizer(EnglishNumbers(EnglishNumberSpeller())),
            ::lexiconFor,
            bart,
        )

    constructor(store: KokoroModelStore) : this(
        { store.frontEndFile(it) },
        store.frontEndFile(GithubKokoroCatalog.BART_US),
    )

    override fun phonemize(
        text: String,
        voice: KokoroVoice,
    ): String = phonemizer.phonemize(text, voice)

    @Synchronized
    private fun lexiconFor(accent: KokoroAccent): MisakiLexicon =
        lexicons.getOrPut(accent) {
            fun load(kind: String): MisakiDictionary {
                val name = GithubKokoroCatalog.dictionaryName(accent, kind)
                val file = dictionaries(name) ?: throw IOException("the dictionary $name is not downloaded")
                return MisakiDictionary.load(file)
            }
            MisakiLexicon(accent == KokoroAccent.BRITISH, load("gold"), load("silver"))
        }

    override fun close() {
        bart?.close()
        lexicons.clear()
    }
}
