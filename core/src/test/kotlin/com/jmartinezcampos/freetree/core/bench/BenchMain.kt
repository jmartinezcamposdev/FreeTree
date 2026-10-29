package com.jmartinezcampos.freetree.core.bench

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.testing.PerfRepo
import com.jmartinezcampos.freetree.core.testing.PerfRepoBuilder
import com.jmartinezcampos.freetree.core.testing.SyntheticDag
import com.jmartinezcampos.freetree.core.testing.SyntheticDagGenerator
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.runBlocking

fun main() {
    val options = BenchOptions.fromSystemProperties()
    printHeader(options)
    val repo = buildRepo(options)
    if (CommitGraph.isPresent(repo.path)) {
        CommitGraph.remove(repo.path)
        println("Removed a cached commit-graph so the canonical numbers are measured without one.")
    }
    val backend = runBlocking { CliGitBackend.open(repo.path) }

    val synthetic = SyntheticDagGenerator(commitCount = options.syntheticCommitCount).generate()
    println()
    println("Synthetic DAG: ${synthetic.commits.size} commits, ${synthetic.refs.size} refs")

    val layoutWarmups = 2
    val layoutIterations = maxOf(options.iterations, 5)
    val syntheticLayout = GraphBench.layoutThroughput(
        synthetic.commits,
        warmups = layoutWarmups,
        iterations = layoutIterations,
    )
    val syntheticMemory = BenchHeap.measureRetained {
        layout(SyntheticDagGenerator(commitCount = options.syntheticCommitCount).generate().commits)
    }
    val syntheticScroll = SnapshotBench.fastScroll(
        snapshot = syntheticMemory.snapshot,
        viewport = VIEWPORT_ROWS,
        jumps = SCROLL_JUMPS,
    )
    val syntheticFirstRows = measureTwoThousandRows(synthetic)

    val realCommits = runBlocking { GraphBench.readCommits(backend) }
    val realLayout = GraphBench.layoutThroughput(
        realCommits,
        warmups = layoutWarmups,
        iterations = layoutIterations,
    )
    val realMemory = BenchHeap.measureRetained { buildSnapshotFromBackend(repo.path) }

    val rawLog = GraphBench.rawLogStats(repo.path, warmups = 1, iterations = options.iterations)
    val rawFirstRows = GraphBench.rawLogFirstRows(
        repo.path,
        records = GraphBench.FIRST_VISIBLE_ROWS,
        warmups = 1,
        iterations = options.iterations,
    )
    val streamOnly = runBlocking {
        GraphBench.measureStreamOnly(backend, warmups = 1, iterations = options.iterations)
    }
    val loads = runBlocking {
        GraphBench.measureGraphLoad(backend, warmups = 1, iterations = options.iterations)
    }
    val cold = runBlocking {
        GraphBench.measureColdActivation(repo.path, warmups = 1, iterations = options.iterations)
    }
    val status = runBlocking {
        StatusBenchRunner.measure(backend, repo.path, warmups = 1, iterations = options.iterations)
    }
    val commitGraph = measureCommitGraph(repo.path, backend, options.iterations)

    val realScroll = SnapshotBench.fastScroll(
        snapshot = realMemory.snapshot,
        viewport = VIEWPORT_ROWS,
        jumps = SCROLL_JUMPS,
    )
    val frozenAccess = SnapshotBench.frozenSnapshotAccess(
        snapshot = realMemory.snapshot,
        viewport = VIEWPORT_ROWS,
        iterations = 500,
    )
    val indexBefore = BenchHeap.usedBytes()
    val indexOfHash = SnapshotBench.indexOfHashBuild(realMemory.snapshot)
    val indexAfter = BenchHeap.usedBytes()
    val indexBytes = (indexAfter - indexBefore).coerceAtLeast(0)

    printGraphSection(
        repo = repo,
        rawLog = rawLog,
        rawFirstRows = rawFirstRows,
        streamOnly = streamOnly,
        syntheticRows = synthetic.commits.size,
        syntheticLayout = syntheticLayout,
        realLayout = realLayout,
        loads = loads,
        cold = cold,
        syntheticFirstRows = syntheticFirstRows,
    )
    printMemorySection(syntheticMemory, realMemory, indexBytes)
    printAccessSection(syntheticScroll, realScroll, frozenAccess, indexOfHash)
    printStatusSection(status)
    printCommitGraphSection(rawFirstRows, rawLog.first, cold, loads, commitGraph)
    printBudgetSummary(
        repo = repo,
        rawLog = rawLog.first,
        rawFirstRows = rawFirstRows,
        loads = loads,
        cold = cold,
        syntheticRowsPerSecond = rowsPerSecond(
            synthetic.commits.size,
            syntheticLayout.median,
        ),
        realRowsPerSecond = rowsPerSecond(realCommits.size, realLayout.median),
        syntheticMemory = syntheticMemory,
        realMemory = realMemory,
        realIndexBytes = indexBytes,
        realScroll = realScroll,
        frozenAccess = frozenAccess,
        status = status,
        commitGraph = commitGraph,
    )
}

private fun printHeader(options: BenchOptions) {
    println("FreeTree Phase 4 performance benchmark")
    println("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
    println("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println("Git: ${RawGit.executable.path} ${RawGit.executable.version}")
    println("CPUs: ${Runtime.getRuntime().availableProcessors()}")
    println("Max heap: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
    println("Options: $options")
}

private fun buildRepo(options: BenchOptions): PerfRepo {
    println()
    println("Preparing ${options.repoCommitCount} commit perf repository at ${options.perfRepoRoot} (built once, then cached)")
    val started = System.nanoTime()
    val repo = PerfRepoBuilder(
        root = options.perfRepoRoot,
        commitCount = options.repoCommitCount,
    ).buildIfNeeded()
    println(
        "Repo ready: ${repo.path} commits=${repo.commitCount} branches=${repo.branchCount} " +
            "tags=${repo.tagCount} cached=${repo.fromCache} " +
            "build=${repo.buildMillis} ms prepare=${elapsedMillis(started).toLong()} ms",
    )
    return repo
}

private fun measureTwoThousandRows(dag: SyntheticDag): Double {
    val engine = LaneLayoutEngine()
    val started = System.nanoTime()
    var rows = 0
    for (commit in dag.commits) {
        engine.add(commit)
        rows++
        if (rows == GraphBench.FIRST_VISIBLE_ROWS) {
            engine.snapshot(complete = false)
            return elapsedMillis(started)
        }
    }
    return elapsedMillis(started)
}

private fun buildSnapshotFromBackend(path: Path): GraphSnapshot = runBlocking {
    val backend = CliGitBackend.open(path)
    val engine = LaneLayoutEngine()
    backend.logStream(GraphBench.logSpec).collect { engine.add(it) }
    engine.snapshot(complete = true)
}

private fun printGraphSection(
    repo: PerfRepo,
    rawLog: Pair<Stats, Int>,
    rawFirstRows: Stats,
    streamOnly: Stats,
    syntheticRows: Int,
    syntheticLayout: Stats,
    realLayout: Stats,
    loads: List<GraphLoadSample>,
    cold: List<ColdActivationSample>,
    syntheticFirstRows: Double,
) {
    val firstVisible = stats(loads.map { it.firstVisibleMillis })
    val full = stats(loads.map { it.fullMillis })
    val snapshot = stats(loads.map { it.snapshotMillis })
    val coldFirst = stats(cold.map { it.firstVisibleMillis })
    val coldActivation = stats(cold.map { it.activationMillis })
    val rows = loads.first().rows
    val overhead = ((full.median - rawLog.first.median) / rawLog.first.median) * 100.0

    println()
    println("-- Graph build --")
    println("Raw git log:                ${rawLog.first.format()} (min ${formatMillis(rawLog.first.min)}, max ${formatMillis(rawLog.first.max)}, ${rawLog.second / (1024 * 1024)} MB)")
    println("Raw git log first ${GraphBench.FIRST_VISIBLE_ROWS} rows: ${rawFirstRows.format()}")
    println("Backend stream + parse:     ${streamOnly.format()}")
    println("Layout only synthetic:      ${syntheticLayout.format()} -> ${formatRate(rowsPerSecond(syntheticRows, syntheticLayout.median))}")
    println("Layout only repo:           ${realLayout.format()} -> ${formatRate(rowsPerSecond(rows, realLayout.median))}")
    println("Full graph (stream+layout): ${full.format()} (n=${full.count}, rows=$rows)")
    println("Final snapshot:             ${snapshot.format()}")
    println("Overhead over raw git log:  ${"%+.1f".format(Locale.ROOT, overhead)}%")
    println("First ${GraphBench.FIRST_VISIBLE_ROWS} rows, warm backend: ${firstVisible.format()}")
    println("First ${GraphBench.FIRST_VISIBLE_ROWS} rows, cold activation: ${coldFirst.format()} (activation ${coldActivation.format()})")
    println("Synthetic first ${GraphBench.FIRST_VISIBLE_ROWS} rows: ${formatMillis(syntheticFirstRows)}")
    println("Method: cold = open backend + refs/HEAD/status + log through ${GraphBench.FIRST_VISIBLE_ROWS} rows and one partial snapshot; Compose composition/rendering excluded.")
    println("Repo: ${repo.path} (${repo.commitCount} commits)")
}

private fun printMemorySection(synthetic: RetainedMemory, real: RetainedMemory, indexBytes: Long) {
    val indexPerCommit = indexBytes.toDouble() / real.snapshot.rowCount
    val totalPerCommit = real.bytesPerCommit(real.snapshot.rowCount) + indexPerCommit
    val syntheticShape = shape(synthetic.snapshot)
    val realShape = shape(real.snapshot)
    println()
    println("-- Graph memory (approximate retained heap delta after explicit GC) --")
    println("50k snapshot:  ${synthetic.bytes / (1024 * 1024)} MB, ${"%.1f".format(Locale.ROOT, synthetic.bytesPerCommit(synthetic.snapshot.rowCount))} bytes/commit; maxLanes=${syntheticShape.first}, segments/row=${"%.2f".format(Locale.ROOT, syntheticShape.second)}")
    println("200k snapshot: ${real.bytes / (1024 * 1024)} MB, ${"%.1f".format(Locale.ROOT, real.bytesPerCommit(real.snapshot.rowCount))} bytes/commit; maxLanes=${realShape.first}, segments/row=${"%.2f".format(Locale.ROOT, realShape.second)}")
    println("200k hash index (lazy, built on first indexOfHash): ${indexBytes / (1024 * 1024)} MB, ${"%.1f".format(Locale.ROOT, indexPerCommit)} bytes/commit; total ${"%.1f".format(Locale.ROOT, totalPerCommit)} bytes/commit")
    println("Method: snapshot built from fresh commits, commits/engine discarded, heap read before and after; includes retained strings.")
}

private fun shape(snapshot: GraphSnapshot): Pair<Int, Double> {
    var segments = 0L
    for (row in 0 until snapshot.rowCount) segments += snapshot.segments(row).size
    return snapshot.maxLanes to segments.toDouble() / snapshot.rowCount.coerceAtLeast(1)
}

private fun printAccessSection(
    syntheticScroll: Stats,
    realScroll: Stats,
    frozenAccess: Stats,
    indexOfHash: Pair<Double, Stats>,
) {
    println()
    println("-- Snapshot access (core-side data path; excludes Compose/text layout) --")
    println("Fast scroll 50k ($VIEWPORT_ROWS rows/frame):  median ${syntheticScroll.format()}, p99 ${formatMillis(syntheticScroll.p99)}, max ${formatMillis(syntheticScroll.max)}")
    println("Fast scroll 200k ($VIEWPORT_ROWS rows/frame): median ${realScroll.format()}, p99 ${formatMillis(realScroll.p99)}, max ${formatMillis(realScroll.max)}")
    println("Frozen snapshot 200k first frame:         median ${frozenAccess.format()}, p99 ${formatMillis(frozenAccess.p99)}, max ${formatMillis(frozenAccess.max)}")
    println("indexOfHash first call (builds map):      ${formatMillis(indexOfHash.first)}; later lookups median ${indexOfHash.second.format()}")
    println("Method: viewport reads (hash/subject/author/time/parents/lane/color/all segments) from random starts and the frozen-snapshot reference handoff.")
    println("Limitation: excludes Compose recomposition, text layout, and drawing; tab activation/session I/O is session-owned and not included.")
}

private fun printStatusSection(status: StatusBench) {
    val overhead = status.backend.median - status.raw.median
    println()
    println("-- Status refresh --")
    println("Raw git status:      ${status.raw.format()} (${status.outputBytes} bytes, ${status.entryCount} entries)")
    println("CliGitBackend.status: ${status.backend.format()}")
    println("Backend overhead:    ${formatMillis(overhead)}")
    println("Parse captured:      ${status.parse.format()}")
    println("Parse ${status.dirtyEntries} dirty entries: ${status.dirtyParse.format()}")
    println("Method: same porcelain-v2 command and environment; overhead = backend (process+parse) - raw (process+read). Clean fixture tree.")
}

internal data class CommitGraphResult(
    val rawFirstRows: Stats,
    val rawLog: Stats,
    val loads: List<GraphLoadSample>,
    val cold: List<ColdActivationSample>,
)

private fun measureCommitGraph(
    path: Path,
    backend: GitBackend,
    iterations: Int,
): CommitGraphResult = runBlocking {
    CommitGraph.write(path)
    try {
        val rawFirstRows = GraphBench.rawLogFirstRows(
            path,
            records = GraphBench.FIRST_VISIBLE_ROWS,
            warmups = 1,
            iterations = iterations,
        )
        val rawLog = GraphBench.rawLogStats(path, warmups = 1, iterations = iterations).first
        val loads = GraphBench.measureGraphLoad(backend, warmups = 1, iterations = iterations)
        val cold = GraphBench.measureColdActivation(path, warmups = 1, iterations = iterations)
        CommitGraphResult(rawFirstRows, rawLog, loads, cold)
    } finally {
        CommitGraph.remove(path)
    }
}

private fun printCommitGraphSection(
    rawFirstRows: Stats,
    rawLog: Stats,
    cold: List<ColdActivationSample>,
    loads: List<GraphLoadSample>,
    result: CommitGraphResult,
) {
    val first = stats(cold.map { it.firstVisibleMillis })
    val full = stats(loads.map { it.fullMillis })
    val withGraphFirst = stats(result.cold.map { it.firstVisibleMillis })
    val withGraphFull = stats(result.loads.map { it.fullMillis })

    println()
    println("-- Optional commit-graph experiment (written for this measurement, then removed) --")
    println("Raw first ${GraphBench.FIRST_VISIBLE_ROWS} rows:    without ${rawFirstRows.format()}, with ${result.rawFirstRows.format()}")
    println("Cold first rows:  without ${first.format()}, with ${withGraphFirst.format()}")
    println("Full graph:       without ${full.format()}, with ${withGraphFull.format()}")
    println("Raw full log:     without ${rawLog.format()}, with ${result.rawLog.format()}")
}

private fun printBudgetSummary(
    repo: PerfRepo,
    rawLog: Stats,
    rawFirstRows: Stats,
    loads: List<GraphLoadSample>,
    cold: List<ColdActivationSample>,
    syntheticRowsPerSecond: Double,
    realRowsPerSecond: Double,
    syntheticMemory: RetainedMemory,
    realMemory: RetainedMemory,
    realIndexBytes: Long,
    realScroll: Stats,
    frozenAccess: Stats,
    status: StatusBench,
    commitGraph: CommitGraphResult,
) {
    val full = stats(loads.map { it.fullMillis })
    val coldFirst = stats(cold.map { it.firstVisibleMillis })
    val graphFirst = stats(commitGraph.cold.map { it.firstVisibleMillis })
    val realBytesWithIndex = realMemory.bytesPerCommit(realMemory.snapshot.rowCount) +
        realIndexBytes.toDouble() / realMemory.snapshot.rowCount
    val statusOverhead = status.backend.median - status.raw.median
    val overheadPercent = ((full.median - rawLog.median) / rawLog.median) * 100.0

    println()
    println("-- Budget comparison (informational, no hard assertion) --")
    budget("First visible rows < 400 ms", coldFirst.median, 400.0, "ms")
    budget("Full graph < 3000 ms", full.median, 3_000.0, "ms")
    budget("Full graph overhead <= 30%", overheadPercent, 30.0, "%")
    budgetAtLeast("Layout throughput >= 300k rows/s (50k)", syntheticRowsPerSecond, 300_000.0)
    budgetAtLeast("Layout throughput >= 300k rows/s (200k)", realRowsPerSecond, 300_000.0)
    budget("Graph memory <= 400 B/commit (50k)", syntheticMemory.bytesPerCommit(syntheticMemory.snapshot.rowCount), 400.0, "B")
    budget("Graph memory <= 400 B/commit (200k)", realMemory.bytesPerCommit(realMemory.snapshot.rowCount), 400.0, "B")
    budget("Graph memory <= 400 B/commit (200k + index)", realBytesWithIndex, 400.0, "B")
    budget("Fast scroll max frame <= 16 ms", realScroll.max, 16.0, "ms")
    budget("Tab switch (frozen snapshot) <= 50 ms", frozenAccess.p99, 50.0, "ms")
    budget("Status backend overhead <= 50 ms", statusOverhead, 50.0, "ms")
    println("Status parse ${status.dirtyEntries} dirty entries: ${status.dirtyParse.format()} (context for a very dirty tree)")
    println("First-visible attribution: raw git topo-order first ${GraphBench.FIRST_VISIBLE_ROWS} rows ${rawFirstRows.format()}, cold path ${coldFirst.format()}.")
    println("First visible rows with commit-graph: ${graphFirst.format()} (mitigation, not the canonical measurement)")
    println("Repo: ${repo.path}")
}

private fun budget(label: String, measured: Double, limit: Double, unit: String) {
    val verdict = if (measured <= limit) "within" else "OVER"
    println("%-48s %10s / %-10s %s".format(Locale.ROOT, label, "${"%.1f".format(Locale.ROOT, measured)} $unit", "${"%.1f".format(Locale.ROOT, limit)} $unit", verdict))
}

private fun budgetAtLeast(label: String, measured: Double, limit: Double) {
    val verdict = if (measured >= limit) "within" else "UNDER"
    println("%-48s %10s / %-10s %s".format(Locale.ROOT, label, formatRate(measured), formatRate(limit), verdict))
}

private const val VIEWPORT_ROWS = 40
private const val SCROLL_JUMPS = 2_000
