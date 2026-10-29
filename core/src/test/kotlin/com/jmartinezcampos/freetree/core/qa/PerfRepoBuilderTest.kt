package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.testing.PerfRepoBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class PerfRepoBuilderTest {

    @Test
    fun smokeBuildIsDeterministicAndCacheable() = runBlocking {
        val firstParent = Files.createTempDirectory("freetree-perf-test-")
        val secondParent = Files.createTempDirectory("freetree-perf-test-")
        val firstRoot = firstParent.resolve("repo")
        val secondRoot = secondParent.resolve("repo")
        try {
            val builder = builder(firstRoot)
            val first = builder.buildIfNeeded()
            assertFalse(first.fromCache)
            assertEquals(240, first.commitCount)

            val backend = CliGitBackend.open(first.path)
            assertEquals(first.head, backend.head().commit)
            val refs = backend.refs()
            assertEquals(7, refs.count { it.kind == RefKind.LOCAL_BRANCH })
            assertEquals(5, refs.count { it.kind == RefKind.TAG })

            val commits = backend.logStream(
                LogSpec(revs = listOf("refs/heads/main"), includeHead = false),
            ).toList()
            assertEquals(240, commits.size)
            assertTrue(commits.any { it.parents.size >= 2 })
            assertTrue(commits.count { it.parents.size >= 2 } >= 20)

            val cached = builder.buildIfNeeded()
            assertTrue(cached.fromCache)
            assertEquals(first.head, cached.head)

            val rebuilt = builder(secondRoot).build()
            assertFalse(rebuilt.fromCache)
            assertEquals(first.head, rebuilt.head)
        } finally {
            deleteRecursively(firstRoot)
            deleteRecursively(secondRoot)
            Files.deleteIfExists(firstParent.resolve("repo.perf.properties"))
            Files.deleteIfExists(secondParent.resolve("repo.perf.properties"))
            Files.deleteIfExists(firstParent)
            Files.deleteIfExists(secondParent)
        }
    }

    private fun builder(root: Path) = PerfRepoBuilder(
        root = root,
        seed = 99,
        commitCount = 240,
        authorCount = 5,
        branchCount = 6,
        tagCount = 5,
        mergeEvery = 5,
    )

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }
}
