package com.jmartinezcampos.freetree.core.hardening

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitExecutable
import com.jmartinezcampos.freetree.core.git.GitVersion
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * docs/11: Git failing mid-command, a missing executable, a moved/deleted repository, permission
 * errors, a file removed between status and diff, huge diffs and untracked trees.
 */
class GitFailureHardeningTest {

    @Test
    fun fakeGitFailingMidLogSurfacesGitStderrWithoutHanging() = runBlocking {
        if (isWindows()) return@runBlocking
        val repo = GitTestRepo.create()
        val scriptDirectory = Files.createTempDirectory("freetree-hardening-fakegit-")
        try {
            repo.writeFile("a.txt", "a\n")
            repo.commitAll("root")
            val script = fakeGitScript(
                scriptDirectory,
                """
                for arg in "${'$'}@"; do
                  if [ "${'$'}arg" = "log" ]; then
                    real_git "${'$'}@" | head -c 2000
                    echo "fake git failed mid-log" >&2
                    exit 1
                  fi
                  if [ "${'$'}arg" = "status" ]; then
                    echo "fake status failure" >&2
                    exit 128
                  fi
                done
                exec "${'$'}REAL_GIT" "${'$'}@"
                """.trimIndent(),
            )
            val fake = CliGitBackend.open(
                repo.workTree,
                GitExecutable(script, GitVersion(2, 30)),
            )
            val emitted = mutableListOf<String>()
            val failure = assertFailsWith<GitException> {
                fake.logStream(LogSpec(revs = listOf("HEAD"), includeHead = false))
                    .collect { emitted += it.hash }
            }
            assertTrue(failure.stderr.contains("fake git failed mid-log"), failure.stderr)
            assertEquals(1, failure.exitCode)
            assertTrue(emitted.isNotEmpty(), "records before the failure are streamed")

            val statusFailure = assertFailsWith<GitException> { fake.status() }
            assertEquals(128, statusFailure.exitCode)
            assertTrue(statusFailure.stderr.contains("fake status failure"), statusFailure.stderr)
        } finally {
            deleteRecursively(scriptDirectory)
            repo.close()
        }
    }

    @Test
    fun missingGitExecutableFailsWithAClearErrorAndNoHang() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            val missing = GitExecutable(
                Path.of("/definitely/not/installed/git"),
                GitVersion(2, 30),
            )
            val failure = withTimeout(10_000) {
                assertFailsWith<GitException> {
                    CliGitBackend.open(repo.workTree, missing)
                }
            }
            assertTrue(failure.stderr.contains("Could not start Git"), failure.stderr)
        } finally {
            repo.close()
        }
    }

    @Test
    fun movedOrDeletedRepositoryFailsReadsWithoutHanging() = runBlocking {
        val repo = GitTestRepo.create()
        val movedTo = Files.createTempDirectory("freetree-hardening-moved-").resolve("renamed")
        try {
            repo.writeFile("a.txt", "a\n")
            repo.commitAll("root")
            val backend = repo.backend()

            Files.move(repo.workTree, movedTo)
            val statusFailure = withTimeout(10_000) {
                assertFailsWith<GitException> { backend.status() }
            }
            assertTrue(statusFailure.stderr.isNotBlank())
            assertFailsWith<GitException> { backend.refs() }

            Files.move(movedTo, repo.workTree)
            assertTrue(backend.status().entries.isEmpty())
            assertEquals(repo.headHash(), backend.head().commit)

            deleteRecursively(repo.workTree)
            assertFailsWith<GitException> { withTimeout(10_000) { backend.status() } }
            assertFailsWith<GitException> { withTimeout(10_000) { backend.refs() } }
        } finally {
            deleteRecursively(movedTo.parent)
            repo.close()
        }
        Unit
    }

    @Test
    fun fileRemovedBetweenStatusAndDiffFailsClearly() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("keep.txt", "keep\n")
            repo.commitAll("root")
            val backend = repo.backend()
            repo.writeFile("ephemeral.txt", "gone soon\n")
            assertTrue(backend.status().entries.any { it.path == "ephemeral.txt" })
            Files.delete(repo.workTree.resolve("ephemeral.txt"))

            val unstaged = assertFailsWith<GitException> {
                backend.diff(DiffTarget.Unstaged("ephemeral.txt"))
            }
            assertTrue(unstaged.message.orEmpty().contains("no longer present"), unstaged.message)

            repo.writeFile("staged.txt", "staged\n")
            assertOk(backend.stage(listOf("staged.txt")))
            repo.git("rm", "--cached", "-f", "--", "staged.txt").requireSuccess()
            Files.delete(repo.workTree.resolve("staged.txt"))
            val staged = assertFailsWith<GitException> {
                backend.diff(DiffTarget.Staged("staged.txt"))
            }
            assertTrue(staged.message.orEmpty().contains("no longer present"), staged.message)
        } finally {
            repo.close()
        }
    }

    @Test
    fun unchangedExistingPathStillReturnsAnEmptyDiff() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("clean.txt", "clean\n")
            repo.commitAll("root")
            val backend = repo.backend()

            assertTrue(backend.diff(DiffTarget.Unstaged("clean.txt")).lines.isEmpty())
            assertTrue(backend.diff(DiffTarget.Staged("clean.txt")).lines.isEmpty())
        } finally {
            repo.close()
        }
    }

    @Test
    fun permissionDeniedWhileDiscardingUntrackedLeavesTheFileAndReportsIt() = runBlocking {
        if (isWindows() || isPrivilegedUser()) return@runBlocking
        val repo = GitTestRepo.create()
        val locked = repo.workTree.resolve("locked")
        try {
            repo.writeFile("keep.txt", "keep\n")
            repo.commitAll("root")
            repo.writeFile("locked/inside.txt", "inside\n")
            val backend = repo.backend()
            assertEquals(1, backend.status().entries.count { it.path == "locked/inside.txt" })

            Files.setPosixFilePermissions(
                locked,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            try {
                val result = backend.discardUnstaged(listOf("locked/inside.txt"))
                assertTrue(result is GitResult.Failure)
                assertTrue(result.stderr.contains("Could not delete"), result.stderr)
                assertTrue(Files.exists(locked.resolve("inside.txt")))
            } finally {
                Files.setPosixFilePermissions(
                    locked,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun fiftyMegabyteUntrackedDiffIsTruncatedAtTheDocumentedLimit() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("keep.txt", "keep\n")
            repo.commitAll("root")
            val big = repo.workTree.resolve("huge.txt")
            val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            chunk[chunk.lastIndex] = '\n'.code.toByte()
            Files.newOutputStream(big).use { output: OutputStream ->
                repeat(50) { output.write(chunk) }
            }
            assertEquals(50L * 1024 * 1024, Files.size(big))

            val started = System.nanoTime()
            val diff = repo.backend().diff(DiffTarget.Unstaged("huge.txt"))
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertTrue(diff.truncated)
            assertTrue(diff.lines.size < 10_000, "only the capped prefix is parsed")
            assertTrue(elapsedMillis < 20_000, "capped diff must not read the whole file: $elapsedMillis ms")
        } finally {
            repo.close()
        }
    }

    @Test
    fun untrackedDirectoriesListEveryFileAndRespectGitignore() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("keep.txt", "keep\n")
            repo.writeFile(".gitignore", "ignored/\n")
            repo.commitAll("root")
            repo.writeFile("ignored/skip.txt", "skip\n")
            repeat(50) { index -> repo.writeFile("dir/file-$index.txt", "file $index\n") }

            val backend = repo.backend()
            val untracked = backend.status().entries
                .filter { it.path.startsWith("dir/") }
                .map { it.path }
            assertEquals(50, untracked.size)
            assertTrue(backend.status().entries.none { it.path.startsWith("ignored/") })

            assertOk(backend.discardUnstaged(untracked))
            assertTrue(Files.exists(repo.workTree.resolve("dir")), "empty directories may remain")
            assertTrue(backend.status().entries.isEmpty())
        } finally {
            repo.close()
        }
    }

    private fun assertOk(result: GitResult) {
        assertTrue(result is GitResult.Ok, (result as? GitResult.Failure)?.stderr)
    }
}
