package io.github.abpubli.dotsupport.external

import io.github.abpubli.dotsupport.settings.DotSettings
import io.github.abpubli.dotsupport.settings.DotSettings.Companion.findDotExecutable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern


/**
 * Shared Regex pattern for parsing error/warnings lines from Graphviz (dot).
 * Defined here to avoid duplication (DRY rule).
 */
internal val GRAPHVIZ_ISSUE_PATTERN: Pattern = Pattern.compile(
    "^(Error|Warning):.*? (?:line|near line)\\s*(\\d+)(.*)", // Poprawiony wzorzec
    Pattern.CASE_INSENSITIVE
)

// data class to storing results of dot command
data class DotExecutionResult(
    val outputBytes: ByteArray?, // stdout results (for example picture)
    val errorOutput: String?,    // stderr results (errors, warnings)
    val exitCode: Int,           // exit code of dot process
    val timedOut: Boolean = false, // time limit exceeds?
    val executionError: Exception? = null // runtime error (for example fot not found)
) {
    // An auxiliary property that checks if any error (execution, timeout or bad output code) has occurred
    val hasFailed: Boolean
        get() = executionError != null || timedOut || exitCode != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DotExecutionResult

        if (exitCode != other.exitCode) return false
        if (timedOut != other.timedOut) return false
        if (!outputBytes.contentEquals(other.outputBytes)) return false
        if (errorOutput != other.errorOutput) return false
        if (executionError != other.executionError) return false
        if (hasFailed != other.hasFailed) return false

        return true
    }

    override fun hashCode(): Int {
        var result = exitCode
        result = 31 * result + timedOut.hashCode()
        result = 31 * result + (outputBytes?.contentHashCode() ?: 0)
        result = 31 * result + (errorOutput?.hashCode() ?: 0)
        result = 31 * result + (executionError?.hashCode() ?: 0)
        result = 31 * result + hasFailed.hashCode()
        return result
    }
}


/**
 * Executes the 'dot' command with the specified DOT code as input.
 * Supports stdin, stdout, stderr and timeout.
 *
 * @param dotSource String containing the definition of a graph in DOT language.
 * @param outputFormat Output format for 'dot' (e.g. “png”, “svg”, “canon”, “dot”).
 * @param timeoutSeconds Maximum waiting time for the 'dot' process to complete.
 * @return DotExecutionResult object containing results or error information.
 */
fun runDotCommand(
    dotSource: String,
    outputFormat: String,
    timeoutSeconds: Long = 10 // default timeout
): DotExecutionResult {
    var dotPath = DotSettings.getInstance().dotPath.trim()
    if (dotPath.isBlank()) {
        dotPath = findDotExecutable() ?: "";
        DotSettings.getInstance().dotPath = dotPath;
    }
    val command = listOf(dotPath, "-T$outputFormat")
    val processBuilder = ProcessBuilder(command)
    var process: Process? = null
    val executor = Executors.newFixedThreadPool(3) // Threads for stdin, stdout, stderr

    try {
        process = processBuilder.start()

        val stdin = process.outputStream
        val stdout = process.inputStream
        val stderr = process.errorStream

        // task writing to stdin
        val inputFuture = executor.submit {
            try {
                stdin.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(dotSource)
                }
            } catch (e: IOException) {
                // logging error (e.g. dot process ended unexpectedly)
                // logging can be helpful, but will mainly return an execution error
                throw IOException("Error writing to dot stdin: ${e.message}", e)
            }
        }

        // task reading stdout
        val outputFuture = executor.submit<ByteArray> {
            stdout.readBytes()
        }

        // task that reads stderr
        val errorFuture = executor.submit<String> {
            stderr.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        }

        var outputBytes: ByteArray? = null
        var errorOutput: String? = null
        var timedOut = false
        var exitCode = -1

        try {
            // waiting for I/O tasks to finish with timeout (can be customized)
            // important: waiting for inputFuture is less critical than for output/error
            inputFuture.get(timeoutSeconds, TimeUnit.SECONDS)
            outputBytes = outputFuture.get(timeoutSeconds, TimeUnit.SECONDS)
            errorOutput = errorFuture.get(timeoutSeconds, TimeUnit.SECONDS)

            // we are waiting for the completion of the dot process
            val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!exited) {
                timedOut = true
                process.destroyForcibly()
                throw TimeoutException("Process 'dot' timed out after ${timeoutSeconds}s")
            }
            exitCode = process.exitValue()

        } catch (e: TimeoutException) {
            timedOut = true
            // we are still trying to read what we can from output/error if a timeout occurred during waitFor
            if (outputBytes == null && outputFuture.isDone && !outputFuture.isCancelled) outputBytes =
                outputFuture.get()
            if (errorOutput == null && errorFuture.isDone && !errorFuture.isCancelled) errorOutput = errorFuture.get()
            // return the result with the flag timedOut = true
            exitCode = process?.exitValue()
                ?: -1 // try to retrieve the exit code if the process has managed to terminate despite the waitFor timeout
        } finally {
            // we make sure that the read/write threads have ended or have been interrupted
            inputFuture.cancel(true)
            outputFuture.cancel(true)
            errorFuture.cancel(true)
            executor.shutdownNow() // Zamykamy pulę wątków
        }

        return DotExecutionResult(outputBytes, errorOutput, exitCode, timedOut)

    } catch (e: IOException) {
        // e.g. 'dot' not found
        return DotExecutionResult(null, null, -1, executionError = e)
    } catch (e: Exception) { // other errors (e.g. with get() if the task threw an exception)
        return DotExecutionResult(null, null, process?.exitValue() ?: -1, executionError = e)
    } finally {
        process?.destroyForcibly() // additional assurance that the process is killed
        executor.shutdownNow()
    }
}