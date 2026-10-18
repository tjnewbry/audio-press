package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageSelectionTest {
    @Test
    fun `all pages by default`() {
        assertEquals(listOf(1, 2, 3), PageSelection.ALL.resolve(3))
    }

    @Test
    fun `single pages, ranges, and open-ended ranges`() {
        assertEquals(listOf(1, 5, 6, 7, 9, 10), PageSelection.parse("1,5-7,9-").resolve(10))
    }

    @Test
    fun `pages beyond the end of the document are ignored`() {
        assertEquals(listOf(4), PageSelection.parse("4-8").resolve(4))
        assertEquals(emptyList(), PageSelection.parse("9").resolve(4))
    }

    @Test
    fun `overlapping ranges do not repeat pages`() {
        assertEquals(listOf(2, 3, 4), PageSelection.parse("2-3,3-4").resolve(10))
    }

    @Test
    fun `invalid ranges are rejected`() {
        for (spec in listOf("", "0", "5-3", "abc", "1,,2", "-4", "99999999999")) {
            assertFailsWith<IllegalArgumentException>("\"$spec\" should be rejected") { PageSelection.parse(spec) }
        }
    }
}
