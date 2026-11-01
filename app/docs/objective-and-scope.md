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
* **Where processing runs** (on the phone, or on a server running the engine) is open until the PDF-reader research is done and the owner decides.
* **Baked audio is large:** uncompressed speech is roughly 170 MB per hour. Compressing it is a later improvement.

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
| A1. Setup and integration | The Android project (minimum API 33) builds and runs on the emulator, calls the engine through a stand-in `TextSource`, and speaks hardcoded text. In parallel, research how to read PDFs on Android. | Not started |
| A2. Import and library | The Android PDF reader, the import flow, storage, and the library screen. | Not started |
| A3. Live player | Live playback, captions with tap-a-line to jump, timestamps, and saved progress. | Not started |
| A4. Bake | Turning a transcript into audio files, and playing baked books. | Not started |
| A5. Hardening | Error handling, testing with real books, and the owner's verification. | Not started |
