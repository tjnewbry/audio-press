# Audio Press: licences and notices

Audio Press is free software under the MIT licence, Copyright (c) 2026 tjnewbry. It is built from the works below. Their licences require that these notices reach the people who use the app. This file is the first part of the app's Licences screen. The build appends the notices of the Bake module (`app/bake/THIRD-PARTY-NOTICES.md`) and then the full licence texts: `app/bake/licenses/` (Apache-2.0, and the MIT licence of kokoro-onnx) and `app/audiopress/licenses/` (the MIT licence of Bouncy Castle and the NOTICE of PdfBox-Android).

PdfBox-Android (version 2.0.27.0) is under Apache-2.0, like the Apache PDFBox code it is ported from. Its NOTICE file, taken from the project's `v2.0.27.0` tag because the published library does not include it, is in `app/audiopress/licenses/NOTICE-pdfbox-android.txt` and on the Licences screen.

## Libraries in the app

| Work | Licence | Use |
|------|---------|-----|
| Kotlin and its standard library, Jetpack Compose, Material 3, AndroidX (core, activity, lifecycle) by Google and JetBrains | Apache-2.0 | The app itself and its screens |
| PdfBox-Android by Tom Roush, a port of Apache PDFBox by the Apache Software Foundation | Apache-2.0 | Reads the text of PDF files |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil`) by the Legion of the Bouncy Castle | MIT (Bouncy Castle licence) | Opens encrypted PDF files |
| ONNX Runtime for Android by Microsoft | MIT | Runs the voice models |

## Voices and speech

The works used to turn text into speech with Kokoro are listed in the next section, with their sources.

The 84 short voice samples shipped in the app (`src/main/assets/voices`, Ogg Opus, three model qualities of 28 voices each) are output of Kokoro-82M v1.0 (Apache-2.0) using voices by hexgrad, and are shared under the same Apache-2.0 licence.

