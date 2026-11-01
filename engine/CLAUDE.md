# Engine: Instructions for Claude

The engine turns text-based PDFs into clean transcripts for text-to-speech. This folder is self-contained: it builds, tests, and runs on its own. Read these first:

* [docs/objective-and-scope.md](docs/objective-and-scope.md): what it does and its milestones (Alpha to Delta).
* [docs/development-plan.md](docs/development-plan.md): roadmap, status, and the Alpha Definition of Done.
* [docs/architecture.md](docs/architecture.md): the design, design decisions, testing approach, and Android notes.
* [docs/api.md](docs/api.md): the public API the app uses.
* [docs/usage.md](docs/usage.md) and [docs/cleanup-rules.md](docs/cleanup-rules.md): the user-facing behavior. Code and these docs must stay in sync.

## Isolation rules

* Nothing in `engine/` may refer to `../app` or to Android. CI builds the engine alone, so a hidden dependency fails the build.
* The app uses only the supported API in [docs/api.md](docs/api.md). Do not change it silently: update that guide in the same change.
* Change requests from the app come to the engine owner. Changes land here, with tests.

## Milestones

Milestone Alpha is done. Bravo, Charlie, and Delta are paused until tjnewbry says the app prototype works and authorizes them. Design with them in mind, but do not build them. Keep the tests green and the plan current, so the milestones can resume as if they had never been paused. After a milestone, tjnewbry verifies it and updates the status paragraph in `docs/objective-and-scope.md` before anything goes past it.

## Working rules

The sprint and milestone gates in the root [CLAUDE.md](../CLAUDE.md) apply here. Keep `docs/development-plan.md` current as part of each sprint.

## Layout

* `core/`: the engine. Depends only on the Kotlin standard library. Cleanup rules are in `core/.../rules`.
* `pdfbox/`: reads PDFs with Apache PDFBox. Its test fixtures (`FixturePdf`) generate PDFs for tests.
* `cli/`: the `audio-press` command-line program, with the golden-file tests.
* `local-samples/`: the owner's real PDFs for manual testing (often copyrighted), with output in `local-samples/out/`. Ignored by git. Committed tests use only generated PDFs and original text.

## Commands (run from `engine/`; on Windows use `gradlew.bat`)

* `./gradlew build`: compile with warnings as errors, check formatting, run all tests. It must pass.
* `./gradlew spotlessApply`: fix formatting. Do not hand-format.
* `./gradlew installDist`: install the program at `cli/build/install/audio-press/bin/audio-press`.
* `./gradlew test -Pgolden.update=true`: regenerate the golden files after an intended behavior change, then review the diff.

## Coding standards

* Kotlin official style, enforced by ktlint through Spotless.
* Write special characters in Kotlin source as code points (`Char(0x00AD)`) or regex escapes (`\x{2013}`). The formatter turns `\uXXXX` escapes into raw, often invisible characters.
* `core` must not use PDFBox, Clikt, `java.awt`, or anything desktop-only; Gradle blocks the first two and `ArchitectureTest` checks the last. Only `cli` prints or exits.
* Model types are immutable `data class`es; stages return new values. No `!!`. Expected failures are `AudioPressException` subclasses.
* Each cleanup rule is one file in `core/.../rules`, with a `RuleId`, thresholds as named constants, unit tests (including a case where it must not fire), and an entry in `docs/cleanup-rules.md`.
* Every behavior change comes with a test. Test names describe the behavior in backticks. Dependency versions live only in `gradle/libs.versions.toml`.

## Toolchain

Kotlin 2.4.21 (JVM), JDK 25 toolchain emitting Java 17 bytecode, Gradle 9.8.1 through the wrapper in this folder.
