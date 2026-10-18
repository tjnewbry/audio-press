package io.github.tjnewbry.audiopress.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import kotlin.system.exitProcess

/** Process exit codes. See `docs/usage.md#exit-codes`. */
object ExitCode {
    const val SUCCESS = 0
    const val INTERNAL_ERROR = 1
    const val USAGE_ERROR = 2
    const val INPUT_ERROR = 3
    const val NO_TEXT = 4
    const val OUTPUT_ERROR = 5
}

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList()))
}

/** Runs the command with [args] and returns the exit code, without exiting the process. */
fun runCli(args: List<String>): Int {
    val command = AudioPressCommand()
    return try {
        command.parse(args)
        ExitCode.SUCCESS
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        if (e is UsageError) ExitCode.USAGE_ERROR else e.statusCode
    }
}
