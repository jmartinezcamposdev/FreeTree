package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DiscardAcceptanceTest {
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
    fun discardStagedTrackedRestoresIndexAndKeepsWorktree() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        repo.writeFile("a.txt", "two\n")
        repo.stage("a.txt").requireSuccess()

        assertOk(backend.discardStaged(listOf("a.txt")))

        assertEquals("two\n", repo.readFile("a.txt"))
        assertTrue(repo.indexMatchesHead())
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "a.txt" }
        assertEquals('.', entry.index)
        assertEquals('M', entry.worktree)
    }

    @Test
    fun discardStagedNewFileLeavesItUntrackedOnDisk() = runBlocking {
        repo.commitFile("base.txt", "base\n", "root")
        repo.createUntrackedFile("new file.txt", "new\n")
        repo.stage("new file.txt").requireSuccess()

        assertOk(backend.discardStaged(listOf("new file.txt")))

        assertTrue(repo.exists("new file.txt"))
        assertEquals("base.txt", repo.gitOk("ls-files"))
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "new file.txt" }
        assertEquals(StatusKind.UNTRACKED, entry.kind)
    }

    @Test
    fun discardStagedRenameRestoresOldPathAndLeavesNewFileUntracked() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.writeFile("old.txt", lines)
        repo.commitAll("root")
        repo.renameTracked("old.txt", "new.txt").requireSuccess()

        assertOk(backend.discardStaged(listOf("new.txt")))

        assertEquals("old.txt", repo.gitOk("ls-files"))
        assertEquals(lines, repo.git("show", ":old.txt").requireSuccess().stdout)
        assertTrue(repo.exists("new.txt"))
        assertFalse(repo.exists("old.txt"))
        val entries = assertStatusMatches(repo, backend).entries.associateBy { it.path }
        assertEquals('D', entries.getValue("old.txt").worktree)
        assertEquals(StatusKind.UNTRACKED, entries.getValue("new.txt").kind)
    }

    @Test
    fun discardUnstagedTrackedRestoresFromIndexAndKeepsIndex() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        repo.writeFile("a.txt", "two\n")
        repo.stage("a.txt").requireSuccess()
        repo.writeFile("a.txt", "three\n")

        assertOk(backend.discardUnstaged(listOf("a.txt")))

        assertEquals("two\n", repo.readFile("a.txt"))
        assertEquals("two\n", repo.git("show", ":a.txt").requireSuccess().stdout)
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "a.txt" }
        assertEquals('M', entry.index)
        assertEquals('.', entry.worktree)
    }

    @Test
    fun discardUnstagedUntrackedFilesDeletesThem() = runBlocking {
        repo.commitFile("base.txt", "base\n", "root")
        repo.createUntrackedFile("untracked.txt", "u\n")
        repo.createUntrackedFile("untracked dir/one.txt", "one\n")
        repo.createUntrackedFile("untracked dir/two.txt", "two\n")

        assertOk(backend.discardUnstaged(listOf("untracked.txt")))
        assertOk(backend.discardUnstaged(listOf("untracked dir/one.txt", "untracked dir/two.txt")))

        assertFalse(repo.exists("untracked.txt"))
        assertFalse(repo.exists("untracked dir/one.txt"))
        assertFalse(repo.exists("untracked dir/two.txt"))
        assertTrue(assertStatusMatches(repo, backend).entries.isEmpty())
    }

    @Test
    fun discardStagedOnBothAreasKeepsWorktree() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        repo.writeFile("a.txt", "two\n")
        repo.stage("a.txt").requireSuccess()
        repo.writeFile("a.txt", "three\n")

        assertOk(backend.discardStaged(listOf("a.txt")))

        assertEquals("three\n", repo.readFile("a.txt"))
        assertEquals("one\n", repo.git("show", ":a.txt").requireSuccess().stdout)
        assertTrue(repo.indexMatchesHead())
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "a.txt" }
        assertEquals('.', entry.index)
        assertEquals('M', entry.worktree)
    }

    @Test
    fun discardUnstagedOnBothAreasKeepsIndex() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        repo.writeFile("a.txt", "two\n")
        repo.stage("a.txt").requireSuccess()
        repo.writeFile("a.txt", "three\n")

        assertOk(backend.discardUnstaged(listOf("a.txt")))

        assertEquals("two\n", repo.readFile("a.txt"))
        assertEquals("two\n", repo.git("show", ":a.txt").requireSuccess().stdout)
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "a.txt" }
        assertEquals('M', entry.index)
        assertEquals('.', entry.worktree)
    }

    @Test
    fun discardStagedDeletionRestoresIndexEntry() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        repo.deleteFile("a.txt")
        repo.stage("a.txt").requireSuccess()

        assertOk(backend.discardStaged(listOf("a.txt")))

        assertEquals("one\n", repo.git("show", ":a.txt").requireSuccess().stdout)
        assertFalse(repo.exists("a.txt"))
        assertTrue(repo.indexMatchesHead())
        val entry = assertStatusMatches(repo, backend).entries.single { it.path == "a.txt" }
        assertEquals('.', entry.index)
        assertEquals('D', entry.worktree)
    }

    @Test
    fun discardRejectsUnmergedPathsWithoutChangingStatus() = runBlocking {
        createConflict()

        val before = repo.statusV1()
        val staged = backend.discardStaged(listOf("conflict.txt"))
        val unstaged = backend.discardUnstaged(listOf("conflict.txt"))

        assertFailure(staged)
        assertFailure(unstaged)
        assertEquals(before, repo.statusV1())
        assertEquals(StatusKind.UNMERGED, backend.status().entries.single { it.path == "conflict.txt" }.kind)
    }

    private fun createConflict() {
        repo.writeFile("conflict.txt", "base\n")
        repo.commitAll("base")
        repo.checkoutNewBranch("other").requireSuccess()
        repo.writeFile("conflict.txt", "other\n")
        repo.commitAll("other")
        repo.checkout("main").requireSuccess()
        repo.writeFile("conflict.txt", "main\n")
        repo.commitAll("main")
        val merge = repo.merge("other")
        assertFalse(merge.succeeded)
        assertTrue(repo.git("status", "--porcelain=v1").stdout.contains("UU"))
    }
}
