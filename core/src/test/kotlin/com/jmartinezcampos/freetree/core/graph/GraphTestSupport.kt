package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal data class RowView(
    val row: Int,
    val hash: String,
    val nodeLane: Int,
    val nodeColor: Int,
    val parentCount: Int,
    val segments: List<GraphSegment>,
)

internal data class ReconstructedEdges(
    val edges: Set<Pair<Int, Int>>,
    val dangling: Int,
)

internal fun layout(
    commits: List<RawCommit>,
    flat: Boolean = false,
    chunkSize: Int = 4096,
): GraphSnapshot {
    val engine = LaneLayoutEngine(flat = flat, chunkSize = chunkSize)
    commits.forEach(engine::add)
    return engine.snapshot(complete = true)
}

internal fun GraphSnapshot.rows(): List<RowView> = (0 until rowCount).map { row ->
    RowView(
        row = row,
        hash = hash(row),
        nodeLane = nodeLane(row),
        nodeColor = nodeColor(row),
        parentCount = parentCount(row),
        segments = segments(row).toList(),
    )
}

internal fun SegmentsView.toList(): List<GraphSegment> = List(size) { get(it) }

internal fun reconstructEdges(snapshot: GraphSnapshot): ReconstructedEdges {
    val pendingOrigins = HashMap<Int, MutableList<Int>>()
    val edges = HashSet<Pair<Int, Int>>()

    for (row in 0 until snapshot.rowCount) {
        snapshot.segments(row).toList().forEach { segment ->
            when (segment.kind) {
                SegKind.OUT -> pendingOrigins.getOrPut(segment.to) { mutableListOf() }.add(row)
                SegKind.IN -> {
                    val origins = assertNotNull(
                        pendingOrigins.remove(segment.from),
                        "IN lane ${segment.from} on row $row has no pending child",
                    )
                    origins.forEach { origin ->
                        assertTrue(edges.add(origin to row), "duplicate edge $origin -> $row")
                    }
                }
                SegKind.PASS -> Unit
            }
        }
    }

    return ReconstructedEdges(edges, pendingOrigins.values.sumOf { it.size })
}

internal fun assertInvariants(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    assertI1(context, commits, snapshot)
    assertI2(context, commits, snapshot)
    assertI3(context, snapshot)
    assertI4(context, commits, snapshot)
    assertI6(context, commits, snapshot)
    assertI7(context, commits, snapshot)
}

private fun assertI1(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    assertEquals(commits.size, snapshot.rowCount, "$context: row count")
    commits.forEachIndexed { row, commit ->
        assertEquals(commit.hash, snapshot.hash(row), "$context: hash at row $row")
        assertEquals(commit.subject, snapshot.subject(row), "$context: subject at row $row")
        assertEquals(commit.authorName, snapshot.author(row), "$context: author at row $row")
        assertEquals(commit.authorTimeSec, snapshot.timeSec(row), "$context: time at row $row")
        assertEquals(commit.parents.size, snapshot.parentCount(row), "$context: parents at row $row")
        assertTrue(
            snapshot.nodeLane(row) in 0 until snapshot.maxLanes,
            "$context: node lane ${snapshot.nodeLane(row)} outside 0..${snapshot.maxLanes - 1}",
        )
        assertTrue(
            snapshot.nodeColor(row) in 0..255,
            "$context: node color ${snapshot.nodeColor(row)} outside byte range",
        )
        assertEquals(row, snapshot.indexOfHash(commit.hash), "$context: indexOfHash for row $row")
    }
    assertEquals(
        commits.size,
        commits.map { it.hash }.distinct().size,
        "$context: fixture has duplicate hashes",
    )
}

private fun assertI2(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    val rowByHash = commits.withIndex().associate { it.value.hash to it.index }
    val expectedEdges = HashSet<Pair<Int, Int>>()
    var missingParents = 0

    commits.forEachIndexed { childRow, commit ->
        commit.parents.forEach { parent ->
            val parentRow = rowByHash[parent]
            if (parentRow == null) {
                missingParents++
            } else {
                expectedEdges += childRow to parentRow
            }
        }
    }

    val reconstructed = reconstructEdges(snapshot)
    assertEquals(expectedEdges, reconstructed.edges, "$context: reconstructed edges")
    assertEquals(missingParents, reconstructed.dangling, "$context: dangling edge count")
}

private fun assertI3(context: String, snapshot: GraphSnapshot) {
    for (row in 0 until snapshot.rowCount - 1) {
        assertEquals(
            bottomLanes(snapshot, row),
            topLanes(snapshot, row + 1),
            "$context: lanes crossing row $row into ${row + 1}",
        )
        assertTrue(
            snapshot.segments(row).toList().all { it.kind != SegKind.PASS || it.from == it.to },
            "$context: PASS segment does not stay in its lane on row $row",
        )
    }
}

private fun bottomLanes(snapshot: GraphSnapshot, row: Int): Map<Int, Int> {
    val lanes = HashMap<Int, Int>()
    snapshot.segments(row).toList().forEach { segment ->
        when (segment.kind) {
            SegKind.PASS, SegKind.OUT -> putLane(lanes, segment.to, segment.color, row)
            SegKind.IN -> Unit
        }
    }
    return lanes
}

private fun topLanes(snapshot: GraphSnapshot, row: Int): Map<Int, Int> {
    val lanes = HashMap<Int, Int>()
    snapshot.segments(row).toList().forEach { segment ->
        when (segment.kind) {
            SegKind.IN, SegKind.PASS -> putLane(lanes, segment.from, segment.color, row)
            SegKind.OUT -> Unit
        }
    }
    return lanes
}

private fun putLane(lanes: MutableMap<Int, Int>, lane: Int, color: Int, row: Int) {
    val previous = lanes.put(lane, color)
    assertTrue(
        previous == null || previous == color,
        "row $row: lane $lane has conflicting colors $previous and $color",
    )
}

private fun assertI4(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    commits.forEachIndexed { row, commit ->
        val outgoing = snapshot.segments(row).toList().filter { it.kind == SegKind.OUT }
        assertEquals(commit.parents.size, outgoing.size, "$context: OUT count on row $row")
        if (outgoing.isEmpty()) return@forEachIndexed

        val first = outgoing.first()
        assertEquals(snapshot.nodeLane(row), first.from, "$context: first OUT source on row $row")
        assertEquals(snapshot.nodeLane(row), first.to, "$context: first OUT target on row $row")
        assertEquals(snapshot.nodeColor(row), first.color, "$context: first OUT color on row $row")
        assertTrue(!first.merge, "$context: first OUT on row $row must not be a merge")
        outgoing.drop(1).forEach { merge ->
            assertEquals(snapshot.nodeLane(row), merge.from, "$context: merge OUT source on row $row")
            assertTrue(merge.merge, "$context: secondary OUT on row $row must be a merge")
        }
    }
}

private fun assertI6(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    val childCounts = HashMap<String, Int>()
    commits.forEach { commit ->
        commit.parents.forEach { parent -> childCounts.merge(parent, 1, Int::plus) }
    }
    commits.forEachIndexed { row, commit ->
        val segments = snapshot.segments(row)
        if (commit.parents.isEmpty()) {
            assertTrue(
                segments.toList().none { it.kind == SegKind.OUT },
                "$context: root row $row emits OUT",
            )
        }
        if (childCounts[commit.hash] == null) {
            assertTrue(
                segments.toList().none { it.kind == SegKind.IN },
                "$context: childless row $row has an IN",
            )
        }
    }
}

private fun assertI7(context: String, commits: List<RawCommit>, snapshot: GraphSnapshot) {
    val rowByHash = commits.withIndex().associate { it.value.hash to it.index }
    val rows = snapshot.rows()

    commits.forEachIndexed { childRow, commit ->
        val outgoing = rows[childRow].segments.filter { it.kind == SegKind.OUT }
        commit.parents.forEachIndexed { parentIndex, parent ->
            val targetLane = outgoing[parentIndex].to
            assertTrue(targetLane < snapshot.maxLanes, "$context: edge lane outside maxLanes")
            val parentRow = rowByHash[parent]
            val lastRow = parentRow ?: snapshot.rowCount

            for (between in childRow + 1 until lastRow) {
                assertTrue(
                    rows[between].segments.any {
                        it.kind == SegKind.PASS && it.from == targetLane && it.to == targetLane
                    },
                    "$context: lane $targetLane not alive between rows $childRow and $lastRow at row $between",
                )
            }
            if (parentRow != null) {
                assertTrue(
                    rows[parentRow].segments.any {
                        it.kind == SegKind.IN && it.from == targetLane
                    },
                    "$context: lane $targetLane does not enter parent row $parentRow",
                )
            }
        }
    }
}
