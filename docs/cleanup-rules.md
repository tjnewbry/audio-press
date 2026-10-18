# Cleanup Rules

Audio Press turns a book's pages into text that sounds right when read aloud. It does this with a short list of named rules. Each rule is simple enough to predict, uses measurements taken from the book itself rather than fixed sizes, and can be switched off.

* See which rules exist: `audio-press --help` lists every rule ID under `--disable`.
* Switch rules off: `--disable running-headers,footnotes`
* See exactly what was removed and why: `--report report.txt`

## Measurements

Rules compare text against measurements of the current book, so they adapt to different formats:

| Name | Meaning |
|------|---------|
| **Body size** (B) | The font size used by the most characters in the book. |
| **Line pitch** (P) | The most common distance between the baselines of consecutive body-size lines. |
| **Top and bottom zones** | The top 9% and bottom 9% of each page. Headers, footers, and page numbers live here. A line is in a zone when it lies entirely inside it. (The zones are kept narrow so that the first and last lines of body text, such as an "Example 3" heading at the top of a page, are not mistaken for headers.) |
| **Text edges** | On each page, the most common left edge and the rightmost right edge of its body lines. Pages with fewer than 3 body lines use the typical values for the book. If no page has 3 body lines, each page is assumed to have equal left and right margins. |

## Removal rules

The rules run in this order. Each one can see what earlier rules removed.

| ID | Removes | How it decides |
|----|---------|----------------|
| `rotated-text` | Text that is not horizontal, such as watermarks, "Downloaded from..." notices, and spine text in the margins. | Any word whose direction is not left-to-right horizontal. |
| `page-numbers` | Page numbers, and stray marks in the margins. | A line in the top or bottom zone whose whole text is a page number (`12`, `xii`, `- 12 -`, `[12]`, `Page 12`, or `Page 12 of 300`), or that has at most 2 characters (specks that scanning turns into letters). |
| `running-headers` | Running headers and footers, such as the book title on even pages and the chapter title on odd pages, even when a page number is attached. | A line in a zone whose *signature* matches a line in the same zone on another page within 2 pages either side. The signature is the lowercased text with punctuation ignored and digit runs and leading or trailing roman numerals replaced by `#`. It must contain at least 3 letters. |
| `legal-notices` | Legal and licensing text. | A line in a zone that contains `©`, `copyright`, `all rights reserved`, `downloaded from`, `licensed to`, or `personal use only` (any capitalization). Also, anywhere on the page, a block of small-print lines (below 0.95 × B) that contains `is shared under a`, `licensed under a`, or `creative commons`. |
| `copyright-page` | The copyright page. | A whole page within the first 10 or last 5 pages of the PDF that contains `all rights reserved`, `ISBN`, or `Library of Congress`. |
| `contents-and-index` | Tables of contents, lists of figures, and indexes. | A whole page with at least 8 lines, where at least 60% of lines contain at least 4 letters and end with a page reference (a number or roman numeral, after a space or dot leaders, optionally followed by more numbers such as `12, 45-47`). The 4-letter minimum keeps lines of equations such as `y = 2` from counting. |
| `footnotes` | Footnote text at the bottom of a page. | The block of consecutive lines at the bottom of the page (after zone lines are removed) whose font size is at most 0.85 × B, as long as the page has body-size text above it. |
| `footnote-markers` | Footnote reference marks inside sentences. | A superscript word (at most 0.85 × its line's font size, with its baseline raised by at least 0.2 × that size) consisting only of 1 to 3 digits or the symbols `* † ‡ § ¶`. Unicode superscript digits (such as `¹²³`) are removed too; text normalization splits them off the end of a word first. In scanned books, OCR often turns footnote numbers into degree signs and stray marks right after a sentence ends (`flesh.”°`); those are removed as well, while a real degree sign (`30°`) is kept. These scan marks are rewritten in the transcript and are not listed in the report. |

The book's own words are never rewritten by a removal rule; they are only kept or removed.

## Paragraph assembly

After removals, the remaining lines are joined into paragraphs and headings. These rules are part of assembly and are always on.

* **Headings.** A line whose font size is at least 1.15 × B is a heading. Consecutive heading lines of the same size form one heading (for example a two-line chapter title).
* **New paragraph.** A line starts a new paragraph when any of these are true:
  1. It is a heading, or follows one.
  2. On the same page, the gap from the previous line's baseline is more than 1.5 × P.
  3. The previous line is short: it ends before 85% of the way across the text edges. Short last lines are how most paragraphs end.
  4. It is indented by at least 0.8 × B from the left text edge, and the previous line is not indented. (This keeps a block quote together while still catching first-line indents.)
  5. It starts with a list bullet (`•`, `◦`, `▪`, `‣`, `●`, `■`). The bullet itself is removed.
* **Across pages.** The same checks apply between the last kept line of one page and the first kept line of the next, except the gap check. A paragraph that runs onto the next page stays one paragraph.
* **Joining lines.** Lines in a paragraph are joined with a single space, except at line-end hyphens and dashes:
  * A line ending in a soft hyphen is joined to the next line with no space and no hyphen.
  * A line ending in `-` followed by a line starting with a lowercase letter is joined with no space and the hyphen is removed (`inter-` + `national` becomes `international`). A real compound split at its hyphen (`well-` + `known`) comes out as `wellknown`; telling the two apart is a planned improvement.
  * A line ending in `-` followed by an uppercase letter or digit is joined with no space and the hyphen is kept (`Anglo-` + `Saxon` becomes `Anglo-Saxon`).
  * A line ending in an en or em dash is joined with no space.

## Token rewrites

| ID | Effect | Option |
|----|--------|--------|
| `urls` | Web addresses (starting with `http://`, `https://`, or `www.`) are shortened to the domain name, so `https://www.example.com/a/b?c=1` is read as `example.com`. Punctuation that ends the sentence is kept. Email addresses are left alone. | `--urls domain` (default), `--urls drop` removes them, `--urls keep` leaves them as is. |

## Text normalization

Applied to every word as soon as it is extracted, before any rule runs. It cannot be switched off, because the rules depend on it.

* Ligature characters become ordinary letters (`ﬁ` becomes `fi`, `ﬄ` becomes `ffl`).
* Non-breaking and other special spaces become ordinary spaces.
* Invisible characters (zero-width spaces and joiners, byte order marks, control characters) are removed.
* Soft hyphens are removed, except at the end of a line, where paragraph assembly uses them.
* Unreadable characters (the replacement character `�` and private-use characters from broken font encodings) are removed.

## Not handled in Alpha

Multi-column reading order, tables, drop caps, sidebars, keeping footnote text (reading it after its paragraph), and keeping the hyphen in compounds split across lines are planned improvements. See [architecture.md](architecture.md#known-limits-in-alpha).
