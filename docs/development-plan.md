# Audio Press: Development Plan

The project is built in sprints, each with one objective, a fixed scope, and exit criteria. The product goals and milestones (Alpha through Delta) are defined in [objective-and-scope.md](objective-and-scope.md).

## How sprints work

* **One sprint at a time.** A sprint starts only after the project owner (tjnewbry) authorizes it.
* **Sprint review.** At the end of every sprint, the work is reported to the owner: what was done, what changed from the plan, and open questions. The next sprint does not start until the owner approves.
* **Version control.** The owner manages commits, pushes, and releases. At the end of each sprint the working tree builds and passes its tests.
* **Re-planning.** If a sprint shows that later scope is wrong, the plan is updated during that sprint and called out in the review. Scope is never silently expanded.
* **Milestone gate.** No work past a milestone starts until the owner has verified it and updated [objective-and-scope.md](objective-and-scope.md). Later milestones are planned in detail only then.

## Owner decisions

1. **Output consumer.** The likely TTS engine is Android's. lo output defaults to plain UTF-8 text with simple page markers, and the output writer is swappable so other formats can be added later.
2. **Audiobook standard.** A listener should never hear footnote reference numbers, legal boilerplate, full URLs, or anything that marks page breaks. Cleanup stays simple and predictable (a small set of clear rules), with command-line flags to adjust it.
3. **Multi-column layouts.** Out of scope for Alpha, but the design must allow them later.
4. **License.** MIT.

## Sprint status

| Sprint | Name | Milestone | Status |
|--------|------|-----------|--------|
| 1 | Architecture | Alpha | Done |
| 2 | Foundation and extraction | Alpha | Done |
| 3 | Cleanup, assembly, and lo output | Alpha | Done |
| 4 | Hardening and Alpha release | Alpha | Done |

Milestone Alpha was verified by the owner on 2026-10-17 and released as version 0.1.0.

Scope changes during Alpha, all approved by the owner: the original five sprints were merged into four to reduce hand-offs; the code is one Gradle module with three packages rather than three modules; hyphen handling uses one simple rule; `--mode` waits for Bravo; and the garbled-text warning waits for Charlie.

## Sprints

**Sprint 1: Architecture.** Design only, no code: the pipeline, data model, cleanup rules, command line, output formats, testing approach, and the Alpha Definition of Done. Result: [architecture.md](architecture.md), [cleanup-rules.md](cleanup-rules.md), and [usage.md](usage.md).

**Sprint 2: Foundation and extraction.** The Gradle build (wrapper, version catalog, formatting checks, warnings as errors, CI), the core data model, `PdfBoxTextSource` (positioned words, superscript splitting, rotated-text detection, typed errors), text normalization, line building, the CLI skeleton with every option validated, and the test-PDF generator. Exit: `--dump-layout` shows correct lines for generated and real PDFs.

**Sprint 3: Cleanup, assembly, and lo output.** Document measurements, all removal rules, paragraph assembly, URL rewriting, the lo writer, `--report`, and end-to-end golden tests. Exit: real books give readable transcripts with no headers, footers, or page numbers in spot checks.

**Sprint 4: Hardening and Alpha release.** Tuning against the owner's sample books (scan punctuation in headers, margin specks, small-print license notices, footnote marks garbled by OCR), error handling, the README, and version `0.1.0`. Exit: every item in the Definition of Done is met.

## Milestone Alpha: Definition of Done

| # | Criterion | Status |
|---|-----------|-------------------------------|
| 1 | `audio-press book.pdf` turns a text-based PDF into a lo transcript that matches [the lo format](usage.md#lo-format) exactly. | Met: the golden tests match exactly. |
| 2 | Every rule in [cleanup-rules.md](cleanup-rules.md) works as documented, can be switched off where documented, and has unit tests, including a case where it must not fire. | Met: `CleanupRulesTest`, `AssemblerTest`, and an end-to-end `--disable` test. |
| 3 | Every option in [usage.md](usage.md) works as documented, with the documented exit codes. | Met: `CliTest` covers options and exit codes 0, 2, 3, 4, and 5. |
| 4 | `--report` lists every removed page, line, and word, plus all warnings. | Met. Text rewrites (shortened URLs, garbled scan marks) are not removals and are documented as not listed. |
| 5 | Scanned, encrypted, damaged, and missing inputs fail with the documented exit codes; pages without text produce warnings. | Met. |
| 6 | Golden tests cover headers and footers, page numbers, footnotes and markers, hyphenation, paragraphs across pages, headings, URLs, rotated text, and the copyright and contents rules. | Met: the generated mini book covers all of them. |
| 7 | `./gradlew build` passes from a clean checkout, and CI runs the same command. | Met locally (69 tests); CI runs once the code is pushed. |
| 8 | A 500-page text-based book converts in under 60 seconds. | Met: 576 pages in 5 seconds. |
| 9 | The README explains how to build, install, and use the program, and the docs match the code. | Met. |
| 10 | The version is `0.1.0`. | Met, ready for the owner to tag `v0.1.0`. |
| 11 | The owner has checked real books from `local-samples/` and verified Alpha. | Met: verified by the owner on 2026-10-17. |

## After Alpha (outline only)

* **Milestone Bravo (hi output):** finalize the hi format (draft in [architecture.md](architecture.md#hi-format-draft)), add `--mode` and the hi writer, and map every transcript word back to its page and position.
* **Milestone Charlie (image-scan PDFs, lo output):** choose an OCR engine, add an OCR text source behind the existing `TextSource` port, and tune cleanup for OCR noise.
* **Milestone Delta (image-scan PDFs, hi output):** use OCR word positions in the hi writer, and measure word-position accuracy on scanned pages.
* **Possible improvements:** multi-column reading order, drop caps, reading footnotes after their paragraph, keeping hyphens in compounds split across lines, a garbled-text warning, and splitting paragraphs longer than Android's TTS input limit.
