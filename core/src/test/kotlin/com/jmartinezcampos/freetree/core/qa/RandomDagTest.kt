package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomDagTest {

    @Test
    fun sameSeedProducesTheSameDag() {
        val first = RandomDag(seed = 1234, commitCount = 250).generate()
        val second = RandomDag(seed = 1234, commitCount = 250).generate()

        assertEquals(first, second)
        assertNotEquals(first, RandomDag(seed = 1235, commitCount = 250).generate())
        assertEquals(first, RandomDag.generate(seed = 1234, commitCount = 250))
    }

    @Test
    fun commitsAreUniqueAndTopologicallyOrdered() {
        val commits = RandomDag(seed = 77, commitCount = 300).generate()
        val positionByHash = commits.mapIndexed { index, commit -> commit.hash to index }.toMap()

        assertEquals(300, commits.size)
        assertEquals(300, positionByHash.size)
        assertEquals(commits.size, commits.map { it.hash }.distinct().size)
        assertTrue(commits.all { it.hash.length == 40 })

        commits.forEachIndexed { index, commit ->
            commit.parents.forEach { parent ->
                val parentIndex = positionByHash[parent] ?: return@forEach
                assertTrue(parentIndex > index, "parent $parent must be older than row $index")
            }
        }
    }

    @Test
    fun defaultDagHasRootsMergesAndMissingParents() {
        val commits = RandomDag(seed = 9).generate()
        val known = commits.map { it.hash }.toSet()

        assertEquals(2, commits.count { it.parents.isEmpty() })
        assertTrue(commits.any { commit -> commit.parents.count { it in known } >= 2 })
        assertTrue(commits.any { commit -> commit.parents.any { it !in known } })
        assertTrue(commits.any { it.parents.size >= 3 })
        assertTrue(commits.flatMap { it.parents }.filter { it !in known }.all { it.length == 40 })
    }

    @Test
    fun configuredRootsAreHonoured() {
        val commits = RandomDag(seed = 21, commitCount = 50, rootCount = 5).generate()

        assertEquals(5, commits.count { it.parents.isEmpty() })
        assertTrue(commits.takeLast(5).all { it.parents.isEmpty() })
    }

    @Test
    fun dagProducesManyColumns() {
        val commits = RandomDag(seed = 31415, commitCount = 400).generate()

        val lanes = maxOpenLanes(commits)
        assertTrue(lanes >= 12, "expected many lanes, got $lanes")
    }

    @Test
    fun noMergesOrMissingParentsCanBeRequested() {
        val commits = RandomDag(
            seed = 5,
            commitCount = 40,
            mergeProbability = 0.0,
            octopusProbability = 0.0,
            missingParentProbability = 0.0,
        ).generate()

        assertTrue(commits.filter { it.parents.isNotEmpty() }.all { it.parents.size == 1 })
    }

    private fun maxOpenLanes(commits: List<RawCommit>): Int {
        val lanes = mutableListOf<String?>()
        var maxLanes = 0

        for (commit in commits) {
            val incoming = lanes.indices.filter { lanes[it] == commit.hash }
            val node = if (incoming.isEmpty()) {
                lanes.indexOfFirst { it == null }.takeIf { it >= 0 } ?: lanes.size
            } else {
                incoming.min()
            }

            incoming.forEach { lanes[it] = null }

            if (commit.parents.isNotEmpty()) {
                if (node == lanes.size) lanes += commit.parents.first() else lanes[node] = commit.parents.first()
                commit.parents.drop(1).forEach { parent ->
                    if (lanes.none { it == parent }) {
                        val free = lanes.indexOfFirst { it == null }.takeIf { it >= 0 } ?: lanes.size
                        if (free == lanes.size) lanes += parent else lanes[free] = parent
                    }
                }
            }

            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)
            maxLanes = maxOf(maxLanes, lanes.size, node + 1)
        }
        return maxLanes
    }
}
