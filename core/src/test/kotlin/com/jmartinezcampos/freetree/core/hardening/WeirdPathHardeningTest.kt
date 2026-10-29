package com.jmartinezcampos.freetree.core.hardening

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * docs/11: paths with spaces, Unicode, newlines, leading dashes and symlinks; worktrees and
 * submodules expose a `.git` file instead of a directory.
 */
class WeirdPathHardeningTest {

    @Test
    fun newlineInTheWorkTreePathResolvesGitDirsAndSupportsReadsAndWrites() = runBlocking {
        // Windows file names cannot contain a newline.
        if (isWindows()) return@runBlocking
        val repo = GitTestRepo.create()
        val worktreeParent = Files.createTempDirectory("freetree-hardening-wt-")
        val worktree = worktreeParent.resolve("work tree\nline")
        try {
            repo.writeFile("a.txt", "one\n")
            repo.commitAll("root")
            repo.git(
                "worktree",
                "add",
                "-q",
                worktree.toAbsolutePath().toString(),
                "-b",
                "newline-worktree",
            ).requireSuccess()

            val backend = CliGitBackend.open(worktree, hardeningGit)
            val dirs = backend.gitDirs()
            assertEquals(worktree.toRealPath(), dirs.workTree.toRealPath())
            assertTrue(Files.isSameFile(repo.gitDir, dirs.commonDir))
            assertNotEquals(dirs.commonDir, dirs.gitDir)
            assertEquals(repo.headHash(), backend.head().commit)

            Files.writeString(worktree.resolve("a.txt"), "two\n")
            val entry = backend.status().entries.single { it.path == "a.txt" }
            assertEquals('M', entry.worktree)

            val diff = backend.diff(DiffTarget.Unstaged("a.txt"))
            assertTrue(diff.lines.any { it.kind == DiffLineKind.ADD && it.text == "two" })

            assertOk(backend.stage(listOf("a.txt")))
            assertEquals('M', backend.status().entries.single { it.path == "a.txt" }.index)
            assertOk(backend.discardStaged(listOf("a.txt")))
            assertEquals("two\n", Files.readString(worktree.resolve("a.txt")))
        } finally {
            repo.git("worktree", "remove", "--force", worktree.toAbsolutePath().toString())
            deleteRecursively(worktreeParent)
            repo.close()
        }
    }

    @Test
    fun spacesAndUnicodeDirectoriesRoundTripThroughDiffAndDiscard() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            val path = "dir with space/ünïcode naïve.txt"
            repo.writeFile(path, "one\n")
            repo.commitAll("root")
            repo.writeFile(path, "one\ntwo\n")

            val backend = repo.backend()
            val diff = backend.diff(DiffTarget.Unstaged(path))
            assertTrue(diff.lines.any { it.kind == DiffLineKind.ADD && it.text == "two" })

            assertOk(backend.stage(listOf(path)))
            assertEquals('M', backend.status().entries.single { it.path == path }.index)
            assertOk(backend.unstage(listOf(path)))
            assertEquals('M', statusOf(backend, path))

            assertOk(backend.discardUnstaged(listOf(path)))
            assertEquals("one\n", Files.readString(repo.workTree.resolve(path)))
            assertTrue(backend.status().entries.none { it.path == path })
        } finally {
            repo.close()
        }
    }

    @Test
    fun trackedSymlinksAreDiffsAndDiscardsAreRestoredWithoutTouchingTheTarget() = runBlocking {
        if (isWindows()) return@runBlocking
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("target.txt", "target\n")
            repo.writeFile("other.txt", "other\n")
            val link = repo.workTree.resolve("link")
            Files.createSymbolicLink(link, Path.of("target.txt"))
            repo.stageAll().requireSuccess()
            repo.commit("with link")

            Files.delete(link)
            Files.createSymbolicLink(link, Path.of("other.txt"))
            val backend = repo.backend()

            val diff = backend.diff(DiffTarget.Unstaged("link"))
            assertFalse(diff.binary)
            assertTrue(diff.lines.any { it.kind == DiffLineKind.ADD && it.text.contains("other.txt") })

            assertOk(backend.discardUnstaged(listOf("link")))
            assertEquals(Path.of("target.txt"), Files.readSymbolicLink(link))
            assertEquals("target\n", Files.readString(repo.workTree.resolve("target.txt")))
            assertEquals("other\n", Files.readString(repo.workTree.resolve("other.txt")))
            assertTrue(backend.status().entries.isEmpty())

            assertOk(backend.stage(listOf("link")))
            assertOk(backend.discardStaged(listOf("link")))
            assertEquals(Path.of("target.txt"), Files.readSymbolicLink(link))
        } finally {
            repo.close()
        }
    }

    @Test
    fun submoduleGitFileResolvesToTheSubmoduleRepository() = runBlocking {
        val submodule = GitTestRepo.create()
        val superproject = GitTestRepo.create()
        try {
            submodule.writeFile("s.txt", "sub\n")
            submodule.commitAll("sub root")
            superproject.writeFile("top.txt", "top\n")
            superproject.commitAll("super root")
            superproject.git(
                "-c",
                "protocol.file.allow=always",
                "submodule",
                "add",
                "-q",
                submodule.workTree.toAbsolutePath().toString(),
                "nested/sub",
            ).requireSuccess()
            superproject.commitAll("add submodule")

            val nested = superproject.workTree.resolve("nested/sub")
            assertTrue(Files.isRegularFile(nested.resolve(".git")), "submodules use a .git file")

            val backend = CliGitBackend.open(nested, hardeningGit)
            val dirs = backend.gitDirs()
            assertEquals(nested.toRealPath(), dirs.workTree.toRealPath())
            assertNotEquals(superproject.gitDir.toRealPath(), dirs.gitDir.toRealPath())
            Files.isSameFile(dirs.gitDir, dirs.commonDir)
            assertEquals(submodule.headHash(), backend.head().commit)

            Files.writeString(nested.resolve("s.txt"), "sub changed\n")
            assertEquals('M', backend.status().entries.single { it.path == "s.txt" }.worktree)
            val diff = backend.diff(DiffTarget.Unstaged("s.txt"))
            assertTrue(diff.lines.any { it.kind == DiffLineKind.ADD && it.text == "sub changed" })
        } finally {
            superproject.close()
            submodule.close()
        }
    }

    @Test
    fun tagsToTreesAndBlobsAndOriginHeadSymrefsAreExcluded() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("a.txt", "a\n")
            val root = repo.commitAll("root")
            val tree = repo.gitOk("rev-parse", "$root^{tree}")
            val blob = repo.gitWithInput("blob content\n", "hash-object", "-w", "--stdin")
                .requireSuccess()
                .stdout
                .trim()
            repo.git("tag", "annotated", "-a", "-m", "annotated", root).requireSuccess()
            repo.git("tag", "tree-tag", tree).requireSuccess()
            repo.git("tag", "blob-tag", blob).requireSuccess()
            repo.git("update-ref", "refs/remotes/origin/main", root).requireSuccess()
            repo.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main").requireSuccess()

            val refs = repo.backend().refs()
            assertEquals(
                setOf("refs/heads/main", "refs/tags/annotated", "refs/remotes/origin/main"),
                refs.map { it.fullName }.toSet(),
            )
            assertEquals(root, refs.single { it.fullName == "refs/tags/annotated" }.commit)
        } finally {
            repo.close()
        }
    }

    private suspend fun statusOf(backend: CliGitBackend, path: String): Char =
        backend.status().entries.single { it.path == path }.worktree

    private fun assertOk(result: GitResult) {
        assertTrue(result is GitResult.Ok, (result as? GitResult.Failure)?.stderr)
    }
}
