package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class GitLogAndRefsAcceptanceTest {
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
    fun logMatchesRevListAcrossBranchesRootsAndMerge() = runBlocking {
        repo.commitFile("main.txt", "main\n", "main root")
        repo.checkoutNewBranch("feature/x").requireSuccess()
        val feature = repo.commitFile("feature.txt", "feature\n", "feature commit")
        repo.checkout("main").requireSuccess()
        repo.commitFile("main2.txt", "main2\n", "main commit")
        repo.merge("feature/x").requireSuccess()
        val merge = repo.headHash()

        repo.git("checkout", "--orphan", "orphan").requireSuccess()
        repo.git("rm", "-rf", ".").requireSuccess()
        val orphan = repo.commit("orphan root", allowEmpty = true)
        repo.checkout("main").requireSuccess()

        val revs = listOf("refs/heads/main", "refs/heads/feature/x", "refs/heads/orphan")
        val expected = parseRevListParents(
            repo.git("rev-list", "--parents", "--topo-order", *revs.toTypedArray())
                .requireSuccess()
                .stdout,
        )
        val actual = backend.logStream(LogSpec(revs = revs, includeHead = false)).toList()

        assertEquals(expected.map { it.first }, actual.map { it.hash })
        assertEquals(expected.map { it.second }, actual.map { it.parents })
        assertEquals(2, actual.single { it.hash == merge }.parents.size)
        assertEquals(emptyList(), actual.single { it.hash == orphan }.parents)
        assertTrue(actual.any { it.hash == feature })
    }

    @Test
    fun headInclusionUsesDetachedHeadAsOracle() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        val root = repo.headHash()
        repo.checkoutNewBranch("feature/x").requireSuccess()
        repo.commitFile("feature.txt", "feature\n", "feature")
        repo.checkout("main").requireSuccess()
        repo.git("checkout", "--detach", root).requireSuccess()

        val expected = parseRevListParents(
            repo.git("rev-list", "--parents", "--topo-order", "HEAD").requireSuccess().stdout,
        )
        val actual = backend.logStream(LogSpec(revs = emptyList(), includeHead = true)).toList()
        val head = backend.head()

        assertEquals(root, head.commit)
        assertNull(head.branchShortName)
        assertEquals(expected.map { it.first }, actual.map { it.hash })
        assertEquals(expected.map { it.second }, actual.map { it.parents })
    }

    @Test
    fun refsMatchShowRefForTagsSymrefsRemoteAndUpstream() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        val root = repo.headHash()
        repo.createBranch("feature/x").requireSuccess()
        repo.git("tag", "-a", "v1.0", "-m", "release", root).requireSuccess()
        repo.git("tag", "light", root).requireSuccess()
        val tree = repo.gitOk("rev-parse", "$root^{tree}")
        repo.git("tag", "tree-tag", tree).requireSuccess()
        repo.createBareOrigin(name = "upstream")
        repo.git("update-ref", "refs/remotes/upstream/main", root).requireSuccess()
        repo.git("update-ref", "refs/remotes/upstream/feature/x", root).requireSuccess()
        repo.git("symbolic-ref", "refs/remotes/upstream/HEAD", "refs/remotes/upstream/main").requireSuccess()
        repo.git("branch", "--set-upstream-to=upstream/main", "main").requireSuccess()

        val expected = repo.refsFromShowRef()
        val actual = backend.refs().sortedBy { it.fullName }

        assertEquals(expected, actual)
        assertEquals(
            setOf("refs/heads/feature/x", "refs/heads/main", "refs/remotes/upstream/feature/x", "refs/remotes/upstream/main", "refs/tags/light", "refs/tags/v1.0"),
            actual.map { it.fullName }.toSet(),
        )
        assertEquals(root, actual.single { it.fullName == "refs/tags/v1.0" }.commit)
        assertEquals(null, actual.firstOrNull { it.fullName == "refs/tags/tree-tag" })

        repo.git("checkout", "--detach", root).requireSuccess()
        val detached = backend.head()
        assertEquals(root, detached.commit)
        assertNull(detached.branchShortName)
    }

    @Test
    fun refsSurviveGitGcAndStillMatchShowRef() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        val root = repo.headHash()
        repo.createBranch("feature/after-gc").requireSuccess()
        repo.git("tag", "-a", "v2.0", "-m", "release", root).requireSuccess()
        repo.git("update-ref", "refs/remotes/upstream/main", root).requireSuccess()

        repo.git("gc", "-q", "--prune=now").requireSuccess()
        assertTrue(java.nio.file.Files.exists(repo.gitDir.resolve("packed-refs")), "git gc must pack refs")

        val expected = repo.refsFromShowRef()
        val actual = backend.refs().sortedBy { it.fullName }
        assertEquals(expected, actual)
        assertEquals(root, actual.single { it.fullName == "refs/tags/v2.0" }.commit)
    }

    @Test
    fun unbornRepositoryHasNoRefsOrCommits() = runBlocking {
        GitTestRepo.create().use { empty ->
            val emptyBackend = empty.openBackend()
            val rawShowRef = empty.git("show-ref")

            assertTrue(emptyBackend.refs().isEmpty())
            assertEquals(1, rawShowRef.exitCode)
            assertTrue(rawShowRef.stdout.isBlank())
            assertNull(emptyBackend.head().commit)
            assertEquals("main", emptyBackend.head().branchShortName)
            assertTrue(emptyBackend.logStream(LogSpec(emptyList(), includeHead = true)).toList().isEmpty())
        }
    }
}
