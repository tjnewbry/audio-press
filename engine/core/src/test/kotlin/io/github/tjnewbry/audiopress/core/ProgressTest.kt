package io.github.tjnewbry.audiopress.core

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {
    /** A book of [pageCount] pages of plain body text. [onRead] runs each time a page is read. */
    private class BookSource(
        override val pageCount: Int,
        private val onRead: (Int) -> Unit = {},
    ) : TextSource {
        val pagesRead = mutableListOf<Int>()

        override fun readPage(pageIndex: Int): SourcePage {
            pagesRead += pageIndex
            onRead(pageIndex)
            val words =
                (0 until 12).flatMap { row ->
                    listOf("Line", "$row", "of", "page", "$pageIndex").mapIndexed { col, text ->
                        val left = 72f + col * 40f
                        val baseline = 100f + row * 14f
                        SourceWord(text, Box(left, baseline - 9f, left + 30f, baseline + 2f), baseline, 11f)
                    }
                }
            return SourcePage(pageIndex, 432f, 648f, words)
        }

        override fun close() = Unit
    }

    @Test
    fun `reports every page while reading, then the analysis steps, ending complete`() {
        val reports = mutableListOf<ConversionProgress>()

        Converter().convert(BookSource(5), "book", ConversionOptions(), onProgress = { reports += it })

        val reading = reports.filter { it.stage == ConversionStage.READING }
        assertEquals((0..5).toList(), reading.map { it.done })
        assertTrue(reading.all { it.total == 5 })
        val analyzing = reports.filter { it.stage == ConversionStage.ANALYZING }
        assertEquals(ConversionStage.ANALYZING, reports.last().stage)
        assertEquals(analyzing.last().total, analyzing.last().done)
        assertEquals((0..analyzing.last().total).toList(), analyzing.map { it.done })
        assertEquals(reports.map { it.stage }.sortedBy { it.ordinal }, reports.map { it.stage }, "reading comes before analyzing")
    }

    @Test
    fun `the overall fraction never goes down and ends at 1`() {
        val fractions = mutableListOf<Float>()

        Converter().convert(BookSource(7), "book", ConversionOptions(), onProgress = { fractions += it.fraction })

        assertEquals(fractions.sorted(), fractions)
        assertEquals(0f, fractions.first())
        assertEquals(1f, fractions.last(), 0.0001f)
    }

    @Test
    fun `progress totals count only the selected pages and the enabled rules`() {
        val reports = mutableListOf<ConversionProgress>()
        val options = ConversionOptions(pages = PageSelection.parse("2-4"), disabledRules = setOf(RuleId.FOOTNOTES, RuleId.PAGE_NUMBERS))

        Converter().convert(BookSource(10), "book", options, onProgress = { reports += it })

        assertEquals(3, reports.first().total)
        assertEquals(3, reports.last { it.stage == ConversionStage.READING }.done)
        assertEquals(DefaultRulesCount - 2 + 1, reports.last().total)
    }

    @Test
    fun `conversion without progress or cancellation gives the same result as before`() {
        val plain = Converter().convert(BookSource(4), "book", ConversionOptions())
        val watched = Converter().convert(BookSource(4), "book", ConversionOptions(), CancellationToken()) { }

        assertEquals(plain.transcript, watched.transcript)
        assertEquals(plain.removals, watched.removals)
        assertEquals(plain.warnings, watched.warnings)
    }

    @Test
    fun `a token cancelled before the start reads no pages`() {
        val source = BookSource(5)
        val token = CancellationToken().apply { cancel() }

        assertFailsWith<ConversionCancelledException> { Converter().convert(source, "book", ConversionOptions(), token) }

        assertEquals(emptyList(), source.pagesRead)
    }

    @Test
    fun `cancelling during reading stops before the next page`() {
        val token = CancellationToken()
        val source = BookSource(20) { page -> if (page == 3) token.cancel() }

        assertFailsWith<ConversionCancelledException> { Converter().convert(source, "book", ConversionOptions(), token) }

        assertEquals(listOf(1, 2, 3), source.pagesRead)
    }

    @Test
    fun `cancelling from the progress callback stops the conversion`() {
        val token = CancellationToken()
        val seen = mutableListOf<ConversionProgress>()

        assertFailsWith<ConversionCancelledException> {
            Converter().convert(BookSource(20), "book", ConversionOptions(), token) {
                seen += it
                if (it.stage == ConversionStage.READING && it.done == 4) token.cancel()
            }
        }

        assertEquals(4, seen.last().done)
        assertTrue(seen.all { it.stage == ConversionStage.READING }, "no analysis after cancelling")
    }

    @Test
    fun `cancelling during the analysis steps produces no result`() {
        val token = CancellationToken()
        var result: ConversionResult? = null

        assertFailsWith<ConversionCancelledException> {
            result =
                Converter().convert(BookSource(3), "book", ConversionOptions(), token) {
                    if (it.stage == ConversionStage.ANALYZING && it.done == 2) token.cancel()
                }
        }

        assertEquals(null, result)
    }

    @Test
    fun `another thread can cancel a running conversion`() {
        val token = CancellationToken()
        val started = java.util.concurrent.CountDownLatch(1)
        val source =
            BookSource(100_000) { page ->
                if (page == 2) started.countDown()
                Thread.sleep(1)
            }
        var failure: Throwable? = null
        val worker =
            thread {
                try {
                    Converter().convert(source, "book", ConversionOptions(), token)
                } catch (e: Throwable) {
                    failure = e
                }
            }

        started.await()
        token.cancel()
        worker.join(5_000)

        assertFalse(worker.isAlive, "the conversion stopped promptly")
        assertTrue(failure is ConversionCancelledException)
        assertTrue(source.pagesRead.size < 1_000)
    }

    @Test
    fun `a cancellation is an expected AudioPressException with a readable message`() {
        val error: AudioPressException = ConversionCancelledException()

        assertEquals("The conversion was cancelled", error.message)
    }

    @Test
    fun `a token stays cancelled`() {
        val token = CancellationToken()
        assertFalse(token.isCancelled)

        token.cancel()

        assertTrue(token.isCancelled)
        assertFailsWith<ConversionCancelledException> { token.throwIfCancelled() }
    }

    private companion object {
        val DefaultRulesCount = io.github.tjnewbry.audiopress.core.rules.DefaultRules.all.size
    }

    @Test
    fun `the source is not used again once reading has finished, so it can be closed early to free memory`() {
        val inner = BookSource(4)
        var closed = false
        val source =
            object : TextSource by inner {
                override fun readPage(pageIndex: Int): SourcePage = inner.readPage(pageIndex)

                override val pageCount: Int
                    get() = if (closed) error("the source was used after it was closed") else inner.pageCount
            }

        val result =
            Converter().convert(source, "book", ConversionOptions()) {
                if (it.stage == ConversionStage.ANALYZING && it.done == 0) closed = true
            }

        assertTrue(closed)
        assertEquals(4, result.document.pageCount)
    }

    @Test
    fun `equal words share one string while pages are read, without changing any text`() {
        val interner = WordInterner()
        val a = String(charArrayOf('t', 'h', 'e'))
        val b = String(charArrayOf('t', 'h', 'e'))
        val long = "x".repeat(40)

        assertTrue(interner.intern(a) === interner.intern(b), "equal words become the same object")
        assertEquals("the", interner.intern(b))
        assertEquals(long, interner.intern(long))
        val page =
            SourcePage(1, 100f, 100f, listOf(SourceWord(a, Box(0f, 0f, 1f, 1f), 1f, 10f), SourceWord(b, Box(2f, 0f, 3f, 1f), 1f, 10f)))
        val words = TextNormalizer.normalize(page, interner).words
        assertTrue(words[0].text === words[1].text)
        assertEquals(listOf("the", "the"), words.map { it.text })
    }
}
