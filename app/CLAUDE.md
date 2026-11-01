# App: Instructions for Claude

The Android app that turns PDF books into audiobooks, using the engine to make each PDF's transcript and Android's `TextToSpeech` to speak it. **Status: not started.** The goal, features, decisions, definition of done, and sprints are in [docs/objective-and-scope.md](docs/objective-and-scope.md). Build exactly those features.

## Ownership

* **`app/pdf-source/`** (the Android PDF reader, which implements the engine's `TextSource`) belongs to the engine owner.
* **Everything else in `app/`** belongs to the app developer.
* **`engine/`** is never edited from here. The API the app may use is [engine/docs/api.md](../engine/docs/api.md). Ask the engine owner for engine changes (or open a GitHub issue labeled `engine`); never copy or patch engine code to work around its API.

## Rules

* **Do not add a PDF library or any other dependency** without tjnewbry's approval. How PDFs are read on Android is decided only after the research is done.
* **Sprints and milestones are gated** as in the root [CLAUDE.md](../CLAUDE.md). Work only within what tjnewbry has authorized, and report back when a step is done.
* **Before editing, propose a plan** and wait for tjnewbry's OK.
* **Test on the emulator** and say how you verified each change. tjnewbry tests on a real phone.

## Toolchain

* The Android SDK is at `%LOCALAPPDATA%\Android\Sdk`. Point Gradle at it with `sdk.dir` in `local.properties`, which git ignores.
* Use the **Android CLI** (`android`, in `cmdline-tools\latest\bin` in the SDK) to create projects, build, run the emulator, install apps, inspect layouts, and search official Android documentation (`android docs`).
* The app needs Kotlin 2.4 or newer and a Java 17 target to use the engine (see the end of [engine/docs/api.md](../engine/docs/api.md)). Use Gradle through the wrapper in this folder once the project exists. Android build outputs are already ignored by the root `.gitignore`.
