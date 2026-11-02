# Audio Press

A Kotlin program that turns a PDF (usually a novel or textbook) into a clean transcript ready for a text-to-speech engine. It strips headers, footers, page numbers, footnotes, legal notices, and formatting artifacts, so the result sounds like an audiobook. A prototype Android app uses the engine to turn a PDF into an audiobook and play it with live captions.

* **lo** output: a readable transcript that records which page each passage came from.
* **hi** output (planned for Milestone Bravo): the lo transcript plus a map of where each word sits on the page, so a future app can start playback from a clicked word or highlight words as they are read.

Version 0.1.0 completes **Milestone Alpha** (text-based PDFs, lo output).

[Software Demo Video](https://www.youtube.com/watch?v=XiTV0JSx9-8) (android app)

[Engine Demo Video](https://www.youtube.com/watch?v=v48KNSDY9Ic) (the engine's command-line program)

## Repository layout

| Folder | What it is |
|--------|-----------|
| [engine/](engine/) | The PDF-to-transcript engine and command-line program. Self-contained, with its own docs, build, and tests. See [engine/docs/objective-and-scope.md](engine/docs/objective-and-scope.md) for the objective and milestone plan. |
| [app/](app/) | The Android app (Android 13+): import a PDF, keep a library of processed books, and play them as audiobooks with live captions, with live speech or audio baked to files. A working prototype, awaiting checks on a real phone; see [app/docs/objective-and-scope.md](app/docs/objective-and-scope.md). |

## Instructions for Build and Use

Steps to build and/or run the software. Run them from the `engine/` folder, and on Windows use `gradlew.bat` instead of `./gradlew`:

1. Install Java 17 or newer. The build downloads JDK 25 and everything else it needs the first time it runs.
2. Build and test: `./gradlew build`
3. Install a runnable copy: `./gradlew installDist`. The program is then at `cli/build/install/audio-press/bin/audio-press` (`audio-press.bat` on Windows).

Instructions for using the software:

1. Convert a book: `audio-press book.pdf`. This writes `book.txt` next to the PDF and prints a one-line summary.
2. Adjust the cleanup if needed:
   * `--pages 15-402` converts only those pages (for example, to skip front and back matter).
   * `--page-markers none` writes plain text with no `[[page N]]` lines, ready for a TTS engine.
   * `--urls drop` or `--urls keep` changes how web addresses are read (the default reads only the domain).
   * `--disable running-headers,footnotes` switches off cleanup rules. `audio-press --help` lists them all.
3. See exactly what was removed and why: `audio-press book.pdf --report report.txt`.

More detail: [usage and output format](engine/docs/usage.md) and [cleanup rules](engine/docs/cleanup-rules.md).

To build the Android app (Android 13 or newer):

1. Install the Android SDK (platform 36 and build tools) and point `app/local.properties` at it with `sdk.dir=...`. The file is git-ignored.
2. From the `app/` folder, build, test, and make the debug APK: `./gradlew build assembleDebug`. The APK is at `audiopress/build/outputs/apk/debug/audiopress-debug.apk`.
3. Install it on a phone with USB debugging on: `adb install -r audiopress/build/outputs/apk/debug/audiopress-debug.apk`.

More detail is in [app/CLAUDE.md](app/CLAUDE.md).

## Development Environment

To recreate the development environment, you need the following software and/or libraries with the specified versions:

* Kotlin 2.4.21 (JVM), compiled with JDK 25 to Java 17 bytecode
* Gradle 9.8.1 (through the Gradle wrapper in `engine/`)
* Apache PDFBox 3.0.8 (PDF text extraction)
* Clikt 5.1.0 (command-line parsing)
* JUnit 6.1.3 with kotlin-test, and Spotless with ktlint 1.8.0 (tests and formatting)
* For the app: the Android SDK (compile SDK 36, minimum SDK 33), Android Gradle Plugin 9.0.1, Jetpack Compose (BOM 2026.03.01) with Material 3, PdfBox-Android 2.0.27.0 (PDF text extraction on Android), and BouncyCastle 1.86
* Visual Studio Code

The design, its decisions, and the testing approach are in [engine/docs/architecture.md](engine/docs/architecture.md), and the sprint plan is in [engine/docs/development-plan.md](engine/docs/development-plan.md). Tests generate their own PDFs; real books for manual testing go in the git-ignored `engine/local-samples/` folder and are never committed.

## Useful Websites to Learn More

I found these websites useful in developing this software:

* [Kotlin documentation](https://kotlinlang.org/docs/home.html)
* [Apache PDFBox](https://pdfbox.apache.org/)
* [Clikt](https://ajalt.github.io/clikt/)
* [Gradle user manual](https://docs.gradle.org/current/userguide/userguide.html)
* [Android TextToSpeech reference](https://developer.android.com/reference/android/speech/tts/TextToSpeech)

## Future Work

The following items I plan to fix, improve, and/or add to this project in the future:

* [x] Milestone Alpha: text-based PDFs, lo output (version 0.1.0)
* [x] Android app prototype that reads PDFs aloud, using the engine (awaiting checks on a real phone)
* [ ] Android app: playback with the screen off, speed and live-voice settings, and compressed baked audio
* [ ] Milestone Bravo: text-based PDFs, hi output
* [ ] Milestone Charlie: image-scan PDFs, lo output (OCR)
* [ ] Milestone Delta: image-scan PDFs, hi output
* [ ] Multi-column pages, drop caps, and reading footnotes after their paragraph
