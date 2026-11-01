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
| `Converter` | `convert(source, sourceName, options, cancellation = null, onProgress = {})` runs the whole pipeline and returns a `ConversionResult`. It holds no state. The last two parameters are optional; see [Progress and cancellation](#progress-and-cancellation). |
| `ConversionProgress`, `ConversionStage`, `CancellationToken` | Progress reports and the cancel switch. |
| `SpeechChunker`, `Utterance`, `SpokenWord`, `TextRange` | Splits a `Transcript` into pieces for text to speech and maps TTS offsets back to words; see [Speaking a transcript](#speaking-a-transcript). |
| `ConversionOptions` | `pages: PageSelection`, `pageMarkers: PageMarkers`, `urls: UrlMode`, `disabledRules: Set<RuleId>`. Everything has a default. |
| `ConversionResult` | `transcript`, `removals` (everything removed, with the rule and reason), `warnings`, and `document` (the page layout). |
| `Transcript` | `segments: List<Segment>` and `wordCount`. |
| `Segment` | `kind` (`PARAGRAPH` or `HEADING`), `tokens: List<Token>`, and `text`, the words joined with spaces. |
| `Token` | One word: `text`, `spaceBefore`, `spans`, and `page`. A word hyphenated across a line or page break is one token with two `Span`s. |
| `Span` | `page` and `box`, where the word was on the page. See [word positions](#word-positions). |
| `Warning` | `page` and `message`. Today the only warning is a page with no text layer. |
| `LoTextWriter` | Writes a `Transcript` as the text-file format in [usage.md](usage.md#lo-format). |
| `RuleId`, `PageSelection`, `PageMarkers`, `UrlMode` | Option values. |
| `AudioPressException` | Sealed base class of expected failures; see [Errors](#errors). |

## Progress and cancellation

A conversion of a long book takes a while (about a minute for 600 pages on the emulator), almost all of it reading pages. `Converter.convert` takes two optional parameters, so existing calls are unchanged.

```kotlin
val token = CancellationToken()          // keep it; call token.cancel() from any thread, for example the Cancel button
val result =
    try {
        Converter().convert(source, "book.pdf", options, token) { p ->
            // p.stage is READING or ANALYZING; p.done of p.total steps; p.fraction is 0..1 for one progress bar
            progressBar.value = p.fraction
        }
    } catch (e: ConversionCancelledException) {
        null                              // the user cancelled: there is no result
    }
```

* **Reports.** `READING` reports `done` of `total` pages (`total` is the number of *selected* pages), once at 0 and then after every page. `ANALYZING` then reports one step per enabled cleanup rule plus one for the paragraph assembly. Within a stage `done` only increases and the last report has `done == total`. `fraction` (0 to 1) gives reading 95 percent of the bar, because it is almost all the time.
* **Threading.** `onProgress` runs on the thread that called `convert`, so hop to the main thread before touching the UI, and keep it quick: it delays the conversion. If it throws, the conversion stops with that exception. `CancellationToken.cancel()` is safe from any thread. A token cancels once; make a new one for the next conversion.
* **Cancelling.** The token is checked before every page and before every rule and the assembly, so a cancel takes effect within one page read (typically milliseconds). `convert` then throws `ConversionCancelledException` and returns nothing: there is no partial result. Nothing else is left behind, because `Converter` keeps no state. The `TextSource` stays open and is still the caller's to `close()`. Once the last report (`ANALYZING`, `done == total`) is sent, the result is already complete and a late `cancel()` has no effect.

## Speaking a transcript

Android's `TextToSpeech` refuses text longer than `getMaxSpeechInputLength()` (4000 characters). `SpeechChunker` splits a `Transcript` into `Utterance`s that are safe to speak, one `speak` call each. `Segment.text` is the text of one paragraph or heading (the tokens joined as in the first example above).

```kotlin
val utterances = SpeechChunker().chunk(result.transcript)   // maxChars = 3900 by default

val u = utterances[i]
tts.speak(u.text, TextToSpeech.QUEUE_FLUSH, null, "utterance-$i")
// u.segmentIndex, u.kind, u.page: where it is in the book; u.words: its words, for highlighting

// In UtteranceProgressListener.onRangeStart(utteranceId, start, end, frame), with u the utterance being spoken:
val word = u.wordAt(start)                // SpokenWord: tokenIndex, page, range in the segment's text; null only if no words
val inSegment = u.segmentRange(start, end) // TextRange in the segment's text (Segment.text)
// highlight inSegment (or the token word.tokenIndex) in the paragraph shown for segment u.segmentIndex

val seconds = u.estimatedSeconds()                          // 170 words per minute by default
val total = result.transcript.estimatedSeconds(wordsPerMinute = 170)
```

* **One utterance per paragraph or heading**, unless the text is longer than `maxChars`. `Utterance.text` is exactly characters `[start, end)` of the segment's text, so an offset in the utterance is `start` plus the offset in the segment.
* **Long paragraphs** are cut at sentence ends (`.`, `!`, `?`, an ellipsis, plus closing quotes and brackets), packing as many whole sentences as fit. A period does not end a sentence after a common abbreviation (Mr., Mrs., Dr., Prof., St., vs., etc., e.g., i.e., a.m., p.m., and a few more), after initials (J. R. R., U.S.), inside a number (3.14), or before a lowercase word; `!` and `?` always do. A sentence that is still too long is cut after its last comma or semicolon (followed by a space), then at its last space. Only a single word longer than `maxChars` is cut inside the word, and `SpokenWord.range` then covers just the part in that utterance. `maxChars` must be at least 8.
* **Highlighting.** `wordAt` gives the word containing the offset TTS reports; an offset on a space gives the next word. Offsets are UTF-16 indexes, which is what Android reports. Word boxes on the page are not part of this; they arrive with Milestone Bravo.
* **Duration.** The engine provides a simple estimate, `words * 60 / wordsPerMinute` (`SpeechChunker.estimatedSeconds`, `Utterance.estimatedSeconds`, `Transcript.estimatedSeconds`; default 170 words per minute). The real time depends on the voice and the speech rate the app sets, so the app should divide by its rate multiplier and may replace the estimate with measured time once utterances have been spoken.
* `SpeechChunker` holds no state and is cheap: chunking a 180,000-word book is a single pass over the text.

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

`Converter.convert` throws `NoPagesSelectedException` (the page selection matches no pages) or `NoTextLayerException` (no selected page has text). A `TextSource` throws the others when it opens a file. The PDFBox source throws `InputNotFoundException`, `InputUnreadableException`, `NotAPdfException`, `CorruptPdfException`, `PdfEncryptedException`, and `ExtractionNotPermittedException`. All have messages meant for the user. A cancelled conversion throws `ConversionCancelledException`, which is also an `AudioPressException`: catch it before the general case, and do not show it as an error.

## Android compatibility

`core` is meant to run on Android 13 (API 33) and up. It was checked by listing every JDK class and method its compiled bytecode calls (including Kotlin standard library code inlined into it) and comparing them with the Android SDK's API list: it uses only `java.lang` and `java.util` basics (collections, `StringBuilder`, `Character`, `Math`, `String.format` and `lowercase` with `Locale.ROOT`), all available since API 24 or earlier. `ArchitectureTest` enforces the rules below.

Rules for engine code in `core`:

* Use only Kotlin, `core`'s own package, and `java.util.Locale`. Check any new JDK class against API 33 before adding it to `ALLOWED_IMPORTS` in `ArchitectureTest`. No `java.nio.file`, `java.time`, `java.util.stream`, `java.awt`, reflection, threads, or files.
* The build compiles with `-Xjdk-release=17`, so Java 18+ methods do not compile. Android 13 has fewer Java 12 to 17 methods than the JDK (for example `String.formatted` and `Stream.toList` are missing), so keep to the older, plain APIs.
* Regexes run on ICU on Android, not `java.util.regex`. Use `[0-9]` instead of `\d`, and avoid `\w`, `\b`, `\p{IsAlphabetic}`, `\p{javaLowerCase}`, `\R`, `\X`, `\h`, possessive quantifiers, and named groups. Write special characters as `\x{...}` and escape `-`, `{`, and `}` inside character classes. `\s` and `\S` are used, but they differ only for U+1680, U+2028, and U+2029, which real PDF text does not contain.

The app must build with Java 17 bytecode support (Android Gradle Plugin 8 or newer, which also rewrites the string-concatenation and lambda `invokedynamic` calls that Kotlin emits).

## Using it from the app's build

The engine is a separate Gradle build in `engine/`. The app can include it directly:

```kotlin
// app/settings.gradle.kts
includeBuild("../engine")

// app module build file
dependencies { implementation("io.github.tjnewbry.audiopress:core:0.1.0") }
```

This has not been tried yet, because the app does not exist. The engine is compiled to Java 17 bytecode with Kotlin 2.4, so the app needs Kotlin 2.4 or newer to read it and a Java 17 target.
