package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaneLayoutEngineTest {

    @Test
    fun emptySnapshotHasNoRows() {
        val snapshot = LaneLayoutEngine().snapshot(complete = false)

        assertEquals(0, snapshot.rowCount)
        assertEquals(0, snapshot.maxLanes)
        assertEquals(-1, snapshot.indexOfHash("anything"))
        assertTrue(!snapshot.complete)
        assertTrue(!snapshot.flat)
    }

    @Test
    fun linearHistoryUsesOneLane() {
        val snapshot = layout(
            listOf(
                commit("a", "b"),
                commit("b", "c"),
                commit("c"),
            ),
        )

        assertEquals(1, snapshot.maxLanes)
        assertEquals(listOf(SegKind.OUT), snapshot.segments(0).kinds())
        assertEquals(listOf(SegKind.IN, SegKind.OUT), snapshot.segments(1).kinds())
        assertEquals(listOf(SegKind.IN), snapshot.segments(2).kinds())
        repeat(3) { row ->
            assertEquals(0, snapshot.nodeLane(row))
            assertEquals(0, snapshot.nodeColor(row))
        }
        assertEquals(
            listOf(GraphSegment(0, 0, 0, SegKind.OUT, false)),
            snapshot.segments(0).toList(),
        )
    }

    @Test
    fun mergeConvergesBothColumnsIntoTheParent() {
        val snapshot = layout(
            listOf(
                commit("x", "a", "b"),
                commit("a", "c"),
                commit("b", "c"),
                commit("c"),
            ),
        )

        assertEquals(2, snapshot.maxLanes)
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.OUT, false),
                GraphSegment(0, 1, 1, SegKind.OUT, true),
            ),
            snapshot.segments(0).toList(),
        )
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.IN, false),
                GraphSegment(1, 1, 1, SegKind.PASS, false),
                GraphSegment(0, 0, 0, SegKind.OUT, false),
            ),
            snapshot.segments(1).toList(),
        )
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.PASS, false),
                GraphSegment(1, 1, 1, SegKind.IN, false),
                GraphSegment(1, 1, 1, SegKind.OUT, false),
            ),
            snapshot.segments(2).toList(),
        )
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.IN, false),
                GraphSegment(1, 0, 1, SegKind.IN, false),
            ),
            snapshot.segments(3).toList(),
        )
    }

    @Test
    fun octopusMergeEmitsOneOutPerParent() {
        val snapshot = layout(
            listOf(
                commit("x", "a", "b", "c"),
                commit("a"),
                commit("b"),
                commit("c"),
            ),
        )

        assertEquals(3, snapshot.maxLanes)
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.OUT, false),
                GraphSegment(0, 1, 1, SegKind.OUT, true),
                GraphSegment(0, 2, 2, SegKind.OUT, true),
            ),
            snapshot.segments(0).toList(),
        )
        assertEquals(
            listOf(
                GraphSegment(0, 0, 0, SegKind.IN, false),
                GraphSegment(1, 1, 1, SegKind.PASS, false),
                GraphSegment(2, 2, 2, SegKind.PASS, false),
            ),
            snapshot.segments(1).toList(),
        )
        assertEquals(
            listOf(
                GraphSegment(1, 1, 1, SegKind.IN, false),
                GraphSegment(2, 2, 2, SegKind.PASS, false),
            ),
            snapshot.segments(2).toList(),
        )
        assertEquals(listOf(GraphSegment(2, 2, 2, SegKind.IN, false)), snapshot.segments(3).toList())
    }

    @Test
    fun missingParentLeavesADanglingLane() {
        val commits = listOf(commit("a", "missing"), commit("b"))
        val snapshot = layout(commits)

        assertEquals(2, snapshot.maxLanes)
        assertEquals(listOf(GraphSegment(0, 0, 0, SegKind.OUT, false)), snapshot.segments(0).toList())
        assertEquals(listOf(GraphSegment(0, 0, 0, SegKind.PASS, false)), snapshot.segments(1).toList())
        assertEquals(1, snapshot.nodeLane(1))
        assertInvariants("missing parent", commits, snapshot)
    }

    @Test
    fun childlessCommitOpensANewColumn() {
        val commits = listOf(commit("a", "b"), commit("c"), commit("b"))
        val snapshot = layout(commits)

        assertEquals(1, snapshot.segments(1).size)
        assertEquals(GraphSegment(0, 0, 0, SegKind.PASS, false), snapshot.segments(1)[0])
        assertEquals(1, snapshot.nodeLane(1))
        assertEquals(2, snapshot.maxLanes)
        assertEquals(1, snapshot.nodeColor(1))
    }

    @Test
    fun rootCommitEmitsNoOutgoingSegment() {
        val snapshot = layout(listOf(commit("root")))

        assertEquals(0, snapshot.segments(0).size)
        assertEquals(0, snapshot.nodeLane(0))
        assertEquals(1, snapshot.maxLanes)
    }

    @Test
    fun colorsWrapToThePackedByteRange() {
        val parents = (0 until 300).map { "p$it" }
        val snapshot = layout(listOf(commit("x", *parents.toTypedArray())))

        val segments = snapshot.segments(0).toList()
        assertEquals(300, segments.size)
        assertEquals(300, snapshot.maxLanes)
        assertEquals(GraphSegment(0, 0, 0, SegKind.OUT, false), segments.first())
        segments.drop(1).forEachIndexed { index, segment ->
            val lane = index + 1
            assertEquals(lane, segment.to)
            assertEquals(lane and 0xFF, segment.color)
            assertTrue(segment.merge)
        }
        assertTrue(segments.all { it.color in 0..255 })
    }

    @Test
    fun rowsSpanChunksWithoutLosingData() {
        val commits = RandomDag(
            seed = 111,
            commitCount = 25,
            missingParentProbability = 0.0,
        ).generate()
        val engine = LaneLayoutEngine(chunkSize = 4)
        commits.forEach(engine::add)
        val snapshot = engine.snapshot(complete = true)

        assertInvariants("chunked", commits, snapshot)
        commits.forEachIndexed { row, commit ->
            assertEquals(row, snapshot.indexOfHash(commit.hash))
            assertEquals(commit.hash, snapshot.hash(row))
            assertEquals(commit.authorName, snapshot.author(row))
            assertEquals(commit.subject, snapshot.subject(row))
            assertEquals(commit.authorTimeSec, snapshot.timeSec(row))
        }
        assertTrue(snapshot.complete)
    }

    @Test
    fun flatModeHasOneColumnAndNoEdges() {
        val commits = RandomDag(seed = 77, commitCount = 120).generate()
        val engine = LaneLayoutEngine(flat = true, chunkSize = 9)
        commits.forEach(engine::add)
        val snapshot = engine.snapshot(complete = true)

        assertTrue(engine.flat)
        assertTrue(snapshot.flat)
        assertEquals(1, snapshot.maxLanes)
        commits.forEachIndexed { row, commit ->
            assertEquals(0, snapshot.nodeLane(row))
            assertEquals(0, snapshot.nodeColor(row))
            assertEquals(commit.parents.size, snapshot.parentCount(row))
            assertEquals(0, snapshot.segments(row).size)
            assertEquals(row, snapshot.indexOfHash(commit.hash))
        }
    }

    @Test
    fun emptyFlatSnapshotStillReportsTheFlag() {
        val snapshot = LaneLayoutEngine(flat = true).snapshot(complete = true)

        assertEquals(0, snapshot.rowCount)
        assertEquals(0, snapshot.maxLanes)
        assertTrue(snapshot.flat)
    }

    @Test
    fun rejectsNonPositiveChunkSize() {
        assertFailsWith<IllegalArgumentException> { LaneLayoutEngine(chunkSize = 0) }
        assertFailsWith<IllegalArgumentException> { LaneLayoutEngine(chunkSize = -1) }
    }

    @Test
    fun segmentAccessIsBounded() {
        val snapshot = layout(listOf(commit("a", "b"), commit("b")))
        val segments = snapshot.segments(0)

        assertFailsWith<IllegalArgumentException> { segments[1] }
        assertFailsWith<IllegalArgumentException> { segments[-1] }
        assertFailsWith<IllegalArgumentException> { snapshot.hash(snapshot.rowCount) }
        assertFailsWith<IllegalArgumentException> { snapshot.hash(-1) }
    }

    private fun commit(hash: String, vararg parents: String) = RawCommit(
        hash = hash,
        parents = parents.toList(),
        authorName = "Author",
        authorTimeSec = 1_700_000_000L,
        subject = "subject $hash",
    )

    private fun SegmentsView.kinds(): List<SegKind> = List(size) { get(it).kind }
}
