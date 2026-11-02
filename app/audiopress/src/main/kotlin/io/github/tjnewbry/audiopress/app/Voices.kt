package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.bake.kokoro.KokoroQuality
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoices

/** The three Kokoro model qualities the user can download for baking; they share one voices file. */
typealias VoiceQuality = KokoroQuality

/** The name shown on the tab, such as `Lite`. */
val VoiceQuality.label: String get() = displayName

/** The folder of this quality's samples in the app's assets. */
val VoiceQuality.assetFolder: String get() = id

/** The note shown under the tabs: always three lines high, so the list below never moves. */
val VoiceQuality.note: String
    get() =
        when (this) {
            VoiceQuality.LITE -> "Smallest download,\nUses the least memory when baking,\nSounds nearly the same as Heavy."
            VoiceQuality.MEDIUM -> "\nBalanced option."
            VoiceQuality.HEAVY -> "Largest download,\nUses the most memory when baking,\nHighest fidelity."
        }

/** Where the sample of this voice at [quality] is, in the app's assets. */
fun KokoroVoice.sampleAsset(quality: VoiceQuality): String = "voices/${quality.assetFolder}/$id.ogg"

/** The 28 English voices, grouped American female, American male, British female, British male. */
val KOKORO_VOICES: List<KokoroVoice> = KokoroVoices.all

/** The voice with [id]. */
fun voiceById(id: String): KokoroVoice = KokoroVoices.byId(id) ?: error("Unknown voice $id")

/** A voice and the quality to bake it at. */
data class VoiceChoice(
    val voiceId: String,
    val quality: VoiceQuality,
) {
    val voice: KokoroVoice get() = voiceById(voiceId)
}

/** The button at the bottom of the voice screen. */
data class VoiceAction(
    val text: String,
    val enabled: Boolean,
)

/**
 * What the voice screen shows: the selected quality tab and the selected voice. Switching tabs deselects the voice.
 */
data class VoicePicker(
    val quality: VoiceQuality,
    val voice: KokoroVoice? = null,
) {
    /** Switching tabs keeps the selected voice; the button then names the new quality. */
    fun withQuality(selected: VoiceQuality): VoicePicker = copy(quality = selected)

    fun withVoice(selected: KokoroVoice): VoicePicker = copy(voice = selected)

    /** The text of the three-line note under the tabs. */
    val note: String get() = quality.note

    /** The bottom button, which depends on the voice and on whether this quality is already on the phone. */
    fun action(
        downloaded: Boolean,
        downloadMb: Int = 0,
    ): VoiceAction {
        val chosen = voice ?: return VoiceAction("Select a voice", enabled = false)
        val named = quality.label.lowercase()
        return if (downloaded) {
            VoiceAction("Bake audio using ${chosen.name} ($named)", enabled = true)
        } else {
            VoiceAction("Download $named (${downloadMb}mb) and\nbake audio using ${chosen.name} ($named)", enabled = true)
        }
    }

    /** The choice to bake with, or null while no voice is selected. */
    val choice: VoiceChoice? get() = voice?.let { VoiceChoice(it.id, quality) }
}

/**
 * The quality tab the voice screen opens on: Medium when it is downloaded, else Lite, else Heavy, else Medium (nothing is
 * downloaded, so Medium is the one to offer).
 */
fun defaultQuality(isDownloaded: (VoiceQuality) -> Boolean): VoiceQuality =
    listOf(VoiceQuality.MEDIUM, VoiceQuality.LITE, VoiceQuality.HEAVY).firstOrNull(isDownloaded) ?: VoiceQuality.MEDIUM
