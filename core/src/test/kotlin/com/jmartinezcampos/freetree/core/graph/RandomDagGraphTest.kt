package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomDagGraphTest {

    @Test
    fun seededDagsSatisfyInvariants() {
        val chunks = listOf(1, 7, 64, 4096)
        val seeds = listOf(1L, 7L, 42L, 1_234L, 98_765L)
        seeds.forEachIndexed { index, seed ->
            val commits = RandomDag(seed = seed, commitCount = 250).generate()
            val snapshot = layout(commits, chunkSize = chunks[index % chunks.size])
            assertInvariants("seed=$seed", commits, snapshot)
            assertTrue(snapshot.maxLanes > 1, "seed=$seed: expected more than one lane")
        }
    }

    @Test
    fun dagsWithoutMissingParentsReconstructExactly() {
        val commits = RandomDag(
            seed = 314,
            commitCount = 300,
            missingParentProbability = 0.0,
        ).generate()
        val snapshot = layout(commits, chunkSize = 13)

        assertInvariants("no missing parents", commits, snapshot)
        assertEquals(0, reconstructEdges(snapshot).dangling)
    }

    @Test
    fun octopusMergesGetMergeSegments() {
        val commits = RandomDag(
            seed = 2718,
            commitCount = 180,
            mergeProbability = 0.6,
            octopusProbability = 0.4,
            missingParentProbability = 0.0,
        ).generate()
        val snapshot = layout(commits, chunkSize = 16)

        assertInvariants("octopus", commits, snapshot)
        val mergeCounts = commits.map { it.parents.size }.filter { it >= 3 }
        assertTrue(mergeCounts.isNotEmpty(), "fixture must contain octopus merges")
        commits.forEachIndexed { row, commit ->
            if (commit.parents.size >= 3) {
                val outgoing = snapshot.segments(row).toList().filter { it.kind == SegKind.OUT }
                assertEquals(commit.parents.size, outgoing.size)
                assertTrue(outgoing.drop(1).all { it.merge })
            }
        }
    }

    @Test
    fun manyColumnsStayWithinMaxLanes() {
        val commits = RandomDag(seed = 31_415, commitCount = 400).generate()
        val snapshot = layout(commits, chunkSize = 31)

        assertInvariants("many columns", commits, snapshot)
        assertTrue(snapshot.maxLanes >= 12, "expected many columns, got ${snapshot.maxLanes}")
    }

    @Test
    fun oneShotAndRandomBatchesProduceIdenticalPrefixes() {
        val commits = RandomDag(seed = 8080, commitCount = 320).generate()
        val oneShot = layout(commits, chunkSize = 7)

        val engine = LaneLayoutEngine(chunkSize = 7)
        val random = Random(8080)
        var added = 0
        while (added < commits.size) {
            val batchSize = random.nextInt(1, 21).coerceAtMost(commits.size - added)
            repeat(batchSize) {
                engine.add(commits[added])
                added++
            }
            val snapshot = engine.snapshot(complete = added == commits.size)
            assertEquals(added, snapshot.rowCount, "row count after $added commits")
            for (row in 0 until added) {
                assertEquals(oneShot.hash(row), snapshot.hash(row), "hash at row $row")
                assertEquals(oneShot.subject(row), snapshot.subject(row), "subject at row $row")
                assertEquals(oneShot.author(row), snapshot.author(row), "author at row $row")
                assertEquals(oneShot.timeSec(row), snapshot.timeSec(row), "time at row $row")
                assertEquals(oneShot.parentCount(row), snapshot.parentCount(row), "parents at row $row")
                assertEquals(oneShot.nodeLane(row), snapshot.nodeLane(row), "lane at row $row")
                assertEquals(oneShot.nodeColor(row), snapshot.nodeColor(row), "color at row $row")
                assertEquals(
                    oneShot.segments(row).toList(),
                    snapshot.segments(row).toList(),
                    "segments at row $row",
                )
            }
        }
        assertEquals(commits.size, oneShot.rowCount)
        assertEquals(oneShot.maxLanes, engine.snapshot(complete = true).maxLanes)
    }

    @Test
    fun missingParentsLeaveDanglingLanesWithoutFailing() {
        val commits = RandomDag(
            seed = 5150,
            commitCount = 160,
            missingParentProbability = 0.15,
        ).generate()
        val snapshot = layout(commits, chunkSize = 9)

        assertInvariants("missing parents", commits, snapshot)
        assertTrue(reconstructEdges(snapshot).dangling > 0, "fixture must contain missing parents")
        assertEquals(commits.size, snapshot.rowCount)
    }

    @Test
    fun snapshotsAreStablePrefixesAfterLaterAdds() {
        val commits = RandomDag(seed = 606, commitCount = 100).generate()
        val engine = LaneLayoutEngine(chunkSize = 5)
        val prefixSize = 43

        commits.take(prefixSize).forEach(engine::add)
        val before = engine.snapshot(complete = false)
        val capturedHashes = (0 until prefixSize).map(before::hash)
        val capturedSegments = (0 until prefixSize).map { before.segments(it).toList() }
        val capturedMaxLanes = before.maxLanes

        commits.drop(prefixSize).forEach(engine::add)

        assertEquals(prefixSize, before.rowCount)
        assertEquals(capturedMaxLanes, before.maxLanes)
        assertEquals(capturedHashes, (0 until prefixSize).map(before::hash))
        assertEquals(
            capturedSegments,
            (0 until prefixSize).map { before.segments(it).toList() },
        )
        assertEquals(-1, before.indexOfHash(commits.last().hash))
        val complete = engine.snapshot(complete = true)
        assertEquals(commits.size - 1, complete.indexOfHash(commits.last().hash))
        assertTrue(!before.complete)
        assertTrue(complete.complete)
    }
}
