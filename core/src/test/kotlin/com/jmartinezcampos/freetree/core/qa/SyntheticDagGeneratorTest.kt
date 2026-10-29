package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.testing.SyntheticDagGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyntheticDagGeneratorTest {

    @Test
    fun smokeDagIsTopologicalAndComplete() {
        val generator = SyntheticDagGenerator(seed = 42, commitCount = 2_000)
        val dag = generator.generate()

        assertEquals(2_000, dag.commits.size)
        assertEquals(2_000, dag.commits.map { it.hash }.distinct().size)
        assertEquals(20, dag.authors.size)
        assertEquals(30, dag.branchNames.size)
        assertEquals(100, dag.tagNames.size)
        assertEquals(130, dag.refs.size)

        val position = dag.commits.withIndex().associate { it.value.hash to it.index }
        dag.commits.forEachIndexed { row, commit ->
            assertEquals(40, commit.hash.length)
            commit.parents.forEach { parent ->
                val parentRow = position.getValue(parent)
                assertTrue(parentRow > row, "parent $parent must be older than row $row")
            }
        }
        assertTrue(dag.commits.any { it.parents.size >= 2 })
        assertTrue(dag.commits.any { it.parents.isEmpty() })

        val branchRefs = dag.refs.filter { it.kind == RefKind.LOCAL_BRANCH }
        val tagRefs = dag.refs.filter { it.kind == RefKind.TAG }
        assertEquals(30, branchRefs.size)
        assertEquals(100, tagRefs.size)
        assertEquals(1, branchRefs.count { it.isCurrentBranch })
        assertTrue(dag.refs.all { it.commit in position })

        val engine = LaneLayoutEngine()
        dag.commits.forEach(engine::add)
        val snapshot = engine.snapshot(complete = true)
        assertEquals(2_000, snapshot.rowCount)
        assertTrue(snapshot.maxLanes > 1)
    }

    @Test
    fun sameSeedIsDeterministic() {
        val first = SyntheticDagGenerator(seed = 7, commitCount = 500).generate()
        val second = SyntheticDagGenerator(seed = 7, commitCount = 500).generate()

        assertEquals(first, second)
        assertNotEquals(first, SyntheticDagGenerator(seed = 8, commitCount = 500).generate())
    }
}
