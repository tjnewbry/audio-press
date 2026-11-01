package io.github.tjnewbry.audiopress.core

/**
 * Cleans up a title read from a document's metadata, for a [TextSource.title]. PDF titles are often left behind by
 * the program that made the file ("Microsoft Word - notes.docx", "Untitled"); those give null, so the app can fall
 * back to the file name.
 */
object DocumentTitle {
    /** Titles that tell the reader nothing, compared in lower case. */
    private val PLACEHOLDERS =
        setOf("untitled", "untitled document", "title", "document", "unknown", "none", "null", "(anonymous)", "(untitled)")

    /** A title ending in one of these is a file name. */
    private val FILE_EXTENSIONS =
        listOf(
            ".doc",
            ".docx",
            ".dot",
            ".pdf",
            ".ppt",
            ".pptx",
            ".xls",
            ".xlsx",
            ".odt",
            ".rtf",
            ".txt",
            ".tex",
            ".indd",
            ".qxd",
            ".pages",
            ".wps",
        )

    /** Programs that put "Name - file" in the title, such as "Microsoft Word - notes.docx". */
    private val PROGRAM_PREFIXES = listOf("microsoft word - ", "microsoft powerpoint - ", "microsoft excel - ", "microsoft publisher - ")

    /** The cleaned [raw] title, or null when it is missing, blank, or junk. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val title =
            raw
                .filter { !it.isISOControl() || it.isWhitespace() }
                .split(' ', '\t', '\n', '\r')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .trim(Char(0xFEFF), ' ')
        if (title.isEmpty()) return null
        val lower = title.lowercase()
        if (lower in PLACEHOLDERS) return null
        if (isUntitledCopy(lower)) return null
        if (PROGRAM_PREFIXES.any { lower.startsWith(it) }) return null
        if (FILE_EXTENSIONS.any { lower.endsWith(it) }) return null
        return title
    }

    /** "Untitled-1", "Untitled 2", "Untitled (3)". */
    private fun isUntitledCopy(lower: String): Boolean =
        lower.startsWith("untitled") && lower.drop("untitled".length).all { it.isDigit() || it in " -_()" }

    /**
     * The `dc:title` of an XMP metadata packet, cleaned, or null. This reads the first `rdf:li` inside `dc:title`
     * (the default language) by plain text search, which is enough for the packets PDF programs write.
     */
    fun fromXmp(xmp: String): String? {
        val open = xmp.indexOf("<dc:title")
        if (open < 0) return null
        val close = xmp.indexOf("</dc:title>", open)
        if (close < 0) return null
        val block = xmp.substring(open, close)
        val item = block.indexOf("<rdf:li")
        if (item < 0) return null
        val start = block.indexOf('>', item) + 1
        val end = block.indexOf("</rdf:li>", start)
        if (start <= 0 || end < 0) return null
        return clean(unescape(block.substring(start, end)))
    }

    private fun unescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
