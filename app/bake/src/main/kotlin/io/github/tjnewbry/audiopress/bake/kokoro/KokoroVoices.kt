package io.github.tjnewbry.audiopress.bake.kokoro

/** The three downloadable qualities of Kokoro-82M v1.0. All share one voices file, so one download gives every voice. */
enum class KokoroQuality(
    /** Stable id for settings keys and file names. */
    val id: String,
    /** The name shown in the app. */
    val displayName: String,
    /** The model file name in the Kokoro v1.0 release. */
    val modelFileName: String,
) {
    LITE("lite", "Lite", "kokoro-v1.0.int8.onnx"),
    MEDIUM("medium", "Medium", "kokoro-v1.0.fp16.onnx"),
    HEAVY("heavy", "Heavy", "kokoro-v1.0.onnx"),
}

enum class KokoroAccent(
    val label: String,
    /** The language tag the phonemizer and the voice use. */
    val languageTag: String,
) {
    AMERICAN("American", "en-us"),
    BRITISH("British", "en-gb"),
}

enum class KokoroGender(
    val label: String,
) {
    FEMALE("Female"),
    MALE("Male"),
}

/** One Kokoro voice. [id] is the model's id, such as `am_santa`; the language follows its first letter. */
data class KokoroVoice(
    val id: String,
    val accent: KokoroAccent,
    val gender: KokoroGender,
) {
    /** The name shown in the app: the id after the underscore, capitalized (`am_santa` is "Santa"). */
    val name: String = id.substringAfter('_').replaceFirstChar { it.uppercase() }

    val languageTag: String get() = accent.languageTag

    /** "Santa (American Male)". */
    val label: String get() = "$name (${accent.label} ${gender.label})"
}

/** The 28 English voices of Kokoro v1.0, in the order the voice screen lists them. */
object KokoroVoices {
    val all: List<KokoroVoice> =
        listOf(
            voices(
                "af",
                KokoroAccent.AMERICAN,
                KokoroGender.FEMALE,
                "alloy",
                "aoede",
                "bella",
                "heart",
                "jessica",
                "kore",
                "nicole",
                "nova",
                "river",
                "sarah",
                "sky",
            ),
            voices(
                "am",
                KokoroAccent.AMERICAN,
                KokoroGender.MALE,
                "adam",
                "echo",
                "eric",
                "fenrir",
                "liam",
                "michael",
                "onyx",
                "puck",
                "santa",
            ),
            voices("bf", KokoroAccent.BRITISH, KokoroGender.FEMALE, "alice", "emma", "isabella", "lily"),
            voices("bm", KokoroAccent.BRITISH, KokoroGender.MALE, "daniel", "fable", "george", "lewis"),
        ).flatten()

    /** The voice with this id, or null. */
    fun byId(id: String): KokoroVoice? = all.firstOrNull { it.id == id }

    private fun voices(
        prefix: String,
        accent: KokoroAccent,
        gender: KokoroGender,
        vararg names: String,
    ): List<KokoroVoice> = names.map { KokoroVoice("${prefix}_$it", accent, gender) }
}
