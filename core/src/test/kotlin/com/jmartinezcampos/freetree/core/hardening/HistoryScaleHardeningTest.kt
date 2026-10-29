package com.jmartinezcampos.freetree.core.hardening

import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * docs/11: thousands of branches, a commit with many parents, unborn HEAD and adversarial commit
 * messages and hooks.
 */
class HistoryScaleHardeningTest {

    @Test
    fun oneThousandBranchesAreAllParsedAndStreamed() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            val commits = (0 until 20).map { index ->
                repo.commitFile("file-$index.txt", "line $index\n", "commit $index")
            }
            val refsInput = buildString {
                repeat(1_000) { index ->
                    val target = commits[index % commits.size]
                    append("create refs/heads/branch-${index.toString().padStart(4, '0')} $target\n")
                }
            }
            repo.gitWithInput(refsInput, "update-ref", "--stdin").requireSuccess()

            val backend = repo.backend()
            val refs = backend.refs()
            assertEquals(1_001, refs.size)
            assertEquals(1_000, refs.count { it.kind == com.jmartinezcampos.freetree.core.model.RefKind.LOCAL_BRANCH && it.shortName.startsWith("branch-") })
            assertEquals(commits.last(), backend.head().commit)

            val streamed = backend.logStream(
                LogSpec(revs = refs.map { it.fullName }, includeHead = false),
            ).toList()
            assertEquals(commits.size, streamed.size)
            assertEquals(commits.toSet(), streamed.map { it.hash }.toSet())
        } finally {
            repo.close()
        }
    }

    @Test
    fun hundredParentOctopusIsParsedAndLaidOut() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("base.txt", "base\n")
            val root = repo.commitAll("root")
            val parents = (0 until 100).map { index ->
                repo.commitFile("parent-$index.txt", "parent $index\n", "parent $index")
            }
            val tree = repo.gitOk("rev-parse", "$root^{tree}")
            val commitTreeArgs = mutableListOf("commit-tree", tree)
            parents.forEach { parent ->
                commitTreeArgs += "-p"
                commitTreeArgs += parent
            }
            val merge = repo.gitWithInput("100-parent octopus\n", *commitTreeArgs.toTypedArray())
                .requireSuccess()
                .stdout
                .trim()

            val backend = repo.backend()
            val streamed = backend.logStream(LogSpec(revs = listOf(merge), includeHead = false)).toList()
            val first = streamed.first()
            assertEquals(merge, first.hash)
            assertEquals(100, first.parents.size)
            assertEquals(parents, first.parents)

            val detail = backend.commitDetail(merge)
            assertEquals(100, detail.parents.size)
            assertEquals("100-parent octopus\n", detail.message)

            val changed = backend.changedFiles(merge)
            assertEquals(
                listOf('D' to "parent-0.txt"),
                changed.map { it.status to it.path },
                "compared against the first parent",
            )

            val engine = LaneLayoutEngine(flat = false)
            streamed.forEach(engine::add)
            val snapshot = engine.snapshot(complete = true)
            assertEquals(102, snapshot.rowCount)
            assertNotNull(snapshot.hash(0))
        } finally {
            repo.close()
        }
        Unit
    }

    @Test
    fun unbornHeadCommitsAndDiscardsIndexOnly() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            val backend = repo.backend()
            assertTrue(backend.head().commit == null)
            assertTrue(backend.status().unborn)

            assertTrue(backend.commit("first") is GitResult.Failure, "empty index must be rejected")
            repo.writeFile("first.txt", "first\n")
            assertOk(backend.stage(listOf("first.txt")))
            assertTrue(backend.commit(" \r\n\t") is GitResult.Failure, "blank message must be rejected")
            assertOk(backend.commit("first ünïcode"))
            val head = assertNotNull(backend.head().commit)
            assertEquals("first ünïcode\n", backend.commitDetail(head).message)

            repo.writeFile("second.txt", "second\n")
            assertOk(backend.stage(listOf("second.txt")))
            assertOk(backend.discardStaged(listOf("second.txt")))
            assertTrue(Files.exists(repo.workTree.resolve("second.txt")))
            val entry = backend.status().entries.single { it.path == "second.txt" }
            assertEquals(com.jmartinezcampos.freetree.core.model.StatusKind.UNTRACKED, entry.kind)
        } finally {
            repo.close()
        }
    }

    @Test
    fun crlfAndUnicodeMessagesRoundTripExactlyAsGitStoresThem() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("a.txt", "a\n")
            repo.commitAll("root")
            repo.writeFile("a.txt", "b\n")
            val backend = repo.backend()
            assertOk(backend.stage(listOf("a.txt")))
            val message = "sübject line\r\n\r\nbödy line ünïcode\r\n"
            assertOk(backend.commit(message))

            val hash = assertNotNull(backend.head().commit)
            val detail = backend.commitDetail(hash)
            val oracle = repo.git("log", "-1", "--format=%B", hash).stdout
            assertEquals(oracle.removeSuffix("\n"), detail.message)
            assertTrue(detail.message.contains("sübject line"))
            assertTrue(detail.message.contains("bödy line ünïcode"))
        } finally {
            repo.close()
        }
    }

    @Test
    fun hookOutputIsSurfacedAndTheRepositoryStaysUsable() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.writeFile("a.txt", "one\n")
            repo.commitAll("root")
            repo.writeFile("a.txt", "two\n")
            val backend = repo.backend()
            assertOk(backend.stage(listOf("a.txt")))

            // The fixture points core.hooksPath at <root>/hooks, not at .git/hooks.
            val hook = repo.root.resolve("hooks/pre-commit")
            Files.createDirectories(hook.parent)
            Files.writeString(hook, "#!/bin/sh\necho 'hook stdout rejection'\necho 'hook stderr detail' >&2\nexit 1\n")
            makeExecutable(hook)

            val failed = backend.commit("hook test")
            assertTrue(failed is GitResult.Failure)
            assertTrue(failed.stderr.contains("hook stdout rejection"), failed.stderr)
            assertTrue(failed.stderr.contains("hook stderr detail"), failed.stderr)
            assertEquals("one\n", repo.git("show", "HEAD:a.txt").stdout)

            Files.delete(hook)
            assertOk(backend.commit("after hook"))
            assertEquals("two\n", repo.git("show", "HEAD:a.txt").stdout)
        } finally {
            repo.close()
        }
    }

    private fun assertOk(result: GitResult) {
        assertTrue(result is GitResult.Ok, (result as? GitResult.Failure)?.stderr)
    }
}
