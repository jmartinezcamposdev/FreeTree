package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GitWritesAcceptanceTest {
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
    fun stageUnstageAndCommitMatchGitStatusAndCachedIndex() = runBlocking {
        repo.writeFile("keep.txt", "one\n")
        repo.writeFile("delete.txt", "delete\n")
        repo.commitAll("root")
        val previous = repo.headHash()

        repo.writeFile("keep.txt", "two\n")
        repo.deleteFile("delete.txt")
        repo.writeFile("added.txt", "added\n")

        assertOk(backend.stage(listOf("keep.txt", "delete.txt", "added.txt")))
        assertStatusMatches(repo, backend)
        assertStagedMatchesStatus(repo, backend)
        assertEquals(setOf("keep.txt", "delete.txt", "added.txt"), repo.stagedPaths())

        assertOk(backend.unstage(listOf("delete.txt")))
        assertStatusMatches(repo, backend)
        assertStagedMatchesStatus(repo, backend)
        assertEquals(setOf("keep.txt", "added.txt"), repo.stagedPaths())

        assertOk(backend.stage(listOf("delete.txt")))
        assertStatusMatches(repo, backend)
        assertStagedMatchesStatus(repo, backend)

        val message = "acceptance subject ünïcode\n\nbody line"
        assertOk(backend.commit(message))
        val head = repo.headHash()
        assertEquals(previous, repo.gitOk("show", "-s", "--format=%P", head))
        assertEquals(message, repo.git("log", "-1", "--format=%B", head).stdout.trimEnd())
        assertTrue(repo.statusV1().isEmpty())
        assertTrue(repo.stagedPaths().isEmpty())
        assertEquals("two\n", repo.readFile("keep.txt"))
        assertEquals("added\n", repo.readFile("added.txt"))
    }

    @Test
    fun stageAndUnstageRenameMatchGitIndex() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.writeFile("old.txt", lines)
        repo.commitAll("root")
        repo.renameTracked("old.txt", "new.txt").requireSuccess()

        assertOk(backend.unstage(listOf("new.txt")))
        assertStatusMatches(repo, backend)
        assertStagedMatchesStatus(repo, backend)
        assertEquals("", repo.gitOk("diff", "--cached", "--name-only"))
        assertEquals("old.txt", repo.gitOk("ls-files"))
        assertTrue(repo.exists("new.txt"))
        assertTrue(!repo.exists("old.txt"))

        assertOk(backend.stage(listOf("old.txt", "new.txt")))
        assertStatusMatches(repo, backend)
        assertStagedMatchesStatus(repo, backend)
        assertEquals("new.txt", backend.status().entries.single { it.index == 'R' }.path)
        assertEquals("old.txt", backend.status().entries.single { it.index == 'R' }.origPath)
    }

    @Test
    fun commitWithoutStagedChangesFails() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        repo.writeFile("a.txt", "changed\n")

        assertFailure(backend.commit("message"))
        assertEquals("a\n", repo.git("show", "HEAD:a.txt").requireSuccess().stdout)
    }
}
