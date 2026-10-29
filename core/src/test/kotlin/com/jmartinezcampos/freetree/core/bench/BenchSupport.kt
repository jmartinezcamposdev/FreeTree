package com.jmartinezcampos.freetree.core.bench

import com.jmartinezcampos.freetree.core.git.GitDiscovery
import com.jmartinezcampos.freetree.core.git.GitExecutable
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.model.RawCommit
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class BenchOptions(
    val perfRepoRoot: Path,
    val repoCommitCount: Int,
    val syntheticCommitCount: Int,
    val iterations: Int,
) {
    companion object {
        fun fromSystemProperties(): BenchOptions = BenchOptions(
            perfRepoRoot = Path.of(System.getProperty("freetree.bench.perfRepo") ?: "build/perf-repo"),
            repoCommitCount = System.getProperty("freetree.bench.repoCommits")?.toIntOrNull() ?: 200_000,
            syntheticCommitCount = System.getProperty("freetree.bench.syntheticCommits")?.toIntOrNull() ?: 50_000,
            iterations = System.getProperty("freetree.bench.iterations")?.toIntOrNull()?.coerceAtLeast(1) ?: 3,
        )
    }
}

internal data class RetainedMemory(val snapshot: GraphSnapshot, val bytes: Long) {
    fun bytesPerCommit(rowCount: Int): Double = bytes.toDouble() / rowCount
}

internal object BenchHeap {
    fun usedBytes(): Long {
        repeat(4) {
            System.gc()
            Thread.sleep(50)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * Measures the heap retained by [builder]'s snapshot. Everything the builder creates besides the
     * snapshot becomes garbage before the second reading, so the delta includes the strings the
     * snapshot keeps alive. Explicit GC makes this approximate, not exact.
     */
    fun measureRetained(builder: () -> GraphSnapshot): RetainedMemory {
        val before = usedBytes()
        val snapshot = builder()
        val after = usedBytes()
        return RetainedMemory(snapshot, (after - before).coerceAtLeast(0))
    }
}

internal fun layout(commits: List<RawCommit>, chunkSize: Int = 4096): GraphSnapshot {
    val engine = LaneLayoutEngine(chunkSize = chunkSize)
    for (commit in commits) engine.add(commit)
    return engine.snapshot(complete = true)
}

internal fun layoutMillis(commits: List<RawCommit>, iterations: Int): List<Double> =
    List(iterations) { measureMillis { layout(commits) } }

internal fun rowsPerSecond(rows: Int, millis: Double): Double = rows / (millis / 1_000.0)

internal data class RawGitOutput(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0

    fun stdoutText(): String = String(stdout, StandardCharsets.UTF_8)
}

internal data class RawGitStreamOutput(val exitCode: Int, val bytes: Int, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/** Runs the same Git process shape as `GitRunner`, without parsing, to establish a baseline. */
internal object RawGit {
    val executable: GitExecutable by lazy { GitDiscovery.discover() }

    fun run(workTree: Path, args: List<String>, stdin: ByteArray? = null): RawGitOutput {
        val process = start(workTree, args)
        val stderr = drain(process.errorStream)
        if (stdin != null) {
            process.outputStream.use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }
        val stdout = process.inputStream.readBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw IllegalStateException("raw git timed out: ${args.joinToString(" ")}")
        }
        return RawGitOutput(
            exitCode = process.exitValue(),
            stdout = stdout,
            stderr = String(stderr.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8),
        )
    }

    /** Consumes stdout through a fixed buffer, like `GitRunner`, without retaining the output. */
    fun runDiscarding(workTree: Path, args: List<String>, stdin: ByteArray? = null): RawGitStreamOutput {
        val process = start(workTree, args)
        val stderr = drain(process.errorStream)
        if (stdin != null) {
            process.outputStream.use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }
        val buffer = ByteArray(1 shl 16)
        var bytes = 0
        while (true) {
            val count = process.inputStream.read(buffer)
            if (count < 0) break
            bytes += count
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw IllegalStateException("raw git timed out: ${args.joinToString(" ")}")
        }
        return RawGitStreamOutput(
            exitCode = process.exitValue(),
            bytes = bytes,
            stderr = String(stderr.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8),
        )
    }

    /** Time until Git has emitted [recordCount] NUL-terminated records, then kills the process. */
    fun firstRecords(
        workTree: Path,
        args: List<String>,
        stdin: ByteArray?,
        recordCount: Int,
    ): Double {
        val process = start(workTree, args)
        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin) }
            } else {
                process.outputStream.close()
            }
            val started = System.nanoTime()
            val buffer = ByteArray(1 shl 16)
            var records = 0
            while (records < recordCount) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    if (buffer[index] == 0.toByte()) {
                        records++
                        if (records == recordCount) return elapsedMillis(started)
                    }
                }
            }
            return elapsedMillis(started)
        } finally {
            process.destroyForcibly()
            process.waitFor()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    private fun start(workTree: Path, args: List<String>): Process {
        val command = listOf(
            executable.path.toString(),
            "--no-pager",
            "-c",
            "core.quotepath=off",
        ) + args
        val builder = ProcessBuilder(command).directory(workTree.toFile())
        val environment = builder.environment()
        REPOSITORY_ENVIRONMENT_VARIABLES.forEach(environment::remove)
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["GIT_OPTIONAL_LOCKS"] = "0"
        environment["GIT_LITERAL_PATHSPECS"] = "1"
        environment["GIT_PAGER"] = "cat"
        environment["PAGER"] = "cat"
        return builder.start()
    }

    private fun drain(stream: java.io.InputStream): CompletableFuture<ByteArray> {
        val result = CompletableFuture<ByteArray>()
        Thread {
            try {
                result.complete(stream.readAllBytes())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }.apply {
            isDaemon = true
            name = "freetree-bench-git-stderr"
            start()
        }
        return result
    }

    private val REPOSITORY_ENVIRONMENT_VARIABLES = listOf(
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_COMMON_DIR",
        "GIT_INDEX_FILE",
        "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_NAMESPACE",
        "GIT_CEILING_DIRECTORIES",
    )
}
