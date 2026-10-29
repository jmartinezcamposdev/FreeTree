package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.StatusKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CliGitBackendWritesTest {
    private lateinit var repo: GitTestRepo
    private lateinit var backend: CliGitBackend

    @BeforeTest
    fun setUp() = runBlocking {
        repo = GitTestRepo.create()
        backend = CliGitBackend.open(repo.root, repo.gitExecutable)
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun stageAndUnstageTrackedModification() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("a.txt", "two\n")

        assertOk(backend.stage(listOf("a.txt")))
        var entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals('M', entry.index)
        assertEquals(' ', entry.worktree)

        assertOk(backend.unstage(listOf("a.txt")))
        entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals(' ', entry.index)
        assertEquals('M', entry.worktree)
        assertEquals("two\n", Files.readString(repo.root.resolve("a.txt")))
    }

    @Test
    fun stageAndUnstageDeletion() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.delete("a.txt")

        assertOk(backend.stage(listOf("a.txt")))
        var entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals('D', entry.index)
        assertEquals(' ', entry.worktree)

        assertOk(backend.unstage(listOf("a.txt")))
        entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals(' ', entry.index)
        assertEquals('D', entry.worktree)
    }

    @Test
    fun unstageAndDiscardStagedInUnbornRepository() = runBlocking {
        val unborn = GitTestRepo.create()
        val unbornBackend = CliGitBackend.open(unborn.root, unborn.gitExecutable)
        try {
            unborn.write("new.txt", "new\n")
            assertOk(unbornBackend.stage(listOf("new.txt")))
            assertEquals('A', unbornBackend.status().entries.single().index)

            assertOk(unbornBackend.unstage(listOf("new.txt")))
            val entry = unbornBackend.status().entries.single()
            assertEquals(StatusKind.UNTRACKED, entry.kind)
            assertTrue(Files.exists(unborn.root.resolve("new.txt")))

            assertOk(unbornBackend.stage(listOf("new.txt")))
            assertOk(unbornBackend.discardStaged(listOf("new.txt")))
            val afterDiscard = unbornBackend.status().entries.single()
            assertEquals(StatusKind.UNTRACKED, afterDiscard.kind)
            assertTrue(Files.exists(unborn.root.resolve("new.txt")))
            assertTrue(unborn.git("ls-files").isBlank())
        } finally {
            unborn.close()
        }
    }

    @Test
    fun unstageStagedNewFileWithUnstagedChangesInUnbornRepository() = runBlocking {
        val unborn = GitTestRepo.create()
        val unbornBackend = CliGitBackend.open(unborn.root, unborn.gitExecutable)
        try {
            unborn.write("new.txt", "staged\n")
            assertOk(unbornBackend.stage(listOf("new.txt")))
            unborn.write("new.txt", "staged\nworktree\n")

            assertOk(unbornBackend.unstage(listOf("new.txt")))

            val entry = unbornBackend.status().entries.single()
            assertEquals(StatusKind.UNTRACKED, entry.kind)
            assertEquals("staged\nworktree\n", Files.readString(unborn.root.resolve("new.txt")))
            assertTrue(unborn.git("ls-files").isBlank())
        } finally {
            unborn.close()
        }
    }

    @Test
    fun unstageStagedNewFileWithUnstagedChangesKeepsWorktree() = runBlocking {
        repo.write("base.txt", "base\n")
        repo.commitAll("root")
        repo.write("new.txt", "staged\n")
        assertOk(backend.stage(listOf("new.txt")))
        repo.write("new.txt", "staged\nworktree\n")

        assertOk(backend.unstage(listOf("new.txt")))

        val entry = backend.status().entries.single { it.path == "new.txt" }
        assertEquals(StatusKind.UNTRACKED, entry.kind)
        assertEquals("staged\nworktree\n", Files.readString(repo.root.resolve("new.txt")))
        assertEquals("base.txt\n", repo.git("ls-files"))
    }

    @Test
    fun discardStagedNewFileWithUnstagedChangesInUnbornRepository() = runBlocking {
        val unborn = GitTestRepo.create()
        val unbornBackend = CliGitBackend.open(unborn.root, unborn.gitExecutable)
        try {
            unborn.write("new.txt", "staged\n")
            assertOk(unbornBackend.stage(listOf("new.txt")))
            unborn.write("new.txt", "staged\nworktree\n")

            assertOk(unbornBackend.discardStaged(listOf("new.txt")))

            val entry = unbornBackend.status().entries.single()
            assertEquals(StatusKind.UNTRACKED, entry.kind)
            assertEquals("staged\nworktree\n", Files.readString(unborn.root.resolve("new.txt")))
            assertTrue(unborn.git("ls-files").isBlank())
        } finally {
            unborn.close()
        }
    }

    @Test
    fun stageRenamePassesBothPaths() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.write("old.txt", lines)
        repo.commitAll("root")
        repo.git("mv", "old.txt", "new.txt")
        repo.write("new.txt", lines.replace("line 10", "line 10 changed"))

        assertOk(backend.stage(listOf("new.txt")))
        val entries = backend.status().entries
        val renamed = entries.single { it.kind == StatusKind.RENAMED }
        assertEquals("new.txt", renamed.path)
        assertEquals("old.txt", renamed.origPath)
        assertEquals(' ', renamed.worktree)
        assertTrue(entries.none { it.path == "old.txt" })
    }

    @Test
    fun unstageRenameRestoresOldPathAndLeavesNewFileUntracked() = runBlocking {
        repo.write("old.txt", "one\n")
        repo.commitAll("root")
        repo.git("mv", "old.txt", "new.txt")

        assertOk(backend.unstage(listOf("new.txt")))

        val entries = backend.status().entries.associateBy { it.path }
        assertEquals(' ', entries.getValue("old.txt").index)
        assertEquals('D', entries.getValue("old.txt").worktree)
        assertEquals(StatusKind.UNTRACKED, entries.getValue("new.txt").kind)
        assertEquals("old.txt\n", repo.git("ls-files"))
        assertTrue(Files.exists(repo.root.resolve("new.txt")))
        assertFalse(Files.exists(repo.root.resolve("old.txt")))
    }

    @Test
    fun pathsStartingWithDashAreTreatedAsPaths() = runBlocking {
        repo.write("keep.txt", "keep\n")
        repo.commitAll("root")
        repo.write("-dash.txt", "dash\n")

        assertOk(backend.stage(listOf("-dash.txt")))
        assertEquals('A', backend.status().entries.single { it.path == "-dash.txt" }.index)
        assertOk(backend.unstage(listOf("-dash.txt")))
        assertEquals(StatusKind.UNTRACKED, backend.status().entries.single { it.path == "-dash.txt" }.kind)
    }

    @Test
    fun emptyPathListsFailWithoutRunningGit() = runBlocking {
        assertTrue(backend.stage(emptyList()) is GitResult.Failure)
        assertTrue(backend.unstage(emptyList()) is GitResult.Failure)
        assertTrue(backend.discardStaged(emptyList()) is GitResult.Failure)
        assertTrue(backend.discardUnstaged(emptyList()) is GitResult.Failure)
    }

    @Test
    fun discardStagedTrackedRestoresIndexAndKeepsWorktree() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("a.txt", "two\n")
        assertOk(backend.stage(listOf("a.txt")))

        assertOk(backend.discardStaged(listOf("a.txt")))

        val entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals(' ', entry.index)
        assertEquals('M', entry.worktree)
        assertEquals("two\n", Files.readString(repo.root.resolve("a.txt")))
        assertEquals("one\n", repo.git("show", "HEAD:a.txt"))
        assertEquals(0, repo.gitResult("diff", "--cached", "--quiet").exitCode)
    }

    @Test
    fun discardStagedNewFileKeepsItOnDisk() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("new file.txt", "new\n")
        assertOk(backend.stage(listOf("new file.txt")))

        assertOk(backend.discardStaged(listOf("new file.txt")))

        val entry = backend.status().entries.single { it.path == "new file.txt" }
        assertEquals(StatusKind.UNTRACKED, entry.kind)
        assertTrue(Files.exists(repo.root.resolve("new file.txt")))
        assertFalse(repo.git("ls-files").contains("new file.txt"))
    }

    @Test
    fun discardStagedNewFileWithUnstagedChangesKeepsTheWorktree() = runBlocking {
        repo.write("base.txt", "base\n")
        repo.commitAll("root")
        repo.write("new.txt", "staged\n")
        assertOk(backend.stage(listOf("new.txt")))
        repo.write("new.txt", "staged\nworktree\n")

        assertOk(backend.discardStaged(listOf("new.txt")))

        val entry = backend.status().entries.single { it.path == "new.txt" }
        assertEquals(StatusKind.UNTRACKED, entry.kind)
        assertEquals("staged\nworktree\n", Files.readString(repo.root.resolve("new.txt")))
        assertEquals("base.txt\n", repo.git("ls-files"))
    }

    @Test
    fun discardStagedNewFileDeletedFromDiskRemovesTheIndexEntry() = runBlocking {
        repo.write("base.txt", "base\n")
        repo.commitAll("root")
        repo.write("new.txt", "staged\n")
        assertOk(backend.stage(listOf("new.txt")))
        repo.delete("new.txt")

        assertOk(backend.discardStaged(listOf("new.txt")))

        assertTrue(backend.status().entries.isEmpty())
        assertFalse(Files.exists(repo.root.resolve("new.txt")))
        assertEquals("base.txt\n", repo.git("ls-files"))
    }

    @Test
    fun discardStagedRenameWithUnstagedChangesRestoresTheOldPath() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.write("old.txt", lines)
        repo.commitAll("root")
        repo.git("mv", "old.txt", "new.txt")
        repo.write("new.txt", lines.replace("line 10", "line 10 changed"))

        assertOk(backend.discardStaged(listOf("new.txt")))

        assertEquals("old.txt\n", repo.git("ls-files"))
        assertEquals(lines, repo.git("show", ":old.txt"))
        assertFalse(Files.exists(repo.root.resolve("old.txt")))
        assertEquals(
            lines.replace("line 10", "line 10 changed"),
            Files.readString(repo.root.resolve("new.txt")),
        )
        val entries = backend.status().entries.associateBy { it.path }
        assertEquals('D', entries.getValue("old.txt").worktree)
        assertEquals(StatusKind.UNTRACKED, entries.getValue("new.txt").kind)
    }

    @Test
    fun discardStagedRenameRestoresOldPathAndRemovesNewIndexEntry() = runBlocking {
        repo.write("old.txt", "one\n")
        repo.commitAll("root")
        repo.git("mv", "old.txt", "new.txt")

        assertOk(backend.discardStaged(listOf("new.txt")))

        val entries = backend.status().entries.associateBy { it.path }
        assertEquals(' ', entries.getValue("old.txt").index)
        assertEquals('D', entries.getValue("old.txt").worktree)
        assertEquals(StatusKind.UNTRACKED, entries.getValue("new.txt").kind)
        assertTrue(Files.exists(repo.root.resolve("new.txt")))
        assertFalse(Files.exists(repo.root.resolve("old.txt")))
        assertEquals("old.txt\n", repo.git("ls-files"))
    }

    @Test
    fun discardStagedDeletionRestoresIndexEntry() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.delete("a.txt")
        assertOk(backend.stage(listOf("a.txt")))

        assertOk(backend.discardStaged(listOf("a.txt")))

        val entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals(' ', entry.index)
        assertEquals('D', entry.worktree)
        assertFalse(Files.exists(repo.root.resolve("a.txt")))
    }

    @Test
    fun discardUnstagedTrackedRestoresFromIndex() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("a.txt", "two\n")
        assertOk(backend.stage(listOf("a.txt")))
        repo.write("a.txt", "three\n")

        assertOk(backend.discardUnstaged(listOf("a.txt")))

        assertEquals("two\n", Files.readString(repo.root.resolve("a.txt")))
        val entry = backend.status().entries.single { it.path == "a.txt" }
        assertEquals('M', entry.index)
        assertEquals(' ', entry.worktree)
        assertEquals("two\n", repo.git("show", ":a.txt"))
    }

    @Test
    fun discardUnstagedDeletionRestoresFile() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.delete("a.txt")

        assertOk(backend.discardUnstaged(listOf("a.txt")))

        assertEquals("one\n", Files.readString(repo.root.resolve("a.txt")))
        assertTrue(backend.status().entries.isEmpty())
    }

    @Test
    fun discardUnstagedUntrackedDeletesFilesAndDirectories() = runBlocking {
        repo.write("keep.txt", "keep\n")
        repo.commitAll("root")
        repo.write("untracked.txt", "u\n")
        repo.write("untracked dir/one.txt", "one\n")
        repo.write("untracked dir/two.txt", "two\n")

        assertOk(backend.discardUnstaged(listOf("untracked.txt")))
        assertFalse(Files.exists(repo.root.resolve("untracked.txt")))

        assertOk(backend.discardUnstaged(listOf("untracked dir/one.txt", "untracked dir/two.txt")))
        assertFalse(Files.exists(repo.root.resolve("untracked dir/one.txt")))
        assertFalse(Files.exists(repo.root.resolve("untracked dir/two.txt")))
        assertTrue(backend.status().entries.isEmpty())
    }

    @Test
    fun symlinksAreNotFollowedForDiffSizeOrDeletion() = runBlocking {
        if (isWindows()) return@runBlocking
        repo.write("target.txt", "target\n")
        repo.commitAll("root")
        repo.write("untracked-target.txt", "do not delete\n")
        val link = repo.root.resolve("link")
        Files.createSymbolicLink(link, Path.of("untracked-target.txt"))

        val diff = backend.diff(DiffTarget.Unstaged("link"))
        assertFalse(diff.binary)
        assertTrue(diff.lines.single().text.contains("Symbolic link"))
        assertTrue(diff.lines.single().text.contains("untracked-target.txt"))

        assertOk(backend.discardUnstaged(listOf("link")))
        assertFalse(Files.exists(link))
        assertEquals("do not delete\n", Files.readString(repo.root.resolve("untracked-target.txt")))
    }

    @Test
    fun discardRejectsUnmergedPaths() = runBlocking {
        createConflict(repo)
        val before = repo.git("status", "--porcelain=v1")

        val staged = backend.discardStaged(listOf("conflict.txt"))
        val unstaged = backend.discardUnstaged(listOf("conflict.txt"))

        assertTrue(staged is GitResult.Failure)
        assertTrue(unstaged is GitResult.Failure)
        assertEquals(before, repo.git("status", "--porcelain=v1"))
    }

    @Test
    fun discardUnstagedDoesNotDeleteUnknownPaths() = runBlocking {
        repo.write("keep.txt", "keep\n")
        repo.commitAll("root")

        val result = backend.discardUnstaged(listOf("missing.txt"))

        assertTrue(result is GitResult.Failure)
        assertFalse(Files.exists(repo.root.resolve("missing.txt")))
    }

    @Test
    fun commitRequiresMessageAndStagedChanges() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("a.txt", "two\n")

        assertTrue(backend.commit("   ") is GitResult.Failure)
        assertTrue(backend.commit("message") is GitResult.Failure)

        assertOk(backend.stage(listOf("a.txt")))
        assertOk(backend.commit("message"))
        assertEquals("message\n", backend.commitDetail(repo.head()).message)
    }

    @Test
    fun commitWorksInUnbornRepositoryWithMultilineUnicodeMessage() = runBlocking {
        val unborn = GitTestRepo.create()
        val unbornBackend = CliGitBackend.open(unborn.root, unborn.gitExecutable)
        try {
            unborn.write("first.txt", "first\n")
            assertOk(unbornBackend.stage(listOf("first.txt")))
            assertOk(unbornBackend.commit("subject ünïcode\n\nbody line\nsecond body line"))

            val head = unbornBackend.head()
            assertNotNull(head.commit)
            val detail = unbornBackend.commitDetail(head.commit)
            assertTrue(detail.message.startsWith("subject ünïcode\n\nbody line\nsecond body line"))
            assertEquals("first.txt\n", unborn.git("ls-tree", "-r", "--name-only", "HEAD"))
        } finally {
            unborn.close()
        }
    }

    @Test
    fun commitHookFailurePreservesOutput() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        repo.write("a.txt", "two\n")
        assertOk(backend.stage(listOf("a.txt")))
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        Files.createDirectories(hook.parent)
        Files.writeString(hook, "#!/bin/sh\necho 'hook rejected the commit' >&2\nexit 1\n")
        Files.setPosixFilePermissions(
            hook,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val result = backend.commit("message")

        assertTrue(result is GitResult.Failure)
        assertTrue(result.stderr.contains("hook rejected the commit"), result.stderr)
        assertEquals("one\n", repo.git("show", "HEAD:a.txt"))
    }

    @Test
    fun pushAndPullAgainstLocalBareRepository() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        val bare = GitTestRepo.createBare()
        val other = GitTestRepo.create()
        try {
            GitTestRepo.addOrigin(repo, bare)
            GitTestRepo.addOrigin(other, bare)

            val noUpstream = backend.push()
            assertTrue(noUpstream is GitResult.Failure)
            assertTrue(noUpstream.stderr.isNotBlank())

            repo.git("push", "-q", "-u", "origin", "main")
            assertOk(backend.push())
            assertEquals(repo.head(), bare.revParse("refs/heads/main"))

            repo.write("a.txt", "two\n")
            repo.commitAll("second")
            assertOk(backend.push())
            assertEquals(repo.head(), bare.revParse("refs/heads/main"))

            other.git("fetch", "-q", "origin")
            other.git("checkout", "-q", "-B", "main", "origin/main")
            other.write("remote.txt", "remote\n")
            other.commitAll("remote change")
            other.git("push", "-q", "origin", "main")

            assertOk(backend.pull())
            assertEquals(other.head(), repo.head())
            assertEquals("remote\n", Files.readString(repo.root.resolve("remote.txt")))
        } finally {
            other.close()
            bare.close()
        }
    }

    @Test
    fun rejectedPushAndDivergentPullPreserveGitStderr() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        val bare = GitTestRepo.createBare()
        val other = GitTestRepo.create()
        try {
            GitTestRepo.addOrigin(repo, bare)
            GitTestRepo.addOrigin(other, bare)
            repo.git("push", "-q", "-u", "origin", "main")

            other.git("fetch", "-q", "origin")
            other.git("checkout", "-q", "-B", "main", "origin/main")
            other.write("remote.txt", "remote\n")
            other.commitAll("remote")
            other.git("push", "-q", "origin", "main")

            repo.write("local.txt", "local\n")
            repo.commitAll("local")
            val rejected = backend.push()
            assertTrue(rejected is GitResult.Failure)
            assertTrue(rejected.stderr.contains("reject") || rejected.stderr.contains("fetch first"), rejected.stderr)

            val pull = backend.pull()
            assertTrue(pull is GitResult.Failure)
            assertTrue(
                pull.stderr.contains("reconcil", ignoreCase = true) ||
                    pull.stderr.contains("fast-forward", ignoreCase = true) ||
                    pull.stderr.contains("diverg", ignoreCase = true) ||
                    pull.stderr.contains("CONFLICT"),
                pull.stderr,
            )
        } finally {
            other.close()
            bare.close()
        }
    }

    @Test
    fun pushWithoutRemoteFailsWithGitMessage() = runBlocking {
        repo.write("a.txt", "one\n")
        repo.commitAll("root")

        val result = backend.push()

        assertTrue(result is GitResult.Failure)
        assertTrue(result.stderr.isNotBlank())
        assertTrue(result.exitCode != 0)
    }

    private fun createConflict(repo: GitTestRepo) {
        repo.write("conflict.txt", "base\n")
        repo.commitAll("base")
        repo.git("checkout", "-q", "-b", "other")
        repo.write("conflict.txt", "other\n")
        repo.git("commit", "-q", "-am", "other")
        repo.git("checkout", "-q", "main")
        repo.write("conflict.txt", "main\n")
        repo.git("commit", "-q", "-am", "main")
        repo.gitResult("merge", "other")
        check(repo.git("status", "--porcelain=v1").contains("UU")) { "merge did not conflict" }
    }

    private fun assertOk(result: GitResult) {
        assertTrue(result is GitResult.Ok, (result as? GitResult.Failure)?.stderr)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")
}
