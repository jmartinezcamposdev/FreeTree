package com.jmartinezcampos.freetree.core.git

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class GitCommandType { READ, LOCAL_WRITE, NETWORK }

internal data class GitTimeouts(
    val readMillis: Long = 60_000,
    val localWriteMillis: Long = 120_000,
    val networkMillis: Long = 300_000,
) {
    fun forType(type: GitCommandType): Long = when (type) {
        GitCommandType.READ -> readMillis
        GitCommandType.LOCAL_WRITE -> localWriteMillis
        GitCommandType.NETWORK -> networkMillis
    }
}

internal data class GitProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut

    fun stdoutText(): String = stdout.toString(StandardCharsets.UTF_8)
}

internal class GitRunner(
    private val workTree: Path,
    private val executable: GitExecutable,
    private val timeouts: GitTimeouts = GitTimeouts(),
    private val baseEnvironment: Map<String, String> = emptyMap(),
) {
    suspend fun run(
        args: List<String>,
        type: GitCommandType = GitCommandType.READ,
        stdin: ByteArray? = null,
        maxStdoutBytes: Long = Long.MAX_VALUE,
        environment: Map<String, String> = emptyMap(),
    ): GitProcessResult = withContext(Dispatchers.IO) {
        execute(args, type, stdin, maxStdoutBytes, environment, onRecord = null)
    }

    suspend fun streamRecords(
        args: List<String>,
        type: GitCommandType = GitCommandType.READ,
        stdin: ByteArray? = null,
        environment: Map<String, String> = emptyMap(),
        onRecord: suspend (String) -> Unit,
    ): GitProcessResult = withContext(Dispatchers.IO) {
        execute(args, type, stdin, Long.MAX_VALUE, environment, onRecord)
    }

    private suspend fun execute(
        args: List<String>,
        type: GitCommandType,
        stdin: ByteArray?,
        maxStdoutBytes: Long,
        environment: Map<String, String>,
        onRecord: (suspend (String) -> Unit)?,
    ): GitProcessResult = coroutineScope {
        val command = listOf(
            executable.path.toString(),
            "--no-pager",
            "-c",
            "core.quotepath=off",
        ) + args
        val builder = ProcessBuilder(command).directory(workTree.toFile())
        baseEnvironment.forEach { (key, value) -> builder.environment()[key] = value }
        builder.environment().apply {
            putAll(environment)
            put("GIT_TERMINAL_PROMPT", "0")
            put("GCM_INTERACTIVE", "never")
            put("GIT_LITERAL_PATHSPECS", "1")
            if (type == GitCommandType.READ) put("GIT_OPTIONAL_LOCKS", "0")
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return@coroutineScope GitProcessResult(
                exitCode = -1,
                stdout = ByteArray(0),
                stderr = "Could not start Git (${executable.path}): ${e.message}",
            )
        }

        val killer = try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    terminate(process)
                }
            }
        } catch (e: CancellationException) {
            terminate(process)
            throw e
        }
        val timedOut = AtomicBoolean(false)
        val truncated = AtomicBoolean(false)
        val timeoutMillis = timeouts.forType(type)
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            delay(timeoutMillis)
            if (process.isAlive) {
                timedOut.set(true)
                terminate(process)
            }
        }

        try {
            var stdinFailure: IOException? = null
            try {
                process.outputStream.use { output ->
                    if (stdin != null) output.write(stdin)
                }
            } catch (e: IOException) {
                if (!isActive) throw CancellationException("Git command cancelled", e)
                stdinFailure = e
            }

            val stderrDeferred = async(Dispatchers.IO) {
                process.errorStream.readBytes()
            }

            val collected = if (onRecord == null) ByteArrayOutputStream() else null
            var stdoutFailure: IOException? = null
            try {
                readStdout(
                    input = process.inputStream,
                    maxStdoutBytes = maxStdoutBytes,
                    collected = collected,
                    onRecord = onRecord,
                    truncated = truncated,
                    terminate = { terminate(process) },
                )
            } catch (e: IOException) {
                if (!isActive) throw CancellationException("Git command cancelled", e)
                if (!timedOut.get() && !truncated.get()) stdoutFailure = e
            }

            if (!timedOut.get() && !truncated.get() && process.isAlive) {
                process.waitFor()
            }
            watchdog.cancel()

            val exitCode = if (timedOut.get()) -1 else {
                process.waitFor(5, TimeUnit.SECONDS)
                if (process.isAlive) {
                    terminate(process)
                    process.waitFor()
                }
                process.exitValue()
            }

            currentCoroutineContext().ensureActive()

            val rawStderr = stderrDeferred.await().toString(StandardCharsets.UTF_8)
            val stderr = when {
                timedOut.get() -> withNote(rawStderr, "Git command timed out after $timeoutMillis ms.")
                stdoutFailure != null -> withNote(rawStderr, "Could not read Git output: ${stdoutFailure.message}")
                stdinFailure != null && exitCode == 0 ->
                    withNote(rawStderr, "Could not write Git input: ${stdinFailure.message}")
                else -> rawStderr
            }
            GitProcessResult(
                exitCode = if ((stdoutFailure != null || stdinFailure != null) && exitCode == 0) -1 else exitCode,
                stdout = collected?.toByteArray() ?: ByteArray(0),
                stderr = stderr,
                truncated = truncated.get(),
                timedOut = timedOut.get(),
            )
        } finally {
            killer.cancel()
            watchdog.cancel()
            process.outputStream.closeQuietly()
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
            if (process.isAlive) terminate(process)
        }
    }

    private suspend fun readStdout(
        input: InputStream,
        maxStdoutBytes: Long,
        collected: ByteArrayOutputStream?,
        onRecord: (suspend (String) -> Unit)?,
        truncated: AtomicBoolean,
        terminate: () -> Unit,
    ) {
        val buffer = ByteArray(8192)
        val record = if (onRecord != null) ByteArrayOutputStream() else null
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue

            if (onRecord != null && record != null) {
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] == 0.toByte()) {
                        record.write(buffer, start, index - start)
                        onRecord(record.toByteArray().toString(StandardCharsets.UTF_8))
                        record.reset()
                        start = index + 1
                    }
                }
                if (start < count) record.write(buffer, start, count - start)
            } else {
                val remaining = maxStdoutBytes - total
                if (count.toLong() > remaining) {
                    if (remaining > 0) collected?.write(buffer, 0, remaining.toInt())
                    truncated.set(true)
                    terminate()
                    break
                }
                collected?.write(buffer, 0, count)
                total += count
            }
        }
        if (onRecord != null && record != null && record.size() > 0) {
            onRecord(record.toByteArray().toString(StandardCharsets.UTF_8))
        }
    }

    private fun terminate(process: Process) {
        process.toHandle().descendants().forEach { handle ->
            if (handle.isAlive) handle.destroyForcibly()
        }
        process.destroyForcibly()
        process.outputStream.closeQuietly()
        process.inputStream.closeQuietly()
        process.errorStream.closeQuietly()
    }

    private fun withNote(stderr: String, note: String): String = when {
        stderr.isBlank() -> note
        stderr.endsWith("\n") -> stderr + note
        else -> stderr + "\n" + note
    }
}

private fun java.io.Closeable?.closeQuietly() {
    try {
        this?.close()
    } catch (_: IOException) {
        // Closing a stream after its process was killed is best effort.
    }
}
