# Audio Press app: objective and scope

## Objective

A working prototype Android app (Android 13 and newer, API 33+) that uses the engine to turn a supported PDF into an audiobook and plays it.

## Features

The prototype has exactly these three features.

1. **Import PDF.** Pick a PDF with the system file picker. The engine turns it into a transcript, with progress shown while it works. Supported PDFs are text-based ones (Milestone Alpha). A scanned, password-protected, or damaged PDF shows a clear message, such as "scanned PDFs are not supported yet", and never crashes the app.
2. **Library.** A list of previously processed books. Selecting one opens it in the player.
3. **Player.**
   * **Live mode (the default):** reads the transcript aloud with Android's `TextToSpeech`, one paragraph per utterance, with play and pause.
   * **Bake:** a button that turns a book's transcript into audio files on the phone. Once a book is baked, the player plays those files.
   * **Live captions:** a scrolling transcript, like song lyrics in Spotify. The passage being read is highlighted and stays in view as the audio plays. In live mode, the current word is highlighted too, where the speech engine reports it. Captions do not highlight anything on the PDF pages; that needs later engine milestones.
   * **Tap a line to jump:** tapping a passage in the captions moves playback there.
   * **Timestamp:** elapsed and total time. In live mode these are estimates (from word count and speaking rate, refined as it plays); for a baked book they are the real audio durations.
   * **Saved progress:** the position is saved automatically, and reopening a book resumes where it left off, in either mode.

**Not in the prototype:** a seek bar or skip buttons (tapping a caption line is the only way to jump), chapter navigation, speed or voice settings, playback with the screen off, deleting books, scanned PDFs, and highlighting words on the PDF pages.

## Decisions

* **The transcript is made at import,** in both modes. Baking only adds audio; it never re-reads the PDF.
* **The engine is used as a library,** through [engine/docs/api.md](../../engine/docs/api.md). PDFBox does not run on Android, so the app needs its own PDF reader.
* **The Android PDF reader** lives in `app/pdf-source/`, an Android library module that implements the engine's `TextSource`. The engine owner builds and owns it; the rest of `app/` belongs to the app developer.
* **Processing runs on the phone,** with PdfBox-Android (Apache 2.0) reading the PDFs. Decided 2026-10-31 after the PDF-reader research; no server is needed.
* **Baked audio is large:** uncompressed speech is measured at about 3 MB per minute (about 175 MB per hour). Compressing it is a later improvement.
* **Baking uses Kokoro v1.0 voices (sprint A6).** Live playback stays on the phone's own speech engine. Tapping Bake opens a voice screen with three model qualities (Lite = int8, Medium = a mixed uint8/int8 model (the release names the file fp16), Heavy = fp32; about 137, 222, and 370 MB), downloaded on demand, never shipped in the APK, and 28 English voices with short samples (shipped as Ogg Opus in the app, about 1.3 MB) to listen to first. Switching quality tabs deselects the voice and keeps the list position. Removing downloaded models is not in A6. 
* **A download or bake keeps running in the background (sprint A6).** It runs in a foreground service of type `dataSync` with a notification ("Downloading Lite...", "Baking audio using Santa...", progress, Cancel), and the CPU is held awake, so a long bake continues with the screen off or another app in front. Android 13 asks for the notification permission when the voice screen opens, and the bake runs either way. From Android 15 a `dataSync` service may run only about 6 hours in 24, so a long bake is normally resumed: when the limit comes the bake stops cleanly and keeps its clips, and the book offers "Resume baking" (as it does after the app is killed). The app needs the `INTERNET` permission for the model download and nothing else uses the network.

## Definition of done

1. The app builds from a clean checkout with its own Gradle wrapper, its tests pass, and the engine still builds on its own.
2. It runs on the Android 13 emulator and on the owner's phone.
3. Importing a text-based PDF adds a book to the library. Scanned, password-protected, and damaged PDFs show clear messages.
4. Live mode reads the book in order, with captions that follow along, a timestamp, and tap-a-line to jump.
5. Baking produces audio, and a baked book plays with captions and real timestamps.
6. Progress survives closing and reopening the app, in both modes.
7. No copyrighted PDFs are committed, and the docs match the app.
8. The owner has checked it on their phone with real books and verified the prototype.

## Sprints

| Sprint | Goal | Status |
|--------|------|--------|
| A1. Setup and integration | The Android project (minimum API 33) builds and runs on the emulator, calls the engine through a stand-in `TextSource`, and speaks hardcoded text. In parallel, research how to read PDFs on Android. | Done for the app side: builds, tests pass, runs on the Android 13 emulator (sample transcript from the engine shown and spoken, play/pause works). Awaiting the owner's check. The PDF research is done: PDFs are read with PdfBox-Android (see Decisions). |
| A2. Import and library | The Android PDF reader, the import flow, storage, and the library screen. | Done for the app side: import from the file picker with a progress bar and Cancel (text-based PDFs; scanned, password-protected, damaged, and empty PDFs show clear messages), storage in app-private `files/books/<id>/`, library list, tap to open in the reader with Speak. Checked on the Android 13 emulator with generated PDFs. Awaiting the owner's check on a phone with real books. |
| A3. Live player | Live playback, captions with tap-a-line to jump, timestamps, and saved progress. | Done for the app side: scrolling captions with the current line (a paragraph, or part of a very long one) and word highlighted (auto-scroll pauses while the user scrolls), tap a line to jump, elapsed and total time (estimated at 170 words per minute, refined while playing), pause and resume from the current word, and progress saved to `progress.txt` in the book folder (resumes after a force-stop). Checked on the Android 13 emulator. Awaiting the owner's check on a phone. |
| A4. Bake | Turning a transcript into audio files, and playing baked books. | Done for the app side: Bake button with progress, Cancel and resume (the `bake` module, files in `books/<id>/bake/`); once baked the player plays the WAV files with `MediaPlayer` (no extra dependency), captions highlight by utterance, tap a line to jump, real elapsed and total time; positions are shared between live and baked mode; the baked size is shown (about 3 MB per minute). Checked on the Android 13 emulator. Awaiting the owner's check on a phone. |
| A5. Hardening | Error handling, testing with real books, and the owner's verification. | Done for the app side, on the emulator: rotation and background keep state (leaving the app or turning the screen off pauses and saves the position), clear messages for a missing speech engine or voice and for low storage (import and bake), baking resumes after a restart, baked books are marked in the library and can be read with live speech instead, long paragraphs are cut into lines so a 1,200-paragraph book scrolls smoothly, and the layout holds at the largest font. Left for the owner: real books on a real phone. |
| A6. Kokoro voices for Bake | The voice screen opened by Bake (quality tabs, 28 voices with samples, download and bake buttons), the full-screen download and bake progress, and baking with the chosen voice. The model download and speech synthesis are in `app/bake`. | Done for the app side: the voice screen, the samples, real Kokoro download and baking through `app/bake` (checked on the emulator with a real Lite download and bake), the background service with its notification, and an About / Licences screen. Awaiting the owner's check on the phone. |
