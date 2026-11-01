# Audio Press engine: roadmap and status

The product goals and the four milestones (Alpha to Delta) are defined in [objective-and-scope.md](objective-and-scope.md). How work is authorized and verified is set out in the root [CLAUDE.md](../../CLAUDE.md).

## Status

| Milestone | Status |
|-----------|--------|
| Alpha: text-based PDFs, lo output | Done. Verified 2026-10-17 and released as version 0.1.0. |
| Bravo: text-based PDFs, hi output | Planned. Paused until the Android app prototype works. |
| Charlie: image-scan PDFs, lo output | Planned. |
| Delta: image-scan PDFs, hi output | Planned. |

Work so far:

| Sprint | What it delivered | Status |
|--------|-------------------|--------|
| 1. Architecture | The design: pipeline, data model, cleanup rules, command line, output formats, testing approach. No code. | Done |
| 2. Foundation and extraction | The Gradle build, the data model, PDF reading with PDFBox, text normalization, line building, the command-line skeleton, and the test-PDF generator. | Done |
| 3. Cleanup, assembly, and lo output | Document measurements, all cleanup rules, paragraph assembly, URL rewriting, the lo writer, `--report`, and end-to-end golden tests. | Done |
| 4. Hardening and Alpha release | Tuning against real books, error handling, the README, and version 0.1.0. | Done |
| 5. Restructure for the app | The engine moved into `engine/` and split into `core`, `pdfbox`, and `cli` modules so the app can use `core` as a library. No behavior change. | Done, awaiting owner review |

## Milestone Alpha: Definition of Done

| # | Criterion | Status |
|---|-----------|--------|
| 1 | `audio-press book.pdf` turns a text-based PDF into a lo transcript that matches [the lo format](usage.md#lo-format) exactly. | Met: the golden tests match exactly. |
| 2 | Every rule in [cleanup-rules.md](cleanup-rules.md) works as documented, can be switched off where documented, and has unit tests, including a case where it must not fire. | Met: `CleanupRulesTest`, `AssemblerTest`, and an end-to-end `--disable` test. |
| 3 | Every option in [usage.md](usage.md) works as documented, with the documented exit codes. | Met: `CliTest` covers options and exit codes 0, 2, 3, 4, and 5. |
| 4 | `--report` lists every removed page, line, and word, plus all warnings. | Met. Text rewrites (shortened URLs, garbled scan marks) are not removals and are documented as not listed. |
| 5 | Scanned, encrypted, damaged, and missing inputs fail with the documented exit codes; pages without text produce warnings. | Met. |
| 6 | Golden tests cover headers and footers, page numbers, footnotes and markers, hyphenation, paragraphs across pages, headings, URLs, rotated text, and the copyright and contents rules. | Met: the generated mini book covers all of them. |
| 7 | `./gradlew build` passes from a clean checkout, and CI runs the same command. | Met at release: 69 tests passed locally and in GitHub Actions. The workflow was moved to `engine/` afterward and runs again on the next push. |
| 8 | A 500-page text-based book converts in under 60 seconds. | Met: 576 pages in 5 seconds. |
| 9 | The README explains how to build, install, and use the program, and the docs match the code. | Met. |
| 10 | The version is `0.1.0`. | Met. |
| 11 | The owner has checked real books and verified Alpha. | Met: verified 2026-10-17. |

## After Alpha (outline only)

* **Milestone Bravo (hi output):** finalize the hi format (draft in [architecture.md](architecture.md#hi-format-draft)), add `--mode` and the hi writer, and map every transcript word back to its page and position.
* **Milestone Charlie (image-scan PDFs, lo output):** choose an OCR engine, add an OCR text source behind the existing `TextSource` port, and tune cleanup for OCR noise.
* **Milestone Delta (image-scan PDFs, hi output):** use OCR word positions in the hi writer, and measure word-position accuracy on scanned pages.
* **Possible improvements:** multi-column reading order, drop caps, reading footnotes after their paragraph, keeping hyphens in compounds split across lines, a garbled-text warning, and splitting paragraphs longer than Android's TTS input limit.
