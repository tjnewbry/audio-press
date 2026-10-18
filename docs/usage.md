# Usage

```
audio-press [OPTIONS] INPUT
```

`INPUT` is the path to one PDF file. What each cleanup rule does is described in [cleanup-rules.md](cleanup-rules.md).

## Options

| Option | Values | Default | Description |
|--------|--------|---------|-------------|
| `-o`, `--output PATH` | file path, or `-` for stdout | `INPUT` with a `.txt` extension | Where to write the transcript. An existing file is replaced; writing over the input file is refused. |
| `--pages RANGES` | for example `9-310` or `1,5-7,20-` | all pages | Only convert these pages. Numbers are physical page positions (1 is the first page of the file), not the numbers printed on the pages. |
| `--page-markers STYLE` | `line`, `none` | `line` | `line` writes a `[[page N]]` line where each new page starts. `none` writes plain text, ready for TTS. |
| `--urls MODE` | `domain`, `drop`, `keep` | `domain` | How web addresses are read. |
| `--disable RULES` | comma-separated rule IDs, can be repeated | none | Switch off cleanup rules. `--help` lists every rule ID. |
| `--report PATH` | file path, or `-` for stderr | no report | Write a report of everything removed and every warning (see [Report](#report)). |
| `-q`, `--quiet` | | | Print errors only. |
| `-v`, `--verbose` | | | Also print PDFBox messages and stack traces for unexpected errors. |
| `--version`, `-h`, `--help` | | | Print the version or help and exit. |

The hidden developer option `--dump-layout PATH` writes every extracted line with its position, font size, and the rule that removed it, for tuning rules.

All messages (the one-line summary, warnings, and errors) go to stderr, so stdout can carry the transcript with `-o -`.

## Exit codes

| Code | Meaning | Examples |
|------|---------|----------|
| 0 | Success (warnings may have been printed). | |
| 1 | Unexpected internal error. | A bug. Rerun with `--verbose` for a stack trace. |
| 2 | Usage error. | Unknown option, bad `--pages` range, unknown rule ID, output would overwrite the input. |
| 3 | Input error. | File not found or unreadable, not a PDF, damaged PDF, needs a password, text extraction not permitted. |
| 4 | No text to extract. | None of the selected pages has a text layer (a scanned PDF). |
| 5 | Output error. | The output file cannot be written. |

## Examples

```sh
# Convert a book to book.txt next to it
audio-press book.pdf

# Skip the front and back matter, and write plain text for TTS
audio-press book.pdf --pages 15-402 --page-markers none -o book-tts.txt

# A math textbook: keep exponents and footnotes, and see what else was removed
audio-press math.pdf --disable footnotes,footnote-markers --report removed.txt
```

## lo format

The lo transcript is a plain text file meant to be read aloud. This is the specification; the writer and its tests match it exactly.

1. **Encoding.** UTF-8, no byte order mark, LF line endings, ending with a newline.
2. **Paragraphs.** Each paragraph or heading is one line of text. Paragraphs are separated by exactly one blank line.
3. **Page markers** (only with `--page-markers line`). A line containing exactly `[[page N]]`, where `N` is the physical page number, written before the first text from page `N`: at the start of the file, between paragraphs, or inside a paragraph that continues onto a new page (the paragraph is split there, with the marker on its own line). A page marker never ends a paragraph; only a blank line does.
4. **Skipped pages.** Pages that contribute no text get no marker.

```text
[[page 7]]
CHAPTER ONE

The lighthouse keeper woke before dawn, as he always did, and climbed the stair to check the lamp. Fog had rolled in from the sea during
[[page 8]]
the night, and a fishing boat crept along the coast below him.

He went back down to make his breakfast.
```

With `--page-markers none`, the same text has no marker lines, and the split paragraph is one line.

**Reading it in a TTS app:** remove lines matching `^\[\[page \d+\]\]$` (noting the page if the app tracks pages), join consecutive non-blank lines with a space, and split on blank lines; each piece is one paragraph. With Android's `TextToSpeech`, speak one paragraph per utterance, and split paragraphs longer than `TextToSpeech.getMaxSpeechInputLength()` (4,000 characters) at a sentence boundary.

## Report

Written with `--report PATH`. Plain text for people to read, not a stable machine format.

```text
Audio Press report
Input: book.pdf (412 pages, 412 selected)
Rules: rotated-text, page-numbers, running-headers, legal-notices, copyright-page, contents-and-index, footnotes, footnote-markers, urls
Disabled: none

Page 4: skipped by copyright-page (contains "all rights reserved")
Page 12:
  line removed by running-headers: "THE LIGHTHOUSE" (same as page 10)
  line removed by page-numbers: "12"
  word removed by footnote-markers: "3" (after "summer.")
Page 40:
  warning: no text layer (probably a scanned image)

Summary: 640 lines, 211 words, and 6 pages removed. 1 warning.
```

Lines on a skipped page are not listed separately. Text rewrites (shortened URLs and footnote marks garbled by scanning) are not listed.
