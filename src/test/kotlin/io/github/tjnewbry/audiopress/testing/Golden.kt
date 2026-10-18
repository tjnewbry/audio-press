package io.github.tjnewbry.audiopress.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares output with a golden file in `src/test/resources/golden/`. Running the tests with
 * `-Pgolden.update=true` rewrites the golden files instead; review the diff before keeping them.
 */
object Golden {
    private val directory: Path = Path.of(System.getProperty("golden.dir") ?: "src/test/resources/golden")
    private val update: Boolean = System.getProperty("golden.update") == "true"

    fun assertMatches(
        name: String,
        actual: String,
    ) {
        val file = directory.resolve(name)
        if (update) {
            Files.createDirectories(directory)
            Files.writeString(file, actual)
            return
        }
        assertTrue(Files.exists(file), "missing golden file $file; run the tests with -Pgolden.update=true to create it")
        assertEquals(Files.readString(file), actual, "output differs from golden file $name")
    }
}
