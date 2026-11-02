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

    /** misaki's dictionaries (Apache-2.0, hexgrad/misaki at the pinned commit) and the unknown-word model. */
    override val frontEnd: List<KokoroFileSpec> =
        listOf(
            misaki("us_gold.json", 3_000_469, "dc414872a49a28ae6c141463d502fd945f3b2fde040484fdc47d00cc4612686f"),
            misaki("us_silver.json", 3_099_517, "de8f67be911bb6c659187b4a65fd966b6a30e56350e0f790d763210b053ac475"),
            misaki("gb_gold.json", 2_838_552, "29e62f4b60261c88f7f3c2c7811ca3825978948090b72d2b27d565b729282f71"),
            misaki("gb_silver.json", 3_663_898, "48131e2d92ccc41655f4543e87e0f938e71463eb5a54be7f0693bb712ebb6bce"),
            // PeterReid's BART grapheme-to-phoneme model (Apache-2.0 tag) exported to ONNX by nekwo (US only for now).
            KokoroFileSpec(
                BART_US,
                "https://huggingface.co/nekwo/misaki-en-us-g2p-onnx/resolve/4b3a605d2f605318ab4f762ab9855c44106528b7/misaki-en_us-g2p.onnx",
                3_438_244,
                "757759c3e3ccb0bf59c379b1671c9834a27543ceaefd6846850823633b9665a1",
            ),
        )

    private fun misaki(
        name: String,
        size: Long,
        sha256: String,
    ) = KokoroFileSpec(
        name,
        "https://raw.githubusercontent.com/hexgrad/misaki/fba1236595f2d2bf21d414ba6e57d25256afada3/misaki/data/$name",
        size,
        sha256,
    )

    const val BART_US = "misaki-en_us-g2p.onnx"

    /** The dictionary file name for [accent] and [kind] ("gold" or "silver"). */
    fun dictionaryName(
        accent: KokoroAccent,
        kind: String,
    ) = (if (accent == KokoroAccent.BRITISH) "gb" else "us") + "_" + kind + ".json"

    /** The whole download for [quality] (its model, the voices file, and the front-end files) in bytes. */
    fun totalBytes(quality: KokoroQuality): Long = model(quality).sizeBytes + voices.sizeBytes + frontEnd.sumOf { it.sizeBytes }
}

/** Bytes as the whole decimal megabytes the download button shows (1 MB = 1,000,000 bytes), rounded: 120,575,669 is 121. */
fun Long.toDecimalMegabytes(): Int = ((this + 500_000) / 1_000_000).toInt()
