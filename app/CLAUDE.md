# App: Instructions for Claude

The Android app that reads PDF books aloud, using the engine to turn each PDF into clean text and Android's `TextToSpeech` to speak it. **Status: not started.** This folder holds only these instructions until the app work is authorized.

## Rules

* **You own `app/` only.** Do not edit `engine/`. The engine's design, behavior, and supported API are in [engine/docs/](../engine/docs/); the API the app may use is [engine/docs/api.md](../engine/docs/api.md). Use only what that guide lists.
* **Engine changes are requested, not made.** Ask the engine owner (or open a GitHub issue labeled `engine`). Do not work around the engine's API by copying or patching its code.
* **PDFBox does not run on Android.** How PDF text is read on the phone, and whether that happens on the phone or on a server, is an open decision pending research. Do not choose a PDF library or add any dependency before it is resolved and tjnewbry approves it.
* **Sprints and milestones are gated** as in the root [CLAUDE.md](../CLAUDE.md). Work only within what tjnewbry has authorized, and report back when a step is done.

## Before building anything

* Check that the Android SDK and an emulator or phone are available on this machine, and say how you will verify your work.
* Propose a plan and wait for tjnewbry's OK before editing.

## Toolchain

The app needs Kotlin 2.4 or newer and a Java 17 target to use the engine (see the end of [engine/docs/api.md](../engine/docs/api.md)). Use Gradle through the wrapper in this folder once the Android project exists. Android build outputs are already ignored by the root `.gitignore`.
