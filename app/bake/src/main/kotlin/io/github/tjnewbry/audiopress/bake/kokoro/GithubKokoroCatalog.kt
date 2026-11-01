package io.github.tjnewbry.audiopress.bake.kokoro

/**
 * The Kokoro v1.0 files from the `model-files-v1.0` release of `thewh1teagle/kokoro-onnx` (MIT; the weights are
 * Apache-2.0). GitHub publishes no checksums for these assets, so the SHA-256 values were computed from the files
 * and are pinned here: a replaced file fails the check.
 *
 * A mirror with the same weights is `onnx-community/Kokoro-82M-v1.0-ONNX` on Hugging Face (different graph input
 * names, so it is not a drop-in replacement for these files).
 */
object GithubKokoroCatalog : KokoroCatalog {
    private const val BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"

    private val models =
        mapOf(
            KokoroQuality.LITE to
                KokoroFileSpec(
                    "kokoro-v1.0.int8.onnx",
                    BASE + "kokoro-v1.0.int8.onnx",
                    92_361_271,
                    "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb",
                ),
            KokoroQuality.MEDIUM to
                KokoroFileSpec(
                    "kokoro-v1.0.fp16.onnx",
                    BASE + "kokoro-v1.0.fp16.onnx",
                    177_464_787,
                    "c1610a859f3bdea01107e73e50100685af38fff88f5cd8e5c56df109ec880204",
                ),
            KokoroQuality.HEAVY to
                KokoroFileSpec(
                    "kokoro-v1.0.onnx",
                    BASE + "kokoro-v1.0.onnx",
                    325_532_387,
                    "7d5df8ecf7d4b1878015a32686053fd0eebe2bc377234608764cc0ef3636a6c5",
                ),
        )

    override fun model(quality: KokoroQuality): KokoroFileSpec = models.getValue(quality)

    override val voices =
        KokoroFileSpec(
            "voices-v1.0.bin",
            BASE + "voices-v1.0.bin",
            28_214_398,
            "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d",
        )

    /** The whole download for [quality] (its model plus the voices file) in bytes. */
    fun totalBytes(quality: KokoroQuality): Long = model(quality).sizeBytes + voices.sizeBytes
}

/** Bytes as the whole decimal megabytes the download button shows (1 MB = 1,000,000 bytes), rounded: 120,575,669 is 121. */
fun Long.toDecimalMegabytes(): Int = ((this + 500_000) / 1_000_000).toInt()
