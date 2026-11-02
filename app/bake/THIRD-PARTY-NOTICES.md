# Third-party notices for `app/bake`

Bake turns text into speech with Kokoro, entirely on the phone. It uses the following works. All are under permissive licences (Apache-2.0 or MIT); no GPL or LGPL code or data is used. The app's own licence screen should list these (the app owner adds the aggregated list).

| Work | Licence | How it is used | Source and revision |
|------|---------|----------------|---------------------|
| **Kokoro-82M** model weights (`kokoro-v1.0.onnx`, `.int8.onnx`, `.fp16.onnx`, `voices-v1.0.bin`) by hexgrad | Apache-2.0 | Downloaded by the user on demand; never shipped in the app. | The ONNX files come from the `model-files-v1.0` release of thewh1teagle/kokoro-onnx (below). |
| **Kokoro-82M `config.json`** (the `vocab` map) by hexgrad | Apache-2.0 | Bundled as a resource in `src/main/resources/.../kokoro/config.json`, byte for byte (SHA-256 `5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f`). | huggingface.co/hexgrad/Kokoro-82M, revision `f3ff3571791e39611d31c381e3a41a3af07b4987` |
| **misaki** (English G2P) by hexgrad | Apache-2.0 | `MisakiLexicon` and `MisakiEnglishPhonemizer` are a Kotlin port of the English lexicon and G2P logic in `misaki/en.py` (lookup, stress, stemming, special cases, context). The code was changed to drop spaCy and `num2words`, which are replaced by `EnglishTextNormalizer` and `EnglishNumbers`. | github.com/hexgrad/misaki, commit `fba1236595f2d2bf21d414ba6e57d25256afada3` |
| **misaki dictionaries** `us_gold`, `us_silver`, `gb_gold`, `gb_silver` (`.json`) | Apache-2.0 | Downloaded with the voice model; never shipped in the app. | the same commit, `misaki/data/` (SHA-256 values in `GithubKokoroCatalog`) |
| **PeterReid BART grapheme-to-phoneme model** (US) | Apache-2.0 (licence tag on the Hugging Face repository) | Said what the dictionaries cannot. The two character tables in `BartG2pFallback` are copied from the model's `config.json`. | huggingface.co/PeterReid/graphemes_to_phonemes_en_us, revision `a5631b285d18d59483c32c0c3379cb9fac924f4b` |
| **ONNX export of that model** by nekwo | Apache-2.0 (licence tag) | Downloaded with the voice model; run with ONNX Runtime. | huggingface.co/nekwo/misaki-en-us-g2p-onnx, revision `4b3a605d2f605318ab4f762ab9855c44106528b7` |
| **kokoro-onnx** by thewh1teagle | MIT (`licenses/MIT-kokoro-onnx.txt`) | The model release hosts the downloaded ONNX files. Its tokenizer behaviour is followed in `KokoroVocab` and `OnnxKokoroEngine`: at most 510 phoneme tokens, a pad id 0 at each end, and voice row (tokens − 1). | github.com/thewh1teagle/kokoro-onnx, commit `3596b26764286a7de9d90c363e988d50578918e5` |
| **ONNX Runtime for Android** 1.28.0 | MIT | Runs the models (`com.microsoft.onnxruntime:onnxruntime-android`). Version 1.28.0 is pinned because later versions add network access and telemetry. | github.com/microsoft/onnxruntime |
| **MisakiSwift** (mlalma) and **speech-core** (soniqo) | Apache-2.0 | Read as references for the port; no code was copied. | github.com/mlalma/MisakiSwift, github.com/soniqo/speech-core |

The Apache-2.0 licence text is in `licenses/Apache-2.0.txt`. Where this module modifies an Apache-2.0 work (the misaki port), the changes are the ones described above.
