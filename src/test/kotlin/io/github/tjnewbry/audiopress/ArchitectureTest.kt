package io.github.tjnewbry.audiopress

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the package boundaries described in docs/architecture.md#packages. */
class ArchitectureTest {
    @Test
    fun `core does not depend on the PDF library, the CLI, or desktop APIs`() {
        val core = Path.of("src/main/kotlin/io/github/tjnewbry/audiopress/core")
        assertTrue(Files.isDirectory(core), "run tests from the project directory")

        val sources = Files.walk(core).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        val violations =
            sources.flatMap { file ->
                Files
                    .readAllLines(file)
                    .filter { line ->
                        FORBIDDEN.any { line.trim().startsWith("import $it") }
                    }.map { "${file.fileName}: $it" }
            }

        assertEquals(emptyList(), violations)
    }

    private companion object {
        val FORBIDDEN =
            listOf(
                "org.apache.pdfbox",
                "org.apache.fontbox",
                "com.github.ajalt",
                "java.awt",
                "io.github.tjnewbry.audiopress.pdfbox",
                "io.github.tjnewbry.audiopress.cli",
            )
    }
}
