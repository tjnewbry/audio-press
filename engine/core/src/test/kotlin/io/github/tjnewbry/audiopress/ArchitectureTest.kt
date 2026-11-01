package io.github.tjnewbry.audiopress

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards what the module boundary cannot: the core module must not use desktop-only APIs, so it stays
 * usable from Android. (Gradle already stops it importing PDFBox, Clikt, or the other modules.)
 * See "Android compatibility" in `docs/api.md`.
 */
class ArchitectureTest {
    @Test
    fun `core does not use desktop-only APIs`() {
        val violations =
            codeLines().filter { (_, line) -> FORBIDDEN.any { line.startsWith("import $it") } }
        assertEquals(emptyList(), violations.map { "${it.first}: ${it.second}" })
    }

    @Test
    fun `core uses only Kotlin, its own package, and the JDK classes allowed for Android`() {
        val violations =
            codeLines().filter { (_, line) ->
                val imported = line.removePrefix("import ").takeIf { line.startsWith("import ") }
                val qualified = JDK_PACKAGE.containsMatchIn(line)
                (imported != null && ALLOWED_IMPORTS.none { imported.startsWith(it) }) ||
                    (imported == null && qualified)
            }
        assertEquals(
            emptyList(),
            violations.map { "${it.first}: ${it.second}" },
            "Add an import to ALLOWED_IMPORTS only after checking the class on Android API 33.",
        )
    }

    @Test
    fun `core regexes avoid syntax that differs on Android`() {
        // Android's regex engine is ICU, not java.util.regex. Only lines that hold a raw-string pattern are checked.
        val violations =
            codeLines()
                .filter { (_, line) -> line.contains("\"\"\"") }
                .flatMap { (file, line) ->
                    RISKY_REGEX.filter { (_, pattern) -> pattern.containsMatchIn(line) }.map { (why, _) -> "$file: $why: $line" }
                }
        assertEquals(emptyList(), violations)
    }

    /** Every non-comment line of core's main sources, as (file name, trimmed line). */
    private fun codeLines(): List<Pair<String, String>> {
        val core = Path.of("src/main/kotlin/io/github/tjnewbry/audiopress/core")
        assertTrue(Files.isDirectory(core), "run tests from the project directory")
        val sources = Files.walk(core).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        return sources.flatMap { file ->
            Files
                .readAllLines(file)
                .map { it.trim() }
                .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                .map { file.fileName.toString() to it }
        }
    }

    private companion object {
        val FORBIDDEN = listOf("java.awt", "javax.swing", "javax.imageio")

        /** The only imports core may use. */
        val ALLOWED_IMPORTS = listOf("io.github.tjnewbry.audiopress.core.", "kotlin.", "java.util.Locale")

        /** A fully qualified JDK name in code, which would bypass the import check. */
        val JDK_PACKAGE = Regex("""(^|[^\w.])(java|javax|android)\.[a-z]""")

        val RISKY_REGEX =
            mapOf(
                "use [0-9] (ICU's \\d matches every Unicode digit)" to Regex("""\\d"""),
                "\\w and \\b differ in ICU" to Regex("""\\[wWbB]"""),
                "\\p{Is...} and \\p{java...} classes are not portable" to Regex("""\\[pP]\{(Is|java|In)"""),
                "\\R, \\X, \\h, and \\N{...} are not portable" to Regex("""\\[RXhHN]"""),
                "possessive quantifiers differ" to Regex("""[*+?]\+"""),
                "named groups differ" to Regex("""\(\?<[A-Za-z]"""),
            )
    }
}
