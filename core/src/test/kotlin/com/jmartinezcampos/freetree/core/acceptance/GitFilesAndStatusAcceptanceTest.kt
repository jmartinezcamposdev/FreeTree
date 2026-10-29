package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GitFilesAndStatusAcceptanceTest {
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
    fun changedFilesMatchShowNameStatusForRootRenameDeleteAndMerge() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.writeFile("keep.txt", "keep\n")
        repo.writeFile("delete.txt", "delete\n")
        repo.writeFile("rename old.txt", lines)
        val root = repo.commitAll("root")

        repo.writeFile("keep.txt", "keep changed\n")
        repo.renameFile("rename old.txt", "rename new.txt")
        repo.deleteFile("delete.txt")
        repo.writeFile("added ünïcode.txt", "added\n")
        val changes = repo.commitAll("rename, delete and add")

        assertChangedFilesMatch(root)
        assertChangedFilesMatch(changes)

        val rootChanges = backend.changedFiles(root)
        assertEquals(setOf("keep.txt", "delete.txt", "rename old.txt"), rootChanges.map { it.path }.toSet())
        assertEquals(setOf('A'), rootChanges.map { it.status }.toSet())
        val rename = backend.changedFiles(changes).single { it.path == "rename new.txt" }
        assertEquals('R', rename.status)
        assertEquals("rename old.txt", rename.oldPath)
        assertEquals('D', backend.changedFiles(changes).single { it.path == "delete.txt" }.status)

        repo.checkoutNewBranch("feature").requireSuccess()
        repo.commitFile("feature.txt", "feature\n", "feature")
        repo.checkout("main").requireSuccess()
        repo.commitFile("main-only.txt", "main\n", "main")
        repo.merge("feature").requireSuccess()
        val merge = repo.headHash()

        assertChangedFilesMatch(merge)
        assertEquals(listOf("feature.txt"), backend.changedFiles(merge).map { it.path })
    }

    @Test
    fun statusMatchesPorcelainV1IncludingRenameBothAreasAndUntrackedTree() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.writeFile("tracked.txt", "one\n")
        repo.writeFile("both.txt", "base\n")
        repo.writeFile("deleted.txt", "gone\n")
        repo.writeFile("rename old.txt", lines)
        repo.commitAll("root")

        repo.writeFile("tracked.txt", "tracked changed\n")
        repo.git("mv", "rename old.txt", "rename new.txt").requireSuccess()
        repo.writeFile("both.txt", "staged\n")
        repo.stage("both.txt").requireSuccess()
        repo.writeFile("both.txt", "staged then unstaged\n")
        repo.writeFile("new staged.txt", "new\n")
        repo.stage("new staged.txt").requireSuccess()
        repo.deleteFile("deleted.txt")
        repo.createUntrackedFile("untracked ünïcode.txt")
        repo.createUntrackedFile("untracked dir/one.txt")
        repo.createUntrackedFile("untracked dir/two.txt")

        val status = assertStatusMatches(repo, backend)
        assertEquals(StatusKind.RENAMED, status.entries.single { it.path == "rename new.txt" }.kind)
        assertEquals("rename old.txt", status.entries.single { it.path == "rename new.txt" }.origPath)
        assertEquals(StatusKind.UNTRACKED, status.entries.single { it.path == "untracked ünïcode.txt" }.kind)
        assertTrue(status.entries.any { it.path == "untracked dir/one.txt" })
        assertEquals(StatusKind.UNTRACKED, status.entries.single { it.path == "untracked dir/two.txt" }.kind)
    }

    @Test
    fun statusMatchesPorcelainV1ForUnbornAndStagedDeletion() = runBlocking {
        GitTestRepo.create().use { empty ->
            val emptyBackend = empty.openBackend()
            empty.writeFile("first.txt", "first\n")
            empty.stage("first.txt").requireSuccess()
            assertStatusMatches(empty, emptyBackend)

            empty.commit("first")
            empty.deleteFile("first.txt")
            empty.stage("first.txt").requireSuccess()
            val status = assertStatusMatches(empty, emptyBackend)
            assertEquals('D', status.entries.single { it.path == "first.txt" }.index)
        }
    }

    private suspend fun assertChangedFilesMatch(hash: String) {
        val expected = repo.showNameStatus(hash).associateBy { it.path }
        val actual = backend.changedFiles(hash).associateBy { it.path }

        assertEquals(expected.keys, actual.keys, "changed paths for $hash")
        expected.forEach { (path, change) ->
            val found = actual.getValue(path)
            assertEquals(change.status, found.status, "status for $path")
            assertEquals(change.oldPath, found.oldPath, "old path for $path")
        }
    }
}
