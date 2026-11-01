package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentTitleTest {
    @Test
    fun `a real title is kept, trimmed, with its spaces tidied`() {
        assertEquals("The Clockmaker's Daughter", DocumentTitle.clean("  The Clockmaker's   Daughter \n"))
        assertEquals("A Study in Brass", DocumentTitle.clean("${Char(0xFEFF)}A Study\tin Brass"))
    }

    @Test
    fun `missing and blank titles are null`() {
        assertNull(DocumentTitle.clean(null))
        assertNull(DocumentTitle.clean(""))
        assertNull(DocumentTitle.clean("  \t\n "))
    }

    @Test
    fun `placeholder titles are null`() {
        for (junk in listOf(
            "Untitled",
            "UNTITLED",
            "Untitled Document",
            "Untitled-1",
            "Untitled 2",
            "untitled (3)",
            "Title",
            "Document",
            "(anonymous)",
        )) {
            assertNull(DocumentTitle.clean(junk), junk)
        }
    }

    @Test
    fun `titles left behind by programs and file names are null`() {
        for (junk in listOf(
            "Microsoft Word - notes.docx",
            "microsoft powerpoint - slides",
            "chapter3.docx",
            "scan_0042.PDF",
            "report.indd",
        )) {
            assertNull(DocumentTitle.clean(junk), junk)
        }
    }

    @Test
    fun `a title that merely starts with untitled or contains a file-like word is kept`() {
        assertEquals("Untitled Lives", DocumentTitle.clean("Untitled Lives"))
        assertEquals("Word Order in Old English", DocumentTitle.clean("Word Order in Old English"))
    }

    @Test
    fun `reads the default-language title from an XMP packet`() {
        val xmp =
            """<x:xmpmeta><rdf:RDF><rdf:Description><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Salt &amp; Iron: A History</rdf:li></rdf:Alt></dc:title>
            |<dc:creator><rdf:Seq><rdf:li>Someone</rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta>
            """.trimMargin()

        assertEquals("Salt & Iron: A History", DocumentTitle.fromXmp(xmp))
    }

    @Test
    fun `an XMP packet without a usable title gives null`() {
        assertNull(DocumentTitle.fromXmp("<x:xmpmeta><dc:creator><rdf:Seq><rdf:li>Someone</rdf:li></rdf:Seq></dc:creator></x:xmpmeta>"))
        assertNull(DocumentTitle.fromXmp("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Untitled</rdf:li></rdf:Alt></dc:title>"))
        assertNull(DocumentTitle.fromXmp("<dc:title><rdf:Alt></rdf:Alt></dc:title>"))
        assertNull(DocumentTitle.fromXmp("not xml at all"))
    }

    @Test
    fun `a source keeps null as its default title`() {
        val source =
            object : TextSource {
                override val pageCount = 0

                override fun readPage(pageIndex: Int): SourcePage = error("no pages")

                override fun close() = Unit
            }

        assertNull(source.title)
    }
}
