package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.PageTarget
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.WordTarget
import io.github.tjnewbry.audiopress.testing.PageBuilder
import io.github.tjnewbry.audiopress.testing.PageBuilder.Companion.RIGHT
import io.github.tjnewbry.audiopress.testing.layout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each rule has a case where it fires and at least one where it must not. Pages are 648 pt tall, so zones end at 58 and start at 590. */
class CleanupRulesTest {
    private fun PageBuilder.body(lines: Int = 3) =
        repeat(lines) {
            line(
                "Body text that fills the line from edge to edge",
                y =
                    120f + it * 14f,
                right = RIGHT,
            )
        }

    private fun removedText(
        rule: CleanupRule,
        document: LayoutDocument,
    ) = rule.apply(document, Removals.NONE).map { it.text }

    @Test
    fun `rotated-text removes vertical text and keeps horizontal text`() {
        val document =
            layout {
                page {
                    body()
                    line("Downloaded from example.org", y = 400f, x = 20f, size = 8f, horizontal = false)
                }
            }

        assertEquals("Downloaded from example.org", removedText(RotatedTextRule, document).joinToString(" "))
    }

    @Test
    fun `page-numbers removes numbers in the zones in their usual forms`() {
        val document =
            layout {
                page {
                    line("12", y = 40f, x = 210f, size = 9f)
                    body()
                    line("- xii -", y = 620f, x = 200f, size = 9f)
                }
                page {
                    body()
                    line("Page 3 of 10", y = 620f, x = 190f, size = 9f)
                }
            }

        assertEquals(listOf("12", "- xii -", "Page 3 of 10"), removedText(PageNumbersRule, document))
    }

    @Test
    fun `page-numbers removes stray marks in the zones`() {
        val document =
            layout {
                page {
                    line("e", y = 15f, x = 330f, size = 4f)
                    body()
                }
            }

        assertEquals(listOf("e"), removedText(PageNumbersRule, document))
    }

    @Test
    fun `running-headers ignores punctuation that scanning adds`() {
        val document =
            layout {
                page {
                    line("114. THE TEST BOOK", y = 40f, x = 150f, size = 9f)
                    body()
                }
                page { body() }
                page {
                    line("116 THE TEST BOOK", y = 40f, x = 150f, size = 9f)
                    body()
                }
            }

        assertEquals(listOf("114. THE TEST BOOK", "116 THE TEST BOOK"), removedText(RunningHeadersRule, document))
    }

    @Test
    fun `legal-notices removes a small-print license notice anywhere on the page`() {
        val document =
            layout {
                page {
                    body(5)
                    line("This page is shared under a CC BY 4.0 license and was", y = 300f, size = 8f)
                    line("written by the test author.", y = 310f, size = 8f)
                    line("Body text resumes here and mentions creative commons.", y = 340f)
                }
            }

        assertEquals(
            listOf("This page is shared under a CC BY 4.0 license and was", "written by the test author."),
            removedText(LegalNoticesRule, document),
        )
    }

    @Test
    fun `page-numbers keeps numbers in the body and other text in the zones`() {
        val document =
            layout {
                page {
                    line("Chapter 12", y = 40f, x = 180f, size = 9f)
                    line("1984", y = 300f, x = 200f)
                    body()
                }
            }

        assertEquals(emptyList(), removedText(PageNumbersRule, document))
    }

    @Test
    fun `running-headers removes zone lines that repeat on nearby pages, ignoring numbers`() {
        val document =
            layout {
                for (number in 10..13) {
                    page {
                        line(if (number % 2 == 0) "$number THE TEST BOOK" else "CHAPTER ONE $number", y = 40f, x = 150f, size = 9f)
                        body()
                    }
                }
            }

        assertEquals(
            listOf("10 THE TEST BOOK", "CHAPTER ONE 11", "12 THE TEST BOOK", "CHAPTER ONE 13"),
            removedText(RunningHeadersRule, document),
        )
    }

    @Test
    fun `running-headers keeps lines that do not repeat nearby or repeat in the other zone`() {
        val document =
            layout {
                page {
                    line("A HEADING ONLY ONCE", y = 40f, x = 150f, size = 9f)
                    line("SAME TEXT", y = 52f, x = 150f, size = 9f)
                    body()
                }
                page {
                    body()
                    line("SAME TEXT", y = 620f, x = 150f, size = 9f)
                }
                page { body() }
                page {
                    line("A HEADING ONLY ONCE", y = 40f, x = 150f, size = 9f)
                    body()
                }
            }

        assertEquals(emptyList(), removedText(RunningHeadersRule, document))
    }

    @Test
    fun `legal-notices removes copyright lines in the zones only`() {
        val copyright = Char(0x00A9)
        val document =
            layout {
                page {
                    line("We discuss copyright law in this chapter at length", y = 300f)
                    body()
                    line("$copyright 2026 Test Press", y = 620f, x = 180f, size = 8f)
                }
            }

        assertEquals(listOf("$copyright 2026 Test Press"), removedText(LegalNoticesRule, document))
    }

    @Test
    fun `copyright-page skips a copyright page near the front but not in the middle`() {
        val document =
            layout(pageCount = 30) {
                page { line("Copyright 2026 Test Author. All rights reserved.", y = 300f) }
                repeat(13) { page { body() } }
                page { line("All rights reserved, said the lawyer.", y = 300f) }
            }

        assertEquals(listOf(PageTarget(1)), CopyrightPageRule.apply(document, Removals.NONE).map { it.target })
    }

    @Test
    fun `contents-and-index skips pages of entries that end in page numbers`() {
        val document =
            layout {
                page {
                    for (i in 0 until 9) line("Chapter Number ${i + 1} .......... ${i * 10 + 3}", y = 120f + i * 14f)
                }
                page { body(9) }
                page {
                    for (i in 0 until 7) line("Short contents entry ${i + 1}", y = 120f + i * 14f)
                }
                page {
                    for (i in 0 until 9) line("y = ${i + 1}", y = 120f + i * 14f)
                }
            }

        assertEquals(listOf(PageTarget(1)), ContentsAndIndexRule.apply(document, Removals.NONE).map { it.target })
    }

    @Test
    fun `footnotes removes small print at the bottom of a page with body text above`() {
        val document =
            layout {
                page {
                    body()
                    line("1 A footnote that should not be read.", y = 560f, size = 8f)
                    line("2 And a second one.", y = 570f, size = 8f)
                }
            }

        assertEquals(listOf("1 A footnote that should not be read.", "2 And a second one."), removedText(FootnotesRule, document))
    }

    @Test
    fun `footnotes keeps small print that is followed by body text or fills the page`() {
        val document =
            layout {
                page {
                    line("A small caption above the text", y = 100f, size = 8f)
                    body()
                }
                page {
                    line("A page of small print only", y = 100f, size = 8f)
                    line("with nothing larger on it", y = 110f, size = 8f)
                }
                page {
                    line("Body text, then small print, then body text again at the end", y = 300f, right = RIGHT)
                    body()
                }
            }

        assertEquals(emptyList(), removedText(FootnotesRule, document))
    }

    @Test
    fun `footnote-markers removes raised numbers and symbols attached to words`() {
        val document =
            layout {
                page {
                    line("It was summer.^3 Then autumn^* came.", y = 100f)
                    body()
                }
            }

        val removals = FootnoteMarkersRule.apply(document, Removals.NONE)

        assertEquals(listOf("3", "*"), removals.map { it.text })
        assertEquals(WordTarget(1, 0, 3), removals.first().target)
        assertEquals("after \"summer.\"", removals.first().reason)
    }

    @Test
    fun `footnote-markers keeps raised letters and ordinary numbers`() {
        val document =
            layout {
                page {
                    line("The 1^st of May, in 1865 and 12 more", y = 100f)
                    body()
                }
            }

        assertEquals(emptyList(), removedText(FootnoteMarkersRule, document))
    }
}
