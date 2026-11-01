# Engine API guide

For code that uses the engine as a library, such as the Android app. The engine is the `core` module (`io.github.tjnewbry.audiopress:core`), which depends only on the Kotlin standard library. Design background is in [architecture.md](architecture.md).

**Stability.** Only the types listed here are the supported API. Kotlin makes the rest of `core` (the cleanup rules, `Assembler`, `LineBuilder`, `TextNormalizer`, `LayoutDump`, and so on) public too, but those are internal to the engine and may change. Changes to the supported API are documented here in the same change.

## Using it

```kotlin
// 1. A TextSource gives the engine one page of positioned words at a time.
//    On the desktop, use PdfBoxTextSource (the pdfbox module). On Android, supply your own (see below).
val source: TextSource = ...

// 2. Convert. This blocks and reads every selected page, so call it off the main thread.
val options = ConversionOptions(
    pages = PageSelection.parse("15-402"),   // or PageSelection.ALL
    urls = UrlMode.DOMAIN,                   // DOMAIN, DROP, or KEEP
    disabledRules = setOf(RuleId.FOOTNOTES), // rules to switch off; empty by default
)
val result = Converter().convert(source, "book.pdf", options)  // the name is used in error messages

// 3. Read the paragraphs. Each segment is one paragraph or heading: speak one per utterance.
for (segment in result.transcript.segments) {
    val text = buildString {
        for (token in segment.tokens) {
            if (isNotEmpty() && token.spaceBefore) append(' ')
            append(token.text)
        }
    }
    // segment.kind is PARAGRAPH or HEADING; token.page is the physical page the word came from
}
```

`ConversionOptions.pageMarkers` only affects `LoTextWriter`, the writer for the text-file format. An app that reads `Transcript` directly ignores it.

## Types

| Type | What it is |
|------|------------|
| `Converter` | `convert(source, sourceName, options)` runs the whole pipeline and returns a `ConversionResult`. It holds no state. |
| `ConversionOptions` | `pages: PageSelection`, `pageMarkers: PageMarkers`, `urls: UrlMode`, `disabledRules: Set<RuleId>`. Everything has a default. |
| `ConversionResult` | `transcript`, `removals` (everything removed, with the rule and reason), `warnings`, and `document` (the page layout). |
| `Transcript` | `segments: List<Segment>` and `wordCount`. |
| `Segment` | `kind` (`PARAGRAPH` or `HEADING`) and `tokens: List<Token>`. |
| `Token` | One word: `text`, `spaceBefore`, `spans`, and `page`. A word hyphenated across a line or page break is one token with two `Span`s. |
| `Span` | `page` and `box`, where the word was on the page. See [word positions](#word-positions). |
| `Warning` | `page` and `message`. Today the only warning is a page with no text layer. |
| `LoTextWriter` | Writes a `Transcript` as the text-file format in [usage.md](usage.md#lo-format). |
| `RuleId`, `PageSelection`, `PageMarkers`, `UrlMode` | Option values. |
| `AudioPressException` | Sealed base class of expected failures; see [Errors](#errors). |

## Implementing a `TextSource`

This is what an Android app must provide, because PDFBox does not run on Android.

```kotlin
interface TextSource : AutoCloseable {
    val pageCount: Int
    fun readPage(pageIndex: Int): SourcePage   // 1-based
}
```

A `SourcePage` has `index`, `width`, `height`, and `words`. A `SourceWord` has `text`, `box`, `baseline`, `fontSize`, `horizontal`, and `joinsPrevious`. The cleanup rules depend on every one of these, so a source that cannot provide them cannot support all the rules.

* **Coordinates** are display space: points (1/72 inch), origin at the top-left of the page as a viewer shows it, y increasing downward, after any page rotation.
* **`baseline` and `fontSize`** are what the header, footer, page number, footnote, and heading rules compare. The engine finds the body font size itself, so absolute sizes do not matter, but relative sizes and baselines must be right.
* **`horizontal`** is false for rotated or vertical text, which the engine removes.
* **`joinsPrevious`** is true for a word split off the previous one with no space, such as a superscript footnote marker. The source must split such markers into their own words, because the footnote-marker rule removes them.
* **An empty `words` list** means the page has no text layer. The engine reports a warning, or fails with `NoTextLayerException` if no selected page has any text.

## Word positions

`Token.spans` carry the page and box of every word. Today the boxes are **estimated** from the font size, not from real font metrics, so they are not accurate enough to highlight words as they are read. Exact boxes are part of Milestone Bravo.

## Errors

`Converter.convert` throws `NoPagesSelectedException` (the page selection matches no pages) or `NoTextLayerException` (no selected page has text). A `TextSource` throws the others when it opens a file. The PDFBox source throws `InputNotFoundException`, `InputUnreadableException`, `NotAPdfException`, `CorruptPdfException`, `PdfEncryptedException`, and `ExtractionNotPermittedException`. All have messages meant for the user.

## Using it from the app's build

The engine is a separate Gradle build in `engine/`. The app can include it directly:

```kotlin
// app/settings.gradle.kts
includeBuild("../engine")

// app module build file
dependencies { implementation("io.github.tjnewbry.audiopress:core:0.1.0") }
```

This has not been tried yet, because the app does not exist. The engine is compiled to Java 17 bytecode with Kotlin 2.4, so the app needs Kotlin 2.4 or newer to read it and a Java 17 target.
