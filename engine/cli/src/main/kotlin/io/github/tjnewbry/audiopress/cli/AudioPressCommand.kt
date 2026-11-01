package io.github.tjnewbry.audiopress.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import io.github.tjnewbry.audiopress.core.AudioPressException
import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.ConversionResult
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.LayoutDump
import io.github.tjnewbry.audiopress.core.LineTarget
import io.github.tjnewbry.audiopress.core.LoTextWriter
import io.github.tjnewbry.audiopress.core.NoPagesSelectedException
import io.github.tjnewbry.audiopress.core.NoTextLayerException
import io.github.tjnewbry.audiopress.core.PageMarkers
import io.github.tjnewbry.audiopress.core.PageSelection
import io.github.tjnewbry.audiopress.core.PageTarget
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.ReportWriter
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.UrlMode
import io.github.tjnewbry.audiopress.core.WordTarget
import io.github.tjnewbry.audiopress.pdfbox.PdfBoxTextSource
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** The `audio-press` command. Options are specified in `docs/usage.md`. */
class AudioPressCommand : CoreCliktCommand(name = "audio-press") {
    init {
        versionOption(Version.current)
    }

    override fun help(context: Context): String = "Turn a PDF book into a clean transcript for text-to-speech."

    private val input by argument("INPUT", help = "The PDF file to convert.").path()

    private val output by option(
        "-o",
        "--output",
        metavar = "PATH",
        help = "Where to write the transcript (\"-\" for stdout). Default: INPUT with a .txt extension.",
    )

    private val pages by option(
        "--pages",
        metavar = "RANGES",
        help = "Only convert these physical pages, for example 9-310 or 1,5-7,20-.",
    ).convert { value ->
        try {
            PageSelection.parse(value)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid page range")
        }
    }.default(PageSelection.ALL, defaultForHelp = "all")

    private val pageMarkers by option(
        "--page-markers",
        help = "\"line\" writes a [[page N]] line where each page starts; \"none\" writes plain text.",
    ).choice("line" to PageMarkers.LINE, "none" to PageMarkers.NONE).default(PageMarkers.LINE, defaultForHelp = "line")

    private val urls by option(
        "--urls",
        help = "How web addresses are read: shortened to the domain, dropped, or kept as is.",
    ).choice("domain" to UrlMode.DOMAIN, "drop" to UrlMode.DROP, "keep" to UrlMode.KEEP)
        .default(UrlMode.DOMAIN, defaultForHelp = "domain")

    private val disabled by option(
        "--disable",
        metavar = "RULES",
        help = "Switch off cleanup rules (comma-separated, repeatable). Rules: ${RuleId.entries.joinToString { it.id }}.",
    ).convert { value ->
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { id ->
            RuleId.fromId(id) ?: fail("unknown rule \"$id\". Valid rules: ${RuleId.entries.joinToString { it.id }}")
        }
    }.multiple()

    private val report by option(
        "--report",
        metavar = "PATH",
        help = "Write a report of everything removed and every warning (\"-\" for stderr).",
    )

    private val quiet by option("-q", "--quiet", help = "Print errors only.").flag()

    private val verbose by option("-v", "--verbose", help = "Also print PDF library messages and error details.").flag()

    private val dumpLayout by option("--dump-layout", metavar = "PATH", hidden = true)

    override fun run() {
        configurePdfBoxLogging(verbose)
        val options =
            ConversionOptions(
                pages = pages,
                pageMarkers = pageMarkers,
                urls = urls,
                disabledRules = disabled.flatten().toSet(),
            )
        val target = output ?: defaultOutput(input).toString()
        if (target != "-" && Path.of(target).toAbsolutePath().normalize() == input.toAbsolutePath().normalize()) {
            throw UsageError("the output file would overwrite the input file; choose another with --output")
        }
        try {
            val result = PdfBoxTextSource.open(input).use { Converter().convert(it, input.toString(), options) }
            dumpLayout?.let { path ->
                OutputFiles.write(path, toStderr = false) { LayoutDump.write(result.document.pages, Removals.of(result.removals), it) }
            }
            OutputFiles.write(target, toStderr = false) { LoTextWriter(options.pageMarkers).write(result.transcript, it) }
            report?.let { path -> OutputFiles.write(path, toStderr = true) { ReportWriter.write(result, input.toString(), options, it) } }
            if (!quiet) printSummary(result, target)
        } catch (e: CliktError) {
            throw e
        } catch (e: NoPagesSelectedException) {
            throw UsageError(e.message ?: "--pages selects no pages")
        } catch (e: AudioPressException) {
            fail(e.message, if (e is NoTextLayerException) ExitCode.NO_TEXT else ExitCode.INPUT_ERROR)
        } catch (e: OutputException) {
            fail(e.message, ExitCode.OUTPUT_ERROR)
        } catch (e: Exception) {
            if (verbose) e.printStackTrace()
            fail("unexpected error: ${e.message ?: e.javaClass.name} (rerun with --verbose for details)", ExitCode.INTERNAL_ERROR)
        }
    }

    private fun printSummary(
        result: ConversionResult,
        target: String,
    ) {
        result.warnings.groupBy { it.message }.forEach { (message, warnings) ->
            val pageList = warnings.map { it.page }
            val label = if (pageList.size == 1) "page ${pageList.single()}" else "pages ${pageList.joinToString(", ")}"
            Messages.warning("$label: $message")
        }
        val removals = result.removals
        val where = if (target == "-") "the transcript to stdout" else target
        Messages.info(
            "Wrote $where: ${count(result.document.pages.size, "page")}, " +
                "${count(result.transcript.segments.size, "paragraph")}, ${count(result.transcript.wordCount, "word")}. " +
                "Removed ${count(removals.count { it.target is PageTarget }, "page")}, " +
                "${count(removals.count { it.target is LineTarget }, "line")}, and " +
                "${count(removals.count { it.target is WordTarget }, "word")} (use --report for details).",
        )
    }

    private fun fail(
        message: String?,
        exitCode: Int,
    ): Nothing {
        Messages.error(message ?: "unknown error")
        throw ProgramResult(exitCode)
    }

    private companion object {
        /** Kept in a field so the logger settings are not garbage collected. */
        val pdfBoxLoggers: List<Logger> = listOf("org.apache.pdfbox", "org.apache.fontbox").map(Logger::getLogger)

        fun configurePdfBoxLogging(verbose: Boolean) {
            pdfBoxLoggers.forEach { it.level = if (verbose) null else Level.OFF }
        }

        /** `book.pdf` becomes `book.txt` in the same directory. */
        fun defaultOutput(input: Path): Path {
            val name = input.fileName.toString()
            val base = if ('.' in name) name.substringBeforeLast('.') else name
            return input.resolveSibling("$base.txt")
        }

        fun count(
            value: Int,
            noun: String,
        ): String = String.format(Locale.US, "%,d %s", value, if (value == 1) noun else "${noun}s")
    }
}
