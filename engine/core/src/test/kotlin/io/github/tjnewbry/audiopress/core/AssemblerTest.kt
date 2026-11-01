package io.github.tjnewbry.audiopress.core

import io.github.tjnewbry.audiopress.core.rules.FootnoteMarkersRule
import io.github.tjnewbry.audiopress.testing.PageBuilder.Companion.RIGHT
import io.github.tjnewbry.audiopress.testing.layout
import kotlin.test.Test
import kotlin.test.assertEquals

class AssemblerTest {
    private fun paragraphs(
        document: LayoutDocument,
        removed: Removals = Removals.NONE,
    ): List<String> =
        Assembler.assemble(document, removed).segments.map { segment ->
            buildString {
                segment.tokens.forEach { token ->
                    if (isNotEmpty() && token.spaceBefore) append(' ')
                    append(token.text)
                }
            }
        }

    @Test
    fun `joins full lines and ends a paragraph at a short line`() {
        val document =
            layout {
                page {
                    line("The first line of a paragraph runs all the way across", y = 120f, right = RIGHT)
                    line("and the second line ends early.", y = 134f)
                    line("A new paragraph begins after the short line above it", y = 148f, right = RIGHT)
                    line("and ends here.", y = 162f)
                }
            }

        assertEquals(
            listOf(
                "The first line of a paragraph runs all the way across and the second line ends early.",
                "A new paragraph begins after the short line above it and ends here.",
            ),
            paragraphs(document),
        )
    }

    @Test
    fun `an indented line starts a paragraph, but an indented block stays together`() {
        val document =
            layout {
                page {
                    line("A paragraph whose lines all run the full width and", y = 92f, right = RIGHT)
                    line("start at the left edge of the text, so that edge is", y = 106f, right = RIGHT)
                    line("easy to find, and which ends with a full line of text", y = 120f, right = RIGHT)
                    line("This indented line starts a new paragraph that keeps", y = 134f, x = 72f, right = RIGHT)
                    line("going on the next line, which is not indented at all", y = 148f, right = RIGHT)
                    line("A block quote is indented on every one of its lines", y = 162f, x = 72f, right = RIGHT)
                    line("so its second line must not start a new paragraph", y = 176f, x = 72f, right = RIGHT)
                }
            }

        assertEquals(3, paragraphs(document).size)
    }

    @Test
    fun `a large gap starts a paragraph`() {
        val document =
            layout {
                page {
                    for (i in 0 until 3) line("Lines that are full width so only the gap can split them", y = 120f + i * 14f, right = RIGHT)
                    line("After a blank line comes another full width line of text", y = 170f + 14f, right = RIGHT)
                }
            }

        assertEquals(2, paragraphs(document).size)
    }

    @Test
    fun `headings are their own segments and a two-line heading stays together`() {
        val document =
            layout {
                page {
                    line("Chapter One", y = 100f, x = 150f, size = 18f)
                    line("The Beginning", y = 122f, x = 150f, size = 18f)
                    line("Body text that starts right after the heading here", y = 160f, right = RIGHT)
                    line("and ends.", y = 174f)
                }
            }

        val transcript = Assembler.assemble(document, Removals.NONE)

        assertEquals(listOf(SegmentKind.HEADING, SegmentKind.PARAGRAPH), transcript.segments.map { it.kind })
        assertEquals("Chapter One The Beginning", paragraphs(document).first())
    }

    @Test
    fun `line-end hyphens are joined by the documented rules`() {
        val softHyphen = TextNormalizer.SOFT_HYPHEN
        val emDash = Char(0x2014)
        val document =
            layout {
                page {
                    line("The rules join an inter-", y = 120f, right = RIGHT)
                    line("national word and keep Anglo-", y = 134f, right = RIGHT)
                    line("Saxon words whole, while a soft hy$softHyphen", y = 148f, right = RIGHT)
                    line("phen goes away and a dash$emDash", y = 162f, right = RIGHT)
                    line("joins with no space.", y = 176f)
                }
            }

        assertEquals(
            listOf(
                "The rules join an international word and keep Anglo-Saxon words whole, while a soft hyphen goes away and a dash${emDash}joins with no space.",
            ),
            paragraphs(document),
        )
    }

    @Test
    fun `a paragraph continues onto the next page, keeping both spans of a hyphenated word`() {
        val document =
            layout {
                page { line("The last line of the page breaks a word in the mid-", y = 600f - 40f, right = RIGHT) }
                page { line("dle and the paragraph goes on.", y = 100f) }
            }

        val transcript = Assembler.assemble(document, Removals.NONE)

        assertEquals(listOf("The last line of the page breaks a word in the middle and the paragraph goes on."), paragraphs(document))
        val middle =
            transcript.segments
                .single()
                .tokens
                .single { it.text == "middle" }
        assertEquals(listOf(1, 2), middle.spans.map { it.page })
    }

    @Test
    fun `bullets start paragraphs and the bullet is not read`() {
        val bullet = Char(0x2022)
        val document =
            layout {
                page {
                    line("Remember these things:", y = 120f)
                    line("$bullet the stairs,", y = 134f)
                    line("$bullet the lift.", y = 148f)
                }
            }

        assertEquals(listOf("Remember these things:", "the stairs,", "the lift."), paragraphs(document))
    }

    @Test
    fun `removed words are skipped and the following punctuation still joins`() {
        val document =
            layout {
                page { line("It was summer.^3 Then autumn came.", y = 120f) }
            }
        val removed = Removals.NONE + FootnoteMarkersRule.apply(document, Removals.NONE)

        assertEquals(listOf("It was summer. Then autumn came."), paragraphs(document, removed))
    }
}
