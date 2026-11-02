package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportRangeTest {
    private val total = 457

    @Test
    fun `typed text is corrected to digits within the PDF`() {
        assertEquals("", correctTyped("", total))
        assertEquals("", correctTyped("abc", total))
        assertEquals("12", correctTyped("1a2", total))
        assertEquals("1", correctTyped("0", total)) // at least page 1
        assertEquals("7", correctTyped("007", total))
        assertEquals("457", correctTyped("999", total)) // at most the last page
        assertEquals("457", correctTyped("99999999999999999999", total))
        assertEquals("30", correctTyped("30", 30))
    }

    @Test
    fun `a start after the end, or an end before the start, is not taken while typing`() {
        val range = PageRange(10, 40)

        assertEquals(PageRange(12, 40), typeStart(range, 12))
        assertEquals(PageRange(40, 40), typeStart(range, 40))
        assertEquals(range, typeStart(range, 41))
        assertEquals(range, typeStart(range, 0))
        assertEquals(range, typeStart(range, null))
        assertEquals(PageRange(10, 457), typeEnd(range, 457, total))
        assertEquals(PageRange(10, 10), typeEnd(range, 10, total))
        assertEquals(range, typeEnd(range, 9, total))
        assertEquals(range, typeEnd(range, 458, total))
        assertEquals(range, typeEnd(range, null, total))
    }

    @Test
    fun `settling corrects the boxes into a possible range`() {
        assertEquals(PageRange(1, 457), settle(PageRange(1, 457), null, null, total))
        assertEquals(PageRange(20, 20), settle(PageRange(10, 20), 50, 20, total)) // start cannot pass the end
        assertEquals(PageRange(30, 30), settle(PageRange(30, 40), 30, 5, total)) // end cannot come before the start's number
        assertEquals(PageRange(1, 457), settle(PageRange(5, 9), 0, 9999, total))
        assertEquals(PageRange(5, 9), settle(PageRange(5, 9), null, null, total))
    }

    @Test
    fun `settled ranges are always valid`() {
        for (s in listOf(null, 0, 1, 5, 300, 457, 900)) {
            for (e in listOf(null, 0, 1, 5, 300, 457, 900)) {
                assertTrue(isValid(settle(PageRange(20, 30), s, e, total), total), "start $s end $e")
            }
        }
        assertFalse(isValid(PageRange(0, 5), total))
        assertFalse(isValid(PageRange(6, 5), total))
        assertFalse(isValid(PageRange(1, 458), total))
    }

    @Test
    fun `the name follows the range until the user types one`() {
        assertEquals("Title", importName("Title", null, PageRange.whole(total), total))
        assertEquals("Title (pp. 12-340)", importName("Title", null, PageRange(12, 340), total))
        assertEquals("Title (pp. 2-457)", importName("Title", null, PageRange(2, 457), total))
        assertEquals("Title (pp. 1-456)", importName("Title", null, PageRange(1, 456), total))
        assertEquals("Title (p. 9)", importName("Title", null, PageRange(9, 9), total))
        assertEquals("Title", importName("Title", null, PageRange(1, 457), total)) // back to the whole PDF: no suffix
        assertEquals("1 page", importName("1 page", null, PageRange(1, 1), 1))
    }

    @Test
    fun `a name the user typed is never touched`() {
        assertEquals("Mine", importName("Title", "Mine", PageRange(12, 340), total))
        assertEquals("Mine", importName("Title", "Mine", PageRange.whole(total), total))
    }

    @Test
    fun `the default name is the PDF title, else the file name`() {
        assertEquals("A Real Title", defaultImportName("  A Real Title ", "x.pdf"))
        assertEquals("my-file", defaultImportName(null, "my-file.pdf"))
        assertEquals("my-file", defaultImportName("   ", "my-file.pdf"))
    }

    @Test
    fun `the popup offers start before the start marker, end after the end marker, both between`() {
        val range = PageRange(11, 20) // markers at boundaries 10 and 20

        assertEquals(listOf(MarkOption.START), markOptions(0, range, total))
        assertEquals(listOf(MarkOption.START), markOptions(9, range, total))
        assertEquals(listOf(MarkOption.START, MarkOption.END), markOptions(11, range, total))
        assertEquals(listOf(MarkOption.START, MarkOption.END), markOptions(19, range, total))
        assertEquals(listOf(MarkOption.END), markOptions(21, range, total))
        assertEquals(listOf(MarkOption.END), markOptions(total, range, total))
    }

    @Test
    fun `the markers' own boundaries and places outside the PDF offer nothing`() {
        val range = PageRange(11, 20)

        assertEquals(emptyList(), markOptions(10, range, total))
        assertEquals(emptyList(), markOptions(20, range, total))
        assertEquals(emptyList(), markOptions(-1, range, total))
        assertEquals(emptyList(), markOptions(total + 1, range, total))
    }

    @Test
    fun `with the whole PDF chosen every boundary between the ends offers both`() {
        val whole = PageRange.whole(total) // markers at boundaries 0 and 457: everything between offers both
        assertEquals(emptyList(), markOptions(0, whole, total))
        assertEquals(emptyList(), markOptions(total, whole, total))
        assertEquals(listOf(MarkOption.START, MarkOption.END), markOptions(1, whole, total))
        assertEquals(listOf(MarkOption.START, MarkOption.END), markOptions(456, whole, total))
    }

    @Test
    fun `marking moves only the chosen marker and only to valid places`() {
        val range = PageRange(11, 20)

        assertEquals(PageRange(5, 20), markAt(4, MarkOption.START, range, total)) // boundary 4 is the top of page 5
        assertEquals(PageRange(15, 20), markAt(14, MarkOption.START, range, total))
        assertEquals(PageRange(11, 300), markAt(300, MarkOption.END, range, total))
        assertEquals(PageRange(11, 12), markAt(12, MarkOption.END, range, total))
        assertEquals(range, markAt(4, MarkOption.END, range, total)) // an end before the start is not offered
        assertEquals(range, markAt(30, MarkOption.START, range, total)) // nor a start after the end
        assertTrue(isValid(markAt(0, MarkOption.START, range, total), total))
    }

    @Test
    fun `the first touch of the screen being Import goes straight ahead, otherwise an unrenamed name is confirmed`() {
        assertFalse(needsNameConfirmation(interacted = false, typedName = null))
        assertTrue(needsNameConfirmation(interacted = true, typedName = null))
        assertFalse(needsNameConfirmation(interacted = true, typedName = "Mine"))
        assertFalse(needsNameConfirmation(interacted = false, typedName = "Mine"))
    }

    @Test
    fun `the bubble names the page at the thumb's height and reaches both ends`() {
        val pagePx = 1000f
        val viewport = 2000f
        val maxScroll = total * pagePx + 100f - viewport

        assertEquals(1, pageUnderThumb(0f, 0f, viewport, pagePx, total))
        assertEquals(total, pageUnderThumb(1f, maxScroll, viewport, pagePx, total))
        assertEquals(3, pageUnderThumb(0f, 2000f, viewport, pagePx, total)) // scrolled two pages, thumb at the top
        assertEquals(1, pageUnderThumb(0.2f, 0f, viewport, pagePx, total))
        assertEquals(1, pageUnderThumb(0.5f, 0f, viewport, 0f, total)) // before the rows are measured
    }
}
