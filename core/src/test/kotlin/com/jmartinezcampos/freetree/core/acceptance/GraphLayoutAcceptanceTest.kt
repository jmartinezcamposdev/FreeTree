package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.graph.SegKind
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class GraphLayoutAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var backend: CliGitBackend

    @BeforeTest
    fun setUp() {
        repo = GitTestRepo.create()
        backend = runBlocking { repo.openBackend() }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun realMergeHistoryProducesStableLanesMergeEdgesAndRefs() = runBlocking {
        repo.writeFile("README.md", "root\n")
        val root = repo.commitAll("root")
        repo.git("tag", "-a", "v1.0", "-m", "release", root).requireSuccess()
        repo.checkoutNewBranch("feature/topic").requireSuccess()
        val topic = repo.commitFile("topic.txt", "topic\n", "topic commit")
        repo.checkout("main").requireSuccess()
        repo.commitFile("main.txt", "main\n", "main commit")
        repo.merge("feature/topic").requireSuccess()
        val merge = repo.headHash()
        repo.createBareOrigin()
        repo.git("push", "-u", "origin", "main").requireSuccess()
        repo.createBranch("release/1.0").requireSuccess()

        val refs = backend.refs()
        val spec = LogSpec(revs = refs.map { it.fullName }, includeHead = true)
        val rows = backend.logStream(spec).toList()
        val expected = parseRevListParents(
            repo.git(
                "rev-list",
                "--parents",
                "--topo-order",
                "HEAD",
                *refs.map { it.fullName }.distinct().toTypedArray(),
            ).requireSuccess().stdout,
        )

        assertEquals(expected.map { it.first }, rows.map { it.hash }, "row order")
        assertEquals(expected.map { it.second }, rows.map { it.parents }, "row parents")
        assertTrue(topic in rows.map { it.hash })
        assertEquals(2, rows.single { it.hash == merge }.parents.size)

        val graph = layout(rows)
        val mergeRow = graph.indexOfHash(merge)
        assertTrue(mergeRow >= 0, "merge commit must be in the graph")
        assertEquals(2, graph.parentCount(mergeRow))
        assertLaneInvariants(graph)

        val mergeSegments = (0 until graph.segments(mergeRow).size)
            .map { graph.segments(mergeRow)[it] }
            .filter { it.kind == SegKind.OUT && it.merge }
        assertEquals(1, mergeSegments.size, "one secondary merge edge")

        assertEquals(
            setOf(
                "refs/heads/feature/topic",
                "refs/heads/main",
                "refs/heads/release/1.0",
                "refs/remotes/origin/main",
                "refs/tags/v1.0",
            ),
            refs.map { it.fullName }.toSet(),
        )
        assertEquals(root, refs.single { it.fullName == "refs/tags/v1.0" }.commit)
        assertTrue(refs.single { it.fullName == "refs/heads/main" }.isCurrentBranch)
        assertEquals("origin/main", refs.single { it.fullName == "refs/heads/main" }.upstream)
    }

    @Test
    fun octopusMergeGetsOneArrowForEverySecondaryParent() = runBlocking {
        repo.writeFile("base.txt", "base\n")
        repo.commitAll("root")
        repo.checkoutNewBranch("side-a").requireSuccess()
        repo.commitFile("a.txt", "a\n", "side a")
        repo.checkout("main").requireSuccess()
        repo.checkoutNewBranch("side-b").requireSuccess()
        repo.commitFile("b.txt", "b\n", "side b")
        repo.checkout("main").requireSuccess()
        repo.commitFile("main.txt", "main\n", "main")
        repo.git("merge", "--no-ff", "-m", "octopus merge", "side-a", "side-b").requireSuccess()
        val merge = repo.headHash()

        val parents = repo.git("show", "-s", "--format=%P", merge).requireSuccess().stdout.trim().split(' ')
        assertEquals(3, parents.size)

        val refs = backend.refs().map { it.fullName }
        val graph = layout(backend.logStream(LogSpec(revs = refs, includeHead = true)).toList())
        val row = graph.indexOfHash(merge)
        assertTrue(row >= 0)
        assertEquals(3, graph.parentCount(row))

        val mergeEdges = (0 until graph.segments(row).size)
            .map { graph.segments(row)[it] }
            .filter { it.kind == SegKind.OUT && it.merge }
        assertEquals(2, mergeEdges.size, "each secondary parent needs a merge arrow")
        assertEquals(2, mergeEdges.map { it.to }.toSet().size, "merge arrows must use distinct lanes")
        assertEquals(graph.nodeLane(row), graph.segments(row).let { segments ->
            (0 until segments.size)
                .map { segments[it] }
                .single { it.kind == SegKind.OUT && !it.merge }
                .from
        })
        assertLaneInvariants(graph)
    }

    private fun layout(rows: List<RawCommit>): GraphSnapshot {
        val engine = LaneLayoutEngine()
        rows.forEach(engine::add)
        return engine.snapshot(complete = true)
    }

    private fun assertLaneInvariants(graph: GraphSnapshot) {
        assertTrue(graph.maxLanes >= 1)
        for (row in 0 until graph.rowCount) {
            val node = graph.nodeLane(row)
            assertTrue(node in 0 until graph.maxLanes, "node lane out of range at row $row")
            val segments = graph.segments(row)
            var mergeOuts = 0
            var firstParentOuts = 0
            for (index in 0 until segments.size) {
                val segment = segments[index]
                assertTrue(segment.from in 0 until graph.maxLanes, "segment start out of range at row $row")
                assertTrue(segment.to in 0 until graph.maxLanes, "segment end out of range at row $row")
                when (segment.kind) {
                    SegKind.PASS -> {
                        assertEquals(segment.from, segment.to, "pass must stay in its lane at row $row")
                        assertFalse(segment.merge, "pass segments are never merge arrows")
                    }
                    SegKind.IN -> {
                        assertEquals(node, segment.to, "incoming edge must end at the node at row $row")
                        assertFalse(segment.merge, "incoming segments are never merge arrows")
                    }
                    SegKind.OUT -> {
                        assertEquals(node, segment.from, "outgoing edge must start at the node at row $row")
                        if (segment.merge) mergeOuts++ else firstParentOuts++
                    }
                }
            }
            val parents = graph.parentCount(row)
            assertEquals((parents - 1).coerceAtLeast(0), mergeOuts, "merge arrows at row $row")
            assertEquals(if (parents > 0) 1 else 0, firstParentOuts, "first-parent edge at row $row")
        }

        for (row in 0 until graph.rowCount - 1) {
            val segments = graph.segments(row)
            val carried = (0 until segments.size)
                .map { segments[it] }
                .filter { it.kind != SegKind.IN }
                .map { it.to }
                .toSet()
            val next = graph.segments(row + 1)
            val nextLanes = (0 until next.size).flatMap { listOf(next[it].from, next[it].to) }.toSet()
            carried.forEach { lane ->
                assertTrue(
                    lane in nextLanes,
                    "lane $lane disappears between ${graph.hash(row)} and ${graph.hash(row + 1)}",
                )
            }
        }
    }
}
