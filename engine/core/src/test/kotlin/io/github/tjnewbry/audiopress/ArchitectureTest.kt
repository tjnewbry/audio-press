package io.github.tjnewbry.audiopress

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards what the module boundary cannot: the core module must not use desktop-only APIs, so it stays
 * usable from Android. (Gradle already stops it importing PDFBox, Clikt, or the other modules.)
 */
class ArchitectureTest {
    @Test
    fun `core does not use desktop-only APIs`() {
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
        val FORBIDDEN = listOf("java.awt", "javax.swing", "javax.imageio")
    }
}
