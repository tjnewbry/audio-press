# Audio Press: Instructions for Claude

Audio Press turns PDF books into clean text for text-to-speech, and is meant to power an Android app that reads them aloud. The repo has two parts, each with its own `CLAUDE.md` that loads when you work in that folder:

| Folder | What it is | Owner | Read first |
|--------|-----------|-------|------------|
| `engine/` | The PDF-to-transcript engine and command-line program (Kotlin). Self-contained; Milestone Alpha is done. | Engine owner | [engine/CLAUDE.md](engine/CLAUDE.md) |
| `app/` | The Android app. Not started. | App developer | [app/CLAUDE.md](app/CLAUDE.md) |
| Root files (`README.md`, `CLAUDE.md`, `.gitignore`, `.gitattributes`, `.editorconfig`, `LICENSE`, `.github/`) | Shared by both | The main session, or whoever tjnewbry assigns | This file |

## Boundaries

* **The engine never depends on the app.** The app uses the engine only through the API in [engine/docs/api.md](engine/docs/api.md).
* **Each agent edits only its own folder.** To change something outside it, ask its owner: message them during a team session, or open a GitHub issue (the app side labels engine requests `engine`).
* **Keep shared files minimal and changes small.** Add nothing to the root that a folder can own.

## Working rules (set by the project owner, tjnewbry)

* **Follow industry best practices in this repo**, scaled to a solo project: no process or files the project does not need.
* **Sprints are gated.** Work only within the current authorized sprint. At the end of every sprint, report back with what was done, plan changes, and open questions, then stop until tjnewbry authorizes the next one.
* **Milestone gate.** Do not work past a milestone until tjnewbry has verified it and updated the status paragraph in `engine/docs/objective-and-scope.md`. Design with later milestones in mind, but do not build them.
* **Keep the plan current** in `engine/docs/development-plan.md` as part of each sprint.
* **The editor is VS Code**, not IntelliJ IDEA.
* **Never commit real books or other copyrighted material.** Committed tests use only generated PDFs and original text.
