# Audio Press: Instructions for Claude

Audio Press is a Kotlin command-line program that turns PDFs into transcripts for text-to-speech. Read these first:

* [docs/objective-and-scope.md](docs/objective-and-scope.md): what the product does and its milestones (Alpha to Delta).
* [docs/development-plan.md](docs/development-plan.md): sprint process, status, and the Definition of Done.
* [docs/architecture.md](docs/architecture.md): the design, design decisions, and testing approach.
* [docs/usage.md](docs/usage.md) and [docs/cleanup-rules.md](docs/cleanup-rules.md): the user-facing behavior. Code and these docs must stay in sync.

## Working rules (set by the project owner, tjnewbry)

* **Follow industry best practices in this repo**, scaled to a solo course project: no process or files the project does not need.
* **Sprints are gated.** Work only within the current authorized sprint. At the end of every sprint, report back with what was done, plan changes, and open questions, then stop until tjnewbry authorizes the next one.
* **Milestone gate.** Do not work past a milestone until tjnewbry has verified it and updated the status paragraph in `docs/objective-and-scope.md`. Design with later milestones in mind, but do not build them.
* **Keep the plan current** in `docs/development-plan.md` as part of each sprint.
* **The editor is VS Code**, not IntelliJ IDEA.

## Coding standards

* Kotlin official style, enforced by ktlint through Spotless. Run `./gradlew spotlessApply`; do not hand-format. `./gradlew build` must pass (compile with warnings as errors, formatting, tests).
* Write special characters in Kotlin source as code points (`Char(0x00AD)`) or regex escapes (`\x{2013}`). The formatter turns `\uXXXX` escapes into raw, often invisible characters.
* `core` must not import PDFBox, Clikt, `java.awt`, or the `pdfbox` and `cli` packages (`ArchitectureTest` checks). Only `cli` prints or exits.
* Model types are immutable `data class`es; stages return new values. No `!!`. Expected failures are `AudioPressException` subclasses.
* Each cleanup rule is one file in `core/rules`, with a `RuleId`, thresholds as named constants, unit tests (including a case where it must not fire), and an entry in `docs/cleanup-rules.md`.
* Every behavior change comes with a test. Test names describe the behavior in backticks. Dependency versions live only in `gradle/libs.versions.toml`.

## What goes where

* `docs/`: committed project documentation.
* `local-docs/`: the owner's private notes and course material. Ignored by git; never move course material into the repo.
* `local-samples/`: real PDFs for manual testing (often copyrighted), with output in `local-samples/out/`. Ignored by git. Committed tests use only generated PDFs and original text.
* `CLAUDE.local.md`: personal, uncommitted agent notes.

## Toolchain

Kotlin 2.4.21 (JVM), JDK 25 toolchain emitting Java 17 bytecode, Gradle 9.8.1 through the wrapper. Windows development machine, PowerShell as the default shell.
