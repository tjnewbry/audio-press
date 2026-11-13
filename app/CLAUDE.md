# App: Instructions for Claude

The Android app that turns PDF books into audiobooks, using the engine to make each PDF's transcript and Android's `TextToSpeech` to speak it. **Status: the app is at version 1.0.0 (prototype sprints A1 to A6 are done, with later polish since; see the sprint table).** The goal, features, decisions, definition of done, and sprints are in [docs/objective-and-scope.md](docs/objective-and-scope.md). Build exactly those features.

## Ownership

* **`app/pdf-source/`** (the Android PDF reader, which implements the engine's `TextSource`) belongs to the engine owner.
* **`app/bake/`** (Bake: synthesizes utterances to audio files with the Kokoro voices through ONNX Runtime, writes the manifest, and maps playback positions to utterances; no UI) belongs to the engine owner for now. The app developer adds the Bake button and baked playback in `app/audiopress` through its public API (`BookBaker`, `BakedBook`).
* **Everything else in `app/`** belongs to the app developer.
* **`engine/`** is never edited from here. The API the app may use is [engine/docs/api.md](../engine/docs/api.md). Ask the engine owner for engine changes (or open a GitHub issue labeled `engine`); never copy or patch engine code to work around its API.

## Rules

* **Do not add any dependency** without tjnewbry's approval.
* **Sprints and milestones are gated** as in the root [CLAUDE.md](../CLAUDE.md). Work only within what tjnewbry has authorized, and report back when a step is done.
* **Before editing, propose a plan** and wait for tjnewbry's OK.
* **Test on the emulator** and say how you verified each change. tjnewbry tests on a real phone.

## Toolchain

* The Android SDK is at `%LOCALAPPDATA%\Android\Sdk`. Point Gradle at it with `sdk.dir` in `local.properties`, which git ignores.
* Use the **Android CLI** (`android`, in `cmdline-tools\latest\bin` in the SDK) to create projects, build, run the emulator, install apps, inspect layouts, and search official Android documentation (`android docs`).
* The app needs Kotlin 2.4 or newer and a Java 17 target to use the engine (see the end of [engine/docs/api.md](../engine/docs/api.md)). Android build outputs are already ignored by the root `.gitignore`.

## Layout

A Gradle build with its own wrapper (`app/gradlew`). `settings.gradle.kts` includes the engine with `includeBuild("../engine")`. The application module is `audiopress/` (named for the product, so the path is not `app/app`); `pdf-source/` (reads PDFs with PdfBox-Android) and `bake/` (turns utterances into audio files) are the engine owner's modules for now. Package `io.github.tjnewbry.audiopress.app`; minSdk 33, compileSdk and targetSdk 36. Versions are in `gradle/libs.versions.toml`.

Inside `audiopress/src/main/kotlin/io/github/tjnewbry/audiopress/app/`:

* **The package root** holds everything that is not drawing: `MainActivity`, the state holders (`LibraryViewModel`, `ReaderSession`), the players, storage, baking, export, the import rules (`ImportRange.kt`), and `Formats.kt` (every size, time and count the screens show).
* **`ui/`** holds the composables, one file per screen or component. Shared pieces are in `ui/` itself: `AppContent.kt` (which screen shows, and Back), `Theme.kt` (theme, spacing scale, touch-target size, shared colours), `Dialogs.kt`, `Progress.kt`, `Outline.kt`. Each screen has a folder: `library/`, `player/`, `bake/` (voice screen and bake progress), `pdfimport/`, `about/`.
* Screens and their sections read their state holder directly, so fast-changing state redraws only the part that shows it; small components take plain values and callbacks. Texts built from state are plain functions next to the composable that shows them, and are unit-tested.

## Commands

Run from `app/`. The engine's build needs JDK 25: the engine's Gradle toolchain finds it through `JAVA_HOME` or downloads it. Create `local.properties` with `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` (forward slashes).

```text
./gradlew build assembleDebug      # compile, run unit tests and the ktlint check, build the debug APK
./gradlew test                     # unit tests only
./gradlew spotlessApply            # fix formatting
adb install -r audiopress/build/outputs/apk/debug/audiopress-debug.apk
adb shell am start -n io.github.tjnewbry.audiopress.app/.MainActivity
```

To test import without real books, push a generated PDF with `adb push file.pdf /sdcard/Download/` (in Git Bash set `MSYS_NO_PATHCONV=1` first), then use Import PDF and pick it from Downloads. Imported books live in `files/books/<id>/` (`book.properties` and `transcript.txt`; format in `BookStore.kt`).

`adb` and the emulator are in the SDK (`platform-tools`, `emulator`). Start the emulator with `emulator -avd audio-press-api33 -no-snapshot-save -no-boot-anim`.
