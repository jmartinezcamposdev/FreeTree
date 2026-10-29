package com.jmartinezcampos.freetree.core.bench

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.git.GitParsers
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

internal object BenchSink {
    @Volatile
    var value: Long = 0L
}

internal data class GraphLoadSample(
    val rows: Int,
    val firstVisibleMillis: Double,
    val fullMillis: Double,
    val snapshotMillis: Double,
)

internal data class ColdActivationSample(
    val rows: Int,
    val activationMillis: Double,
    val firstVisibleMillis: Double,
)

internal data class StatusBench(
    val raw: Stats,
    val backend: Stats,
    val parse: Stats,
    val outputBytes: Int,
    val entryCount: Int,
    val dirtyParse: Stats,
    val dirtyEntries: Int,
)

internal object GraphBench {
    const val FIRST_VISIBLE_ROWS = 2_000

    private const val LOG_FORMAT = "%H%x1f%P%x1f%an%x1f%at%x1f%s"

    val logSpec: LogSpec = LogSpec(revs = emptyList(), includeHead = true)

    private val logArgs = listOf(
        "log",
        "--stdin",
        "-z",
        "--topo-order",
        "--format=$LOG_FORMAT",
    )

    private val logStdin = "HEAD\n".toByteArray(StandardCharsets.UTF_8)

    suspend fun readCommits(backend: GitBackend): List<RawCommit> {
        val commits = ArrayList<RawCommit>(220_000)
        backend.logStream(logSpec).collect { commits += it }
        return commits
    }

    suspend fun measureStreamOnly(backend: GitBackend, warmups: Int, iterations: Int): Stats {
        repeat(warmups) { backend.logStream(logSpec).collect { BenchSink.value += it.parents.size } }
        val samples = List(iterations) {
            val started = System.nanoTime()
            backend.logStream(logSpec).collect { BenchSink.value += it.parents.size }
            elapsedMillis(started)
        }
        return stats(samples)
    }

    suspend fun measureGraphLoad(
        backend: GitBackend,
        warmups: Int,
        iterations: Int,
    ): List<GraphLoadSample> {
        repeat(warmups) { loadOnce(backend) }
        return List(iterations) { loadOnce(backend) }
    }

    private suspend fun loadOnce(backend: GitBackend): GraphLoadSample {
        val engine = LaneLayoutEngine()
        val started = System.nanoTime()
        var rows = 0
        var firstVisible = 0.0
        backend.logStream(logSpec).collect { commit ->
            engine.add(commit)
            rows++
            if (rows == FIRST_VISIBLE_ROWS) {
                engine.snapshot(complete = false)
                firstVisible = elapsedMillis(started)
            }
        }
        val snapshotStarted = System.nanoTime()
        engine.snapshot(complete = true)
        val snapshotMillis = elapsedMillis(snapshotStarted)
        val fullMillis = elapsedMillis(started)
        return GraphLoadSample(
            rows = rows,
            firstVisibleMillis = if (rows < FIRST_VISIBLE_ROWS) fullMillis else firstVisible,
            fullMillis = fullMillis,
            snapshotMillis = snapshotMillis,
        )
    }

    suspend fun measureColdActivation(
        path: Path,
        warmups: Int,
        iterations: Int,
    ): List<ColdActivationSample> {
        repeat(warmups) { coldActivationOnce(path) }
        return List(iterations) { coldActivationOnce(path) }
    }

    private suspend fun coldActivationOnce(path: Path): ColdActivationSample {
        val started = System.nanoTime()
        val backend = CliGitBackend.open(path)
        backend.gitDirs()
        coroutineScope {
            val refs = async { backend.refs() }
            val head = async { backend.head() }
            val status = async { backend.status() }
            refs.await()
            head.await()
            status.await()
        }
        val activationMillis = elapsedMillis(started)
        val engine = LaneLayoutEngine()
        var rows = 0
        var firstVisible = 0.0
        backend.logStream(logSpec).take(FIRST_VISIBLE_ROWS).collect { commit ->
            engine.add(commit)
            rows++
            if (rows == FIRST_VISIBLE_ROWS) {
                engine.snapshot(complete = false)
                firstVisible = elapsedMillis(started)
            }
        }
        return ColdActivationSample(
            rows = rows,
            activationMillis = activationMillis,
            firstVisibleMillis = if (rows < FIRST_VISIBLE_ROWS) elapsedMillis(started) else firstVisible,
        )
    }

    fun rawLogStats(repo: Path, warmups: Int, iterations: Int): Pair<Stats, Int> {
        fun run(): RawGitStreamOutput = RawGit.runDiscarding(repo, logArgs, logStdin)
        repeat(warmups) {
            val output = run()
            check(output.succeeded) { "raw git log failed: ${output.stderr}" }
        }
        var bytes = 0
        val samples = List(iterations) {
            val started = System.nanoTime()
            val output = run()
            val elapsed = elapsedMillis(started)
            check(output.succeeded) { "raw git log failed: ${output.stderr}" }
            bytes = output.bytes
            BenchSink.value += output.bytes
            elapsed
        }
        return stats(samples) to bytes
    }

    fun rawLogFirstRows(repo: Path, records: Int, warmups: Int, iterations: Int): Stats {
        repeat(warmups) { RawGit.firstRecords(repo, logArgs, logStdin, records) }
        val samples = List(iterations) {
            RawGit.firstRecords(repo, logArgs, logStdin, records)
        }
        return stats(samples)
    }

    fun layoutThroughput(commits: List<RawCommit>, warmups: Int, iterations: Int): Stats {
        repeat(warmups) { layout(commits) }
        val samples = layoutMillis(commits, iterations)
        return stats(samples)
    }
}

internal object StatusBenchRunner {
    private val statusArgs = listOf(
        "status",
        "--porcelain=v2",
        "-z",
        "--branch",
        "--untracked-files=all",
    )

    suspend fun measure(
        backend: GitBackend,
        repo: Path,
        warmups: Int,
        iterations: Int,
    ): StatusBench {
        repeat(warmups) {
            RawGit.run(repo, statusArgs).also { check(it.succeeded) { "raw git status failed: ${it.stderr}" } }
            backend.status()
        }
        val rawSamples = List(iterations) {
            val started = System.nanoTime()
            val output = RawGit.run(repo, statusArgs)
            val elapsed = elapsedMillis(started)
            check(output.succeeded) { "raw git status failed: ${output.stderr}" }
            BenchSink.value += output.stdout.size
            elapsed
        }
        val backendSamples = List(iterations) {
            measureMillis { runBlocking { BenchSink.value += backend.status().entries.size } }
        }
        val captured = RawGit.run(repo, statusArgs)
        check(captured.succeeded) { "raw git status failed: ${captured.stderr}" }
        val statusText = captured.stdoutText()
        val parsed = GitParsers.parseStatus(statusText)
        val parseSamples = List(iterations) {
            measureMillis { BenchSink.value += GitParsers.parseStatus(statusText).entries.size }
        }
        val dirtyOutput = dirtyStatusOutput(DIRTY_ENTRIES)
        val dirtySamples = List(iterations) {
            measureMillis { BenchSink.value += GitParsers.parseStatus(dirtyOutput).entries.size }
        }
        return StatusBench(
            raw = stats(rawSamples),
            backend = stats(backendSamples),
            parse = stats(parseSamples),
            outputBytes = captured.stdout.size,
            entryCount = parsed.entries.size,
            dirtyParse = stats(dirtySamples),
            dirtyEntries = DIRTY_ENTRIES,
        )
    }

    private fun dirtyStatusOutput(entries: Int): String {
        val builder = StringBuilder(entries * 120)
        builder.append("# branch.oid ").append("0".repeat(40)).append('\u0000')
        builder.append("# branch.head main").append('\u0000')
        for (index in 0 until entries) {
            val objectId = index.toString(16).padStart(40, '0')
            builder.append("1 .M N... 100644 100644 100644 ")
                .append(objectId)
                .append(' ')
                .append(objectId)
                .append(" src/main/kotlin/file")
                .append(index)
                .append(".kt")
                .append('\u0000')
        }
        return builder.toString()
    }

    private const val DIRTY_ENTRIES = 20_000
}

internal object SnapshotBench {
    fun fastScroll(
        snapshot: GraphSnapshot,
        viewport: Int,
        jumps: Int,
        labels: Map<String, List<*>> = emptyMap(),
        warmups: Int = 100,
    ): Stats {
        val random = Random(20_261_029)
        fun frame(): Long {
            val maxStart = (snapshot.rowCount - viewport).coerceAtLeast(1)
            val start = random.nextInt(maxStart)
            var checksum = 0L
            for (offset in 0 until viewport) {
                val row = start + offset
                if (row >= snapshot.rowCount) break
                val hash = snapshot.hash(row)
                checksum += hash.length
                checksum += snapshot.subject(row).length
                checksum += snapshot.author(row).length
                checksum += snapshot.timeSec(row)
                checksum += snapshot.parentCount(row)
                checksum += snapshot.nodeLane(row)
                checksum += snapshot.nodeColor(row)
                checksum += labels[hash]?.size ?: 0
                val segments = snapshot.segments(row)
                for (index in 0 until segments.size) {
                    val segment = segments[index]
                    checksum += segment.from + segment.to + segment.color + segment.kind.ordinal
                }
            }
            return checksum
        }
        repeat(warmups) { BenchSink.value += frame() }
        val samples = List(jumps) { measureMillis { BenchSink.value += frame() } }
        return stats(samples)
    }

    fun frozenSnapshotAccess(
        snapshot: GraphSnapshot,
        viewport: Int,
        iterations: Int,
        warmups: Int = 100,
    ): Stats {
        fun frame(): Long {
            val frozen = snapshot
            var checksum = frozen.rowCount.toLong() + frozen.maxLanes
            val start = ((frozen.rowCount / 2) - viewport / 2)
                .coerceIn(0, (frozen.rowCount - viewport).coerceAtLeast(0))
            for (offset in 0 until viewport) {
                val row = start + offset
                if (row >= frozen.rowCount) break
                checksum += frozen.hash(row).length
                checksum += frozen.subject(row).length
                checksum += frozen.author(row).length
                checksum += frozen.timeSec(row)
                checksum += frozen.parentCount(row)
                checksum += frozen.nodeLane(row)
                checksum += frozen.nodeColor(row)
                val segments = frozen.segments(row)
                for (index in 0 until segments.size) {
                    val segment = segments[index]
                    checksum += segment.from + segment.to + segment.color + segment.kind.ordinal
                }
            }
            return checksum
        }
        repeat(warmups) { BenchSink.value += frame() }
        val samples = List(iterations) { measureMillis { BenchSink.value += frame() } }
        return stats(samples)
    }

    fun indexOfHashBuild(snapshot: GraphSnapshot): Pair<Double, Stats> {
        val target = snapshot.hash(snapshot.rowCount / 2)
        val firstCall = measureMillis { BenchSink.value += snapshot.indexOfHash(target) }
        val samples = List(1_000) {
            measureMillis { BenchSink.value += snapshot.indexOfHash(target) }
        }
        return firstCall to stats(samples)
    }
}
