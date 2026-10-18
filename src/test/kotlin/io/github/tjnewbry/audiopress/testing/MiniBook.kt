package io.github.tjnewbry.audiopress.testing

import java.nio.file.Path

/**
 * A six-page "book" with original text that exercises every Alpha cleanup rule, for end-to-end golden
 * tests. Expected output is in `src/test/resources/golden/mini-book*.txt`.
 *
 * * Page 1: a copyright page. Page 2: a table of contents. Both are skipped.
 * * Pages 3 to 6: running headers (alternating book and chapter titles), page numbers, a rotated margin
 *   notice, a heading, first-line indents, hyphenation, a paragraph that runs across a page break, a
 *   footnote marker and footnote, a URL, a bulleted list, and a legal line in the footer.
 */
object MiniBook {
    private const val LEFT = 54f
    private const val INDENT = 72f
    private const val RIGHT = 378f
    private val BULLET = Char(0x2022)

    fun write(path: Path): Path =
        fixturePdf(path) {
            page {
                line("Copyright 2026 Test Author. All rights reserved.", x = LEFT, y = 300f)
                line("ISBN 978-1-23-456789-0", x = LEFT, y = 314f)
            }
            page {
                line("Contents", x = 180f, y = 110f, size = 16f)
                for (i in 0 until 8) line("Chapter Number ${i + 1} .......... ${3 + i * 4}", x = LEFT, y = 150f + i * 14f)
            }
            bookPage(3, header = "CHAPTER ONE") {
                line("Chapter One", x = 160f, y = 110f, size = 18f)
                line("The lighthouse keeper woke before dawn, as he always did,", x = INDENT, y = 150f, right = RIGHT)
                line("and climbed the hundred-step stair to check the lamp at", x = LEFT, y = 164f, right = RIGHT)
                line("the top of the tower.", x = LEFT, y = 178f)
                line("Fog had rolled in from the sea during the night. Far", x = INDENT, y = 192f, right = RIGHT)
                line("below him a fishing boat crept along the coast, its dis-", x = LEFT, y = 206f, right = RIGHT)
                line("tant engine the only sound in the grey morning. He watched", x = LEFT, y = 220f, right = RIGHT)
                line("it for a long time, until the boat was no more than a", x = LEFT, y = 234f, right = RIGHT)
            }
            bookPage(4, header = "THE TEST BOOK") {
                line("shadow, and then he went back down to make his breakfast", x = LEFT, y = 110f, right = RIGHT)
                line("of bread and strong black tea.^1", x = LEFT, y = 124f)
                line("The supply ship was due at noon. He had no use for the", x = INDENT, y = 138f, right = RIGHT)
                line("radio. See www.example.com/test now.", x = LEFT, y = 152f)
                line("1 The keeper drank his tea without sugar.", x = LEFT, y = 570f, size = 8f)
            }
            bookPage(5, header = "CHAPTER ONE", legal = "Licensed to Test Reader") {
                line("He had three jobs to do before noon:", x = INDENT, y = 110f)
                line("$BULLET the lamp,", x = LEFT, y = 124f)
                line("$BULLET the logbook, and", x = LEFT, y = 138f)
                line("$BULLET the stores.", x = LEFT, y = 152f)
                line("The ship brought letters, flour, and a crate of Anglo-", x = INDENT, y = 166f, right = RIGHT)
                line("Saxon history books for the long winter.", x = LEFT, y = 180f)
            }
            bookPage(6, header = "THE TEST BOOK") {
                line("The end.", x = INDENT, y = 110f)
            }
        }

    private fun FixturePdf.bookPage(
        number: Int,
        header: String,
        legal: String? = null,
        content: FixturePage.() -> Unit,
    ) = page {
        line(header, x = 170f, y = 36f, size = 9f)
        line("$number", x = 214f, y = 620f, size = 9f)
        if (legal != null) line(legal, x = 160f, y = 634f, size = 7f)
        rotatedText("Downloaded from example.org", x = 20f, y = 500f)
        content()
    }
}
