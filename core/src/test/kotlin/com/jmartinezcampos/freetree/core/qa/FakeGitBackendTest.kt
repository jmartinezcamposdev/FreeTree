package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.BackendCall
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.SuspensionGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FakeGitBackendTest {

    private val newest = RawCommit(
        hash = "1".repeat(40),
        parents = listOf("2".repeat(40)),
        authorName = "Ada",
        authorTimeSec = 100,
        subject = "newest",
    )
    private val oldest = RawCommit(
        hash = "2".repeat(40),
        parents = emptyList(),
        authorName = "Grace",
        authorTimeSec = 99,
        subject = "oldest",
    )

    private fun backend() = FakeGitBackend(
        commits = listOf(newest, oldest),
        refs = listOf(
            GitRef(
                fullName = "refs/heads/main",
                shortName = "main",
                kind = RefKind.LOCAL_BRANCH,
                commit = newest.hash,
                isCurrentBranch = true,
                upstream = "origin/main",
            ),
        ),
        head = HeadInfo(newest.hash, "main"),
        status = WorkingStatus(
            branchHead = newest.hash,
            upstream = "origin/main",
            ahead = 1,
            behind = 0,
            unborn = false,
            entries = listOf(
                StatusEntry("a.txt", null, 'M', ' ', StatusKind.ORDINARY),
            ),
        ),
        commitDetails = mapOf(oldest.hash to oldestDetail()),
        changedFiles = mapOf(oldest.hash to listOf(FileChange('A', "a.txt", null))),
        diffs = mapOf(
            DiffTarget.InCommit(oldest.hash, FileChange('A', "a.txt", null)) to DiffText(
                lines = listOf(DiffLine(DiffLineKind.ADD, null, 1, "hello")),
                binary = false,
                truncated = false,
            ),
        ),
    )

    private fun oldestDetail() = com.jmartinezcampos.freetree.core.model.CommitDetail(
        hash = oldest.hash,
        parents = emptyList(),
        authorName = "Grace",
        authorEmail = "grace@example.com",
        authorTimeSec = 99,
        committerName = "Grace",
        committerTimeSec = 99,
        message = "oldest\n\nbody",
    )

    @Test
    fun exposesSnapshotsAndRecordsCalls() = runTest {
        val backend = backend()

        assertEquals(backend.refs, backend.refs())
        assertEquals(backend.head, backend.head())
        assertEquals(backend.status, backend.status())
        assertEquals(oldestDetail(), backend.commitDetail(oldest.hash))
        assertEquals(listOf(FileChange('A', "a.txt", null)), backend.changedFiles(oldest.hash))

        val target = DiffTarget.InCommit(oldest.hash, FileChange('A', "a.txt", null))
        assertEquals(1, backend.diff(target, unlimited = true).lines.size)

        val dirs = backend.gitDirs()
        assertEquals(backend.dirs, dirs)
        assertEquals(
            listOf(
                FakeMethod.REFS,
                FakeMethod.HEAD,
                FakeMethod.STATUS,
                FakeMethod.COMMIT_DETAIL,
                FakeMethod.CHANGED_FILES,
                FakeMethod.DIFF,
                FakeMethod.GIT_DIRS,
            ),
            backend.calls.map { it.method },
        )
        assertTrue(backend.callsOf(FakeMethod.DIFF).single().unlimited)
        assertEquals(target, backend.callsOf(FakeMethod.DIFF).single().target)
    }

    @Test
    fun logStreamIsColdOrderedAndCancellable() = runTest {
        val backend = backend()
        assertTrue(backend.callsOf(FakeMethod.LOG).isEmpty())

        val spec = LogSpec(revs = listOf("refs/heads/main"), includeHead = true)
        assertEquals(listOf(newest, oldest), backend.logStream(spec).toList())
        assertEquals(spec, backend.callsOf(FakeMethod.LOG).single().spec)

        backend.logDelayMillis = 10
        val gate = backend.pause(FakeMethod.LOG)
        val started = async { backend.logStream(spec).toList() }
        gate.awaitEntered()
        assertFalse(started.isCompleted)
        started.cancel()
        gate.release()
        advanceUntilIdle()
        assertTrue(started.isCancelled)
    }

    @Test
    fun logCanFailMidStream() = runTest {
        val backend = backend()
        backend.logFailAfter = 1
        backend.logFailure = GitException(42, "late failure")

        val error = assertFailsWith<GitException> {
            backend.logStream(LogSpec(emptyList(), includeHead = true)).toList()
        }

        assertEquals(42, error.exitCode)
        assertEquals(1, backend.callCount(FakeMethod.LOG))
    }

    @Test
    fun failedCallsAreConsumedOncePerInjection() = runTest {
        val backend = backend()
        backend.failNext(FakeMethod.STATUS, GitException(7, "first"))
        backend.failNext(FakeMethod.STATUS, GitException(8, "second"))

        assertEquals(7, assertFailsWith<GitException> { backend.status() }.exitCode)
        assertEquals(8, assertFailsWith<GitException> { backend.status() }.exitCode)
        assertEquals(backend.status, backend.status())
        assertEquals(3, backend.callCount(FakeMethod.STATUS))
    }

    @Test
    fun suspensionGateHoldsOneCall() = runTest {
        val backend = backend()
        val gate: SuspensionGate = backend.pause(FakeMethod.DIFF)
        val target = DiffTarget.Unstaged("a.txt")
        val deferred = async { backend.diff(target) }

        gate.awaitEntered()
        assertFalse(deferred.isCompleted)
        gate.release()

        assertEquals(DiffText(emptyList(), binary = false, truncated = false), deferred.await())
        assertEquals(target, backend.callsOf(FakeMethod.DIFF).single().target)
    }

    @Test
    fun pauseNextHoldsOnlyTheNextCall() = runTest {
        val backend = backend()
        val gate = backend.pauseNext(FakeMethod.STATUS)

        val first = async { backend.status() }
        gate.awaitEntered()
        val second = async { backend.status() }
        advanceUntilIdle()

        assertFalse(first.isCompleted)
        assertTrue(second.isCompleted)

        gate.release()
        assertEquals(backend.status, first.await())
    }

    @Test
    fun writeResultsAndArgumentsAreRecorded() = runTest {
        val backend = backend()
        backend.setResult(FakeMethod.STAGE, GitResult.Failure(1, "index locked"))
        backend.setResult(FakeMethod.COMMIT, GitResult.Ok("committed"))

        assertEquals(GitResult.Failure(1, "index locked"), backend.stage(listOf("a.txt")))
        assertEquals(GitResult.Ok("committed"), backend.commit("message"))
        assertEquals(GitResult.Ok(""), backend.push())

        val stage = backend.callsOf(FakeMethod.STAGE).single()
        assertEquals(listOf("a.txt"), stage.paths)
        assertEquals("message", backend.callsOf(FakeMethod.COMMIT).single().message)
    }

    @Test
    fun tracksPeakConcurrentWrites() = runTest {
        val backend = backend()
        backend.pause(FakeMethod.STAGE)

        val writes = (1..4).map { index ->
            launch { backend.stage(listOf("file-$index.txt")) }
        }
        advanceUntilIdle()

        assertEquals(4, backend.peakConcurrentWrites)
        assertEquals(4, backend.callCount(FakeMethod.STAGE))

        backend.clearSuspensions()
        writes.joinAll()
        assertEquals(4, backend.callsOf(FakeMethod.STAGE).size)
    }

    @Test
    fun unknownCommitDetailFails() = runTest {
        val backend = backend()
        val error = assertFailsWith<GitException> { backend.commitDetail("f".repeat(40)) }
        assertEquals(128, error.exitCode)
    }

    @Test
    fun callSequenceIsMonotonic() = runTest {
        val backend = backend()
        backend.refs()
        backend.head()
        val sequences = backend.calls.map(BackendCall::sequence)
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.distinct(), sequences)
    }
}
