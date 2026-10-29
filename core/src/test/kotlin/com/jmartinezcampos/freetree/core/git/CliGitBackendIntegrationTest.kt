package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusKind
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class CliGitBackendIntegrationTest {
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
    fun refsAndHeadCoverTagsSymrefsAndDetachedHead() = runBlocking {
        repo.write("a.txt", "a\n")
        val root = repo.commitAll("root")
        repo.git("branch", "feature")
        repo.git("tag", "-a", "v1", "-m", "version", root)
        repo.git("tag", "light")
        val tree = repo.revParse("$root^{tree}")
        repo.git("tag", "tree-tag", tree)
        repo.git("update-ref", "refs/remotes/origin/main", root)
        repo.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        val refs = backend.refs()
        assertEquals(
            setOf("refs/heads/main", "refs/heads/feature", "refs/remotes/origin/main", "refs/tags/v1", "refs/tags/light"),
            refs.map { it.fullName }.toSet(),
        )
        val main = refs.single { it.fullName == "refs/heads/main" }
        assertEquals(RefKind.LOCAL_BRANCH, main.kind)
        assertTrue(main.isCurrentBranch)
        assertEquals(root, main.commit)
        assertEquals(root, refs.single { it.fullName == "refs/tags/v1" }.commit)
        assertNull(refs.firstOrNull { it.fullName == "refs/tags/tree-tag" })
        assertNull(refs.firstOrNull { it.fullName == "refs/remotes/origin/HEAD" })

        val head = backend.head()
        assertEquals(root, head.commit)
        assertEquals("main", head.branchShortName)

        repo.git("checkout", "-q", "--detach")
        val detached = backend.head()
        assertEquals(root, detached.commit)
        assertNull(detached.branchShortName)
    }

    @Test
    fun emptyRepositoryHasNoRefsHeadOrCommits() = runBlocking {
        assertTrue(backend.refs().isEmpty())
        val head = backend.head()
        assertNull(head.commit)
        assertEquals("main", head.branchShortName)
        val status = backend.status()
        assertTrue(status.unborn)
        assertNull(status.branchHead)
        assertTrue(status.entries.isEmpty())
        assertTrue(backend.logStream(LogSpec(revs = emptyList(), includeHead = true)).toList().isEmpty())
        assertTrue(backend.logStream(LogSpec(revs = emptyList(), includeHead = false)).toList().isEmpty())
    }

    @Test
    fun logStreamMatchesRevListIncludingOctopusMerge() = runBlocking {
        repo.write("a.txt", "a\n")
        repo.commitAll("root")
        repo.git("checkout", "-q", "-b", "left")
        repo.write("left.txt", "left\n")
        repo.commitAll("left")
        repo.git("checkout", "-q", "-b", "right", "main")
        repo.write("right.txt", "right\n")
        repo.commitAll("right")
        repo.git("checkout", "-q", "main")
        repo.git("merge", "-q", "--no-ff", "-m", "octopus", "left", "right")

        val expected = repo.git("rev-list", "--parents", "--topo-order", "main")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> line.split(' ').let { it.first() to it.drop(1) } }
            .toList()
        val actual = backend.logStream(LogSpec(revs = listOf("refs/heads/main"), includeHead = false)).toList()

        assertEquals(expected.map { it.first }, actual.map { it.hash })
        assertEquals(expected.map { it.second }, actual.map { it.parents })
        assertEquals(3, actual.first { it.subject == "octopus" }.parents.size)
        assertEquals(emptyList(), actual.last().parents)
    }

    @Test
    fun logStreamSupportsFiltersAndHeadInclusion() = runBlocking {
        repo.write("a.txt", "a\n")
        val root = repo.commitAll("root")
        repo.write("a.txt", "a2\n")
        repo.commitAll("fix the parser")
        repo.git("checkout", "-q", "-b", "other", root)
        repo.write("other.txt", "other\n")
        val other = repo.commitAll("other branch")
        repo.git("checkout", "-q", "main")

        val grep = backend.logStream(
            LogSpec(revs = listOf("refs/heads/main"), includeHead = false, grep = "parser"),
        ).toList()
        assertEquals(listOf("fix the parser"), grep.map { it.subject })

        val path = backend.logStream(
            LogSpec(revs = listOf("refs/heads/main"), includeHead = false, path = "other.txt"),
        ).toList()
        assertTrue(path.isEmpty())

        val withHead = backend.logStream(
            LogSpec(revs = listOf("refs/heads/main"), includeHead = true),
        ).toList()
        assertEquals(backend.logStream(LogSpec(listOf("refs/heads/main"), false)).toList().size, withHead.size)

        val otherOnly = backend.logStream(
            LogSpec(revs = listOf("refs/heads/other"), includeHead = true),
        ).toList()
        assertTrue(otherOnly.any { it.hash == other })
    }

    @Test
    fun logStreamThrowsGitExceptionForAnInvalidRevision() = runBlocking {
        repo.write("a.txt", "a\n")
        repo.commitAll("root")

        assertFailsWith<GitException> {
            backend.logStream(LogSpec(revs = listOf("refs/heads/does-not-exist"), includeHead = false)).toList()
        }
        Unit
    }

    @Test
    fun commitDetailAndChangedFilesMatchGit() = runBlocking {
        repo.write("keep.txt", "line 1\nline 2\n")
        repo.write("delete.txt", "delete\n")
        repo.write("rename old.txt", "rename\n")
        repo.write("unicodé/naïve.txt", "naive\n")
        val root = repo.commitAll("root ünïcode\n\nbody line")
        Files.move(repo.root.resolve("rename old.txt"), repo.root.resolve("rename new.txt"))
        repo.delete("delete.txt")
        repo.write("new file.txt", "new\n")
        repo.write("unicodé/naïve.txt", "changed\n")
        repo.write("keep.txt", "line 1\nline 2 changed\n")
        repo.git("add", "-A")
        repo.git("commit", "-q", "-m", "changes\n\nsecond line ünïcode")
        val hash = repo.head()

        val detail = backend.commitDetail(hash)
        assertEquals(hash, detail.hash)
        assertEquals(listOf(root), detail.parents)
        assertEquals("Test User", detail.authorName)
        assertEquals("test@example.com", detail.authorEmail)
        assertEquals("changes\n\nsecond line ünïcode\n", detail.message)
        assertEquals(repo.git("log", "-1", "--format=%at", hash).trim().toLong(), detail.authorTimeSec)

        val changes = backend.changedFiles(hash).associateBy { it.path }
        assertEquals(setOf("keep.txt", "delete.txt", "new file.txt", "rename new.txt", "unicodé/naïve.txt"), changes.keys)
        assertEquals('D', changes.getValue("delete.txt").status)
        assertEquals('A', changes.getValue("new file.txt").status)
        val rename = changes.getValue("rename new.txt")
        assertEquals('R', rename.status)
        assertEquals("rename old.txt", rename.oldPath)

        val rootChanges = backend.changedFiles(root)
        assertEquals(
            repo.git("ls-tree", "-r", "--name-only", root).lineSequence().filter { it.isNotBlank() }.toSet(),
            rootChanges.map { it.path }.toSet(),
        )
        assertTrue(rootChanges.all { it.status == 'A' })
    }

    @Test
    fun changedFilesMatchShowNameStatusOracle() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.write("rename old.txt", lines)
        repo.write("delete.txt", "delete\n")
        repo.write("modify.txt", "before\n")
        repo.write("unchanged.txt", "same\n")
        val root = repo.commitAll("root")

        Files.move(repo.root.resolve("rename old.txt"), repo.root.resolve("rename new.txt"))
        repo.write("rename new.txt", lines.replace("line 5", "line 5 changed"))
        repo.delete("delete.txt")
        repo.write("modify.txt", "after\n")
        repo.write("added.txt", "added\n")
        repo.git("add", "-A")
        repo.git("commit", "-q", "-m", "changes")
        val hash = repo.head()

        val expected = parseNameStatusOracle(
            repo.git("show", "--name-status", "--format=", "-M", "--first-parent", "-z", hash).split('\u0000'),
        )
        val actual = backend.changedFiles(hash)
            .map { Triple(it.status, it.path, it.oldPath) }
            .toSet()
        assertEquals(expected, actual)

        val rootExpected = parseNameStatusOracle(
            repo.git("show", "--name-status", "--format=", "-M", "--first-parent", "-z", root).split('\u0000'),
        )
        val rootActual = backend.changedFiles(root)
            .map { Triple(it.status, it.path, it.oldPath) }
            .toSet()
        assertEquals(rootExpected, rootActual)
    }

    @Test
    fun diffUnstagedAndStagedRenamePassBothPaths() = runBlocking {
        val lines = (1..20).joinToString("\n") { "line $it" } + "\n"
        repo.write("old.txt", lines)
        repo.commitAll("root")
        repo.git("mv", "old.txt", "new.txt")
        repo.write("new.txt", lines.replace("line 10", "line 10 changed"))

        val unstaged = backend.diff(DiffTarget.Unstaged("new.txt"))
        assertTrue(unstaged.lines.any { it.kind == DiffLineKind.ADD && it.text == "line 10 changed" })

        val staged = backend.diff(DiffTarget.Staged("new.txt"))
        assertTrue(staged.lines.any { it.kind == DiffLineKind.FILE_HEADER && it.text.contains("old.txt") })
        assertTrue(staged.lines.any { it.kind == DiffLineKind.FILE_HEADER && it.text.contains("new.txt") })
    }

    @Test
    fun sha256RepositoryComputesItsOwnEmptyTree() = runBlocking {
        val sha256 = GitTestRepo.create(objectFormat = "sha256")
        try {
            sha256.write("a.txt", "a\n")
            val root = sha256.commitAll("root")
            val shaBackend = CliGitBackend.open(sha256.root, sha256.gitExecutable)
            val changes = shaBackend.changedFiles(root)
            assertEquals(listOf("a.txt"), changes.map { it.path })
            val diff = shaBackend.diff(DiffTarget.InCommit(root, changes.single()))
            assertTrue(diff.lines.any { it.kind == DiffLineKind.ADD && it.text == "a" })

            val commits = shaBackend.logStream(
                LogSpec(revs = listOf("refs/heads/main"), includeHead = false),
            ).toList()
            assertEquals(64, commits.single().hash.length)
        } finally {
            sha256.close()
        }
    }

    @Test
    fun mergeChangedFilesUseFirstParent() = runBlocking {
        repo.write("a.txt", "a\n")
        repo.commitAll("root")
        repo.git("checkout", "-q", "-b", "feature")
        repo.write("feature.txt", "feature\n")
        repo.commitAll("feature")
        repo.git("checkout", "-q", "main")
        repo.write("main.txt", "main\n")
        repo.commitAll("main")
        repo.git("merge", "-q", "--no-ff", "-m", "merge", "feature")
        val merge = repo.head()

        val changes = backend.changedFiles(merge)
        assertEquals(listOf("feature.txt"), changes.map { it.path })
        assertEquals('A', changes.single().status)
        assertFalse(changes.any { it.path == "main.txt" })
    }

    @Test
    fun statusMatchesPorcelainV1Oracle() = runBlocking {
        repo.write("tracked.txt", "one\n")
        repo.write("both.txt", "both\n")
        repo.write("deleted.txt", "delete\n")
        repo.write("status old.txt", "old\n")
        repo.commitAll("root")
        repo.write("tracked.txt", "one changed\n")
        repo.git("mv", "status old.txt", "status new.txt")
        repo.write("staged new.txt", "staged\n")
        repo.git("add", "staged new.txt")
        repo.write("both.txt", "staged\n")
        repo.git("add", "both.txt")
        repo.write("both.txt", "staged then unstaged\n")
        repo.delete("deleted.txt")
        repo.write("untracked ünïcode.txt", "u\n")
        if (!isWindows()) repo.write("line\nbreak.txt", "n\n")

        val status = backend.status()
        assertFalse(status.unborn)
        assertEquals(repo.head(), status.branchHead)
        assertEquals(v1Entries(repo), status.entries.map { it.path to "${it.index}${it.worktree}" }.toSet())

        val rename = status.entries.single { it.kind == StatusKind.RENAMED }
        assertEquals("status new.txt", rename.path)
        assertEquals("status old.txt", rename.origPath)
        assertEquals(StatusKind.UNTRACKED, status.entries.single { it.path.startsWith("untracked") }.kind)
    }

    @Test
    fun diffCoversCommitUnstagedStagedUntrackedAndBinary() = runBlocking {
        repo.write("text.txt", "line 1\nline 2\nline 3\n")
        repo.write("binary.bin", byteArrayOf(0, 1, 2, 3, 10))
        repo.write("rename old.txt", "rename me\n")
        repo.write("gone.txt", "gone\n")
        val root = repo.commitAll("root")
        repo.write("text.txt", "line 1\nline 2 changed\nline 3\n")
        Files.move(repo.root.resolve("rename old.txt"), repo.root.resolve("rename new.txt"))
        repo.write("binary.bin", byteArrayOf(0, 9, 9, 9, 10))
        repo.delete("gone.txt")
        repo.git("add", "-A")
        repo.git("commit", "-q", "-m", "changes")
        val hash = repo.head()

        val changes = backend.changedFiles(hash).associateBy { it.path }
        val textDiff = backend.diff(DiffTarget.InCommit(hash, changes.getValue("text.txt")))
        assertTrue(textDiff.lines.any { it.kind == DiffLineKind.ADD && it.text == "line 2 changed" && it.newNo == 2 })
        assertTrue(textDiff.lines.any { it.kind == DiffLineKind.DEL && it.text == "line 2" && it.oldNo == 2 })

        val deletedDiff = backend.diff(DiffTarget.InCommit(hash, changes.getValue("gone.txt")))
        assertTrue(deletedDiff.lines.any { it.kind == DiffLineKind.DEL && it.text == "gone" })

        val renameDiff = backend.diff(DiffTarget.InCommit(hash, changes.getValue("rename new.txt")))
        assertTrue(renameDiff.lines.any { it.kind == DiffLineKind.FILE_HEADER && it.text.contains("rename new.txt") })

        val binaryDiff = backend.diff(DiffTarget.InCommit(hash, changes.getValue("binary.bin")))
        assertTrue(binaryDiff.binary)

        val rootChange = backend.changedFiles(root).single { it.path == "text.txt" }
        val rootDiff = backend.diff(DiffTarget.InCommit(root, rootChange))
        assertTrue(rootDiff.lines.any { it.kind == DiffLineKind.ADD && it.text == "line 1" && it.newNo == 1 })

        repo.write("text.txt", "line 1\nline 2 changed\nline 3\nline 4\n")
        val unstaged = backend.diff(DiffTarget.Unstaged("text.txt"))
        assertTrue(unstaged.lines.any { it.kind == DiffLineKind.ADD && it.text == "line 4" })

        repo.git("add", "text.txt")
        val staged = backend.diff(DiffTarget.Staged("text.txt"))
        assertTrue(staged.lines.any { it.kind == DiffLineKind.ADD && it.text == "line 4" })

        repo.write("untracked file ünïcode.txt", "first\nsecond\n")
        val untracked = backend.diff(DiffTarget.Unstaged("untracked file ünïcode.txt"))
        assertEquals(listOf("first", "second"), untracked.lines.map { it.text })
        assertTrue(untracked.lines.all { it.kind == DiffLineKind.ADD })
        assertEquals(listOf(1, 2), untracked.lines.map { it.newNo })

        repo.git("add", "untracked file ünïcode.txt")
        val newlyStaged = backend.diff(DiffTarget.Staged("untracked file ünïcode.txt"))
        assertEquals(listOf("first", "second"), newlyStaged.lines.filter { it.kind == DiffLineKind.ADD }.map { it.text })
        assertTrue(newlyStaged.lines.any { it.kind == DiffLineKind.FILE_HEADER })

        repo.write("untracked.bin", byteArrayOf(0, 1, 2))
        assertTrue(backend.diff(DiffTarget.Unstaged("untracked.bin")).binary)
    }

    @Test
    fun diffHandlesDeletesStagedDeletesAndUnicodeNewlinePaths() = runBlocking {
        repo.write("delete me.txt", "gone\n")
        if (!isWindows()) repo.write("line\nbreak.txt", "one\ntwo\n")
        repo.commitAll("root")
        repo.delete("delete me.txt")
        if (!isWindows()) repo.write("line\nbreak.txt", "one\ntwo\nthree\n")

        val deleteDiff = backend.diff(DiffTarget.Unstaged("delete me.txt"))
        assertTrue(deleteDiff.lines.any { it.kind == DiffLineKind.DEL && it.text == "gone" })
        if (!isWindows()) {
            val newlineDiff = backend.diff(DiffTarget.Unstaged("line\nbreak.txt"))
            assertTrue(newlineDiff.lines.any { it.kind == DiffLineKind.ADD && it.text == "three" })
        }

        repo.git("add", "-A")
        val stagedDelete = backend.diff(DiffTarget.Staged("delete me.txt"))
        assertTrue(stagedDelete.lines.any { it.kind == DiffLineKind.DEL && it.text == "gone" })
    }

    @Test
    fun diffIsCappedAtTwoMegabytesAndUnlimitedLoadsAll() = runBlocking {
        repo.write("keep.txt", "keep\n")
        repo.commitAll("root")
        val big = buildString { repeat(300_000) { append("0123456789\n") } }.toByteArray()
        repo.write("big.txt", big)

        val capped = backend.diff(DiffTarget.Unstaged("big.txt"))
        assertTrue(capped.truncated)

        val unlimited = backend.diff(DiffTarget.Unstaged("big.txt"), unlimited = true)
        assertFalse(unlimited.truncated)
        assertEquals(300_000, unlimited.lines.size)
    }

    @Test
    fun trackedDiffOutputIsKilledAndMarkedTruncated() = runBlocking {
        repo.write("big.txt", buildString { repeat(250_000) { append("aaaaaaaaaa\n") } })
        repo.commitAll("root")
        repo.write("big.txt", buildString { repeat(250_000) { append("bbbbbbbbbb\n") } })

        val diff = backend.diff(DiffTarget.Unstaged("big.txt"))

        assertTrue(diff.truncated)
    }

    @Test
    fun longLinesAreNotTruncatedByTheBackend() = runBlocking {
        val line = "x".repeat(3_000)
        repo.write("long.txt", "$line\n")
        repo.commitAll("root")
        repo.write("long.txt", "$line\nmore\n")

        val diff = backend.diff(DiffTarget.Unstaged("long.txt"))
        assertEquals(line, diff.lines.single { it.kind == DiffLineKind.CONTEXT && it.text.startsWith("x") }.text)
    }

    @Test
    fun gitDirsResolveMainRepoSubdirectoryAndWorktree() = runBlocking {
        repo.write("a.txt", "a\n")
        repo.commitAll("root")
        val dirs = backend.gitDirs()
        assertTrue(Files.isSameFile(repo.root, dirs.workTree))
        assertTrue(Files.isSameFile(repo.root.resolve(".git"), dirs.gitDir))
        assertTrue(Files.isSameFile(repo.root.resolve(".git"), dirs.commonDir))

        val sub = repo.root.resolve("nested/deeper")
        Files.createDirectories(sub)
        val fromSub = CliGitBackend.open(sub, repo.gitExecutable)
        assertTrue(Files.isSameFile(repo.root, fromSub.gitDirs().workTree))

        val worktreePath = Files.createTempDirectory("freetree-git-worktree-")
        Files.delete(worktreePath)
        repo.git("worktree", "add", "-q", worktreePath.toString(), "-b", "wt")
        try {
            val fromWorktree = CliGitBackend.open(worktreePath, repo.gitExecutable)
            val worktreeDirs = fromWorktree.gitDirs()
            assertTrue(Files.isSameFile(worktreePath, worktreeDirs.workTree))
            assertTrue(Files.isSameFile(repo.root.resolve(".git"), worktreeDirs.commonDir))
            assertFalse(Files.isSameFile(repo.root.resolve(".git"), worktreeDirs.gitDir))
            assertEquals(repo.head(), fromWorktree.head().commit)
        } finally {
            Files.walk(worktreePath).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun parseNameStatusOracle(tokens: List<String>): Set<Triple<Char, String, String?>> {
        val result = mutableSetOf<Triple<Char, String, String?>>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.isEmpty()) {
                index += 1
                continue
            }
            val status = token.first()
            if (status == 'R' || status == 'C') {
                val oldPath = tokens.getOrNull(index + 1) ?: break
                val newPath = tokens.getOrNull(index + 2) ?: break
                result += Triple(status, newPath, oldPath)
                index += 3
            } else {
                val path = tokens.getOrNull(index + 1) ?: break
                result += Triple(status, path, null)
                index += 2
            }
        }
        return result
    }

    private fun v1Entries(repo: GitTestRepo): Set<Pair<String, String>> {
        val raw = repo.git("status", "--porcelain=v1", "-z")
        val tokens = raw.split('\u0000')
        val result = mutableSetOf<Pair<String, String>>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.isEmpty()) {
                index += 1
                continue
            }
            val code = token.substring(0, 2)
            val path = token.substring(3)
            result += path to code
            if (code[0] == 'R' || code[0] == 'C') index += 1
            index += 1
        }
        return result
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")
}
