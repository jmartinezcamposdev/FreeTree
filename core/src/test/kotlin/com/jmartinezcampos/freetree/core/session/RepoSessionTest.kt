package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.testing.RandomDag
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RepoSessionTest {

    private val sessionPath = Path.of("/repo/session")

    @Test
    fun activationLoadsGraphRefsStatusAndLabels() = runTest {
        val commits = RandomDag.generate(seed = 21, commitCount = 30)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)

        session.activate()
        advanceUntilIdle()

        val state = session.state.value
        val graph = assertNotNull(state.graph)
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        assertTrue(watcher.isRunning)
        assertEquals(commits.size, graph.rowCount)
        assertTrue(graph.complete)
        assertEquals(backend.refs, state.refs)
        assertEquals(backend.head, state.head)
        assertEquals(backend.status, state.status)
        assertEquals(backend.refs, state.labelsByCommit[backend.head.commit])
        assertEquals(LoadState.Idle, state.load)
        assertTrue(backend.callCount(FakeMethod.LOG) >= 1)
        session.close()
    }

    @Test
    fun refsChangedReloadsGraphWhileStatusOnlyDoesNot() = runTest {
        val commits = RandomDag.generate(seed = 22, commitCount = 30)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        session.activate()
        advanceUntilIdle()

        backend.clearCalls()
        watcher.emit(WatchEvent.STATUS_CHANGED)
        advanceUntilIdle()
        assertEquals(listOf(FakeMethod.STATUS), backend.calls.map { it.method })

        backend.clearCalls()
        watcher.emit(WatchEvent.REFS_CHANGED)
        advanceUntilIdle()
        assertTrue(backend.callsOf(FakeMethod.REFS).isNotEmpty())
        assertTrue(backend.callsOf(FakeMethod.HEAD).isNotEmpty())
        assertTrue(backend.callsOf(FakeMethod.STATUS).isNotEmpty())
        assertEquals(0, backend.callCount(FakeMethod.LOG))

        backend.refs = backend.refs + GitRef(
            fullName = "refs/heads/feature",
            shortName = "feature",
            kind = RefKind.LOCAL_BRANCH,
            commit = commits[5].hash,
            isCurrentBranch = false,
            upstream = null,
        )
        backend.clearCalls()
        watcher.emit(WatchEvent.REFS_CHANGED)
        advanceUntilIdle()
        assertEquals(1, backend.callCount(FakeMethod.LOG))
        assertEquals(2, session.state.value.refs.size)
        assertTrue(session.state.value.graph!!.complete)
        session.close()
    }

    @Test
    fun headChangeAloneReloadsGraph() = runTest {
        val commits = RandomDag.generate(seed = 23, commitCount = 30)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        session.activate()
        advanceUntilIdle()

        backend.head = HeadInfo(commits[7].hash, "main")
        backend.clearCalls()
        watcher.emit(WatchEvent.REFS_CHANGED)
        advanceUntilIdle()

        assertEquals(1, backend.callCount(FakeMethod.LOG))
        assertEquals(backend.head, session.state.value.head)
        session.close()
    }

    @Test
    fun queryAppliesAndInvalidQueryKeepsLastView() = runTest {
        val commits = RandomDag.generate(seed = 24, commitCount = 30)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        session.activate()
        advanceUntilIdle()
        val stableGraph = session.state.value.graph
        backend.clearCalls()

        session.applyQuery("bogus:value")
        advanceUntilIdle()

        assertSame(stableGraph, session.state.value.graph)
        assertEquals("bogus:value", session.state.value.query)
        assertEquals("bogus:value", session.tab.value.query)
        assertNotNull(session.state.value.queryError)
        assertEquals(0, backend.callCount(FakeMethod.LOG))

        session.applyQuery("author:Ada")
        advanceUntilIdle()

        assertNull(session.state.value.queryError)
        assertNotSame(stableGraph, session.state.value.graph)
        assertTrue(session.state.value.graph!!.flat)
        assertEquals("Ada", backend.callsOf(FakeMethod.LOG).last().spec?.author)
        assertTrue(backend.callsOf(FakeMethod.LOG).last().spec!!.flat)
        session.close()
    }

    @Test
    fun contentFilterUsesFlatGraph() = runTest {
        val commits = RandomDag.generate(seed = 25, commitCount = 30)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()

        session.applyQuery("path:src/app.kt")
        advanceUntilIdle()

        val graph = session.state.value.graph!!
        assertTrue(graph.flat)
        assertEquals(1, graph.maxLanes)
        assertEquals(commits.size, graph.rowCount)
        session.close()
    }

    @Test
    fun refreshSwapsGraphAndPreservesSelectionAndAnchor() = runTest {
        val commits = RandomDag.generate(seed = 26, commitCount = 40)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()

        val hash = session.state.value.graph!!.hash(7)
        session.selectCommit(hash)
        session.setScrollAnchor(hash, 7)
        advanceUntilIdle()
        val oldGraph = session.state.value.graph!!
        assertEquals(hash, session.state.value.commitDetail?.hash)

        session.refresh()
        advanceUntilIdle()

        assertNotSame(oldGraph, session.state.value.graph)
        assertEquals(Selection.Commit(hash), session.state.value.selection)
        assertEquals(hash, session.tab.value.selectedHash)
        assertEquals(hash, session.tab.value.scrollAnchorHash)

        session.setScrollAnchor("missing-anchor", 5)
        session.refresh()
        advanceUntilIdle()
        assertEquals(session.state.value.graph!!.hash(5), session.tab.value.scrollAnchorHash)
        session.close()
    }

    @Test
    fun keyboardNavigationDebouncesAndLoadsOnlyTheFinalCommit() = runTest {
        val commits = RandomDag.generate(seed = 35, commitCount = 30)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        val first = session.state.value.graph!!.hash(3)
        val second = session.state.value.graph!!.hash(8)
        val changes = listOf(FileChange('M', "file.kt", null))
        backend.changedFiles = mapOf(second to changes)
        backend.clearCalls()

        session.selectCommit(first)
        advanceTimeBy(50)
        runCurrent()
        session.selectCommit(second)
        advanceTimeBy(99)
        runCurrent()

        assertEquals(0, backend.callCount(FakeMethod.COMMIT_DETAIL))

        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, backend.callCount(FakeMethod.COMMIT_DETAIL))
        assertEquals(second, backend.callsOf(FakeMethod.COMMIT_DETAIL).single().hash)
        assertEquals(second, session.state.value.commitDetail?.hash)
        assertEquals(changes, session.state.value.changedFiles)
        assertEquals(second, session.tab.value.selectedHash)
        session.close()
    }

    @Test
    fun workingTreeSelectionLoadsNoCommitDetail() = runTest {
        val commits = RandomDag.generate(seed = 36, commitCount = 20)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        session.selectWorkingTree()
        advanceUntilIdle()

        assertEquals(Selection.WorkingTree, session.state.value.selection)
        assertNull(session.state.value.commitDetail)
        assertTrue(session.state.value.changedFiles.isEmpty())
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        assertEquals(0, backend.callCount(FakeMethod.COMMIT_DETAIL))
        assertEquals(0, backend.callCount(FakeMethod.CHANGED_FILES))
        assertNull(session.tab.value.selectedHash)
        session.close()
    }

    @Test
    fun openDiffLoadsUnlimitedAndDropsStaleResults() = runTest {
        val commits = RandomDag.generate(seed = 37, commitCount = 10)
        val truncated = DiffTarget.Unstaged("a.txt")
        val other = DiffTarget.Staged("b.txt")
        val backend = backend(commits)
        backend.diffs = mapOf(
            truncated to DiffText(emptyList(), binary = false, truncated = true),
            other to DiffText(emptyList(), binary = false, truncated = false),
        )
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        session.openDiff(truncated)
        advanceUntilIdle()

        assertEquals(truncated, session.state.value.openDiffTarget)
        assertTrue(session.state.value.openDiff!!.truncated)
        assertEquals(false, backend.callsOf(FakeMethod.DIFF).single().unlimited)

        backend.diffs = backend.diffs + (truncated to DiffText(emptyList(), binary = false, truncated = false))
        session.openDiff(truncated, unlimited = true)
        advanceUntilIdle()

        assertTrue(backend.callsOf(FakeMethod.DIFF).last().unlimited)
        assertFalse(session.state.value.openDiff!!.truncated)

        val gate = backend.pauseNext(FakeMethod.DIFF)
        session.openDiff(truncated)
        advanceUntilIdle()
        gate.awaitEntered()

        session.openDiff(other, unlimited = true)
        advanceUntilIdle()
        assertEquals(other, session.state.value.openDiffTarget)

        gate.release()
        advanceUntilIdle()

        assertEquals(other, session.state.value.openDiffTarget)
        assertEquals(truncated, backend.callsOf(FakeMethod.DIFF)[1].target)
        session.close()
    }

    @Test
    fun interruptedDiffReloadsAfterReactivation() = runTest {
        val commits = RandomDag.generate(seed = 42, commitCount = 10)
        val target = DiffTarget.Unstaged("a.txt")
        val backend = backend(commits)
        backend.diffs = mapOf(target to DiffText(emptyList(), binary = false, truncated = false))
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        val gate = backend.pauseNext(FakeMethod.DIFF)
        session.openDiff(target, unlimited = true)
        advanceUntilIdle()
        gate.awaitEntered()

        session.deactivate()
        gate.release()
        advanceUntilIdle()
        assertNull(session.state.value.openDiff)

        session.activate()
        advanceUntilIdle()

        assertNotNull(session.state.value.openDiff)
        assertEquals(target, session.state.value.openDiffTarget)
        assertEquals(2, backend.callCount(FakeMethod.DIFF))
        assertTrue(backend.callsOf(FakeMethod.DIFF).last().unlimited)
        session.close()
    }

    @Test
    fun selectionCancelsPendingDiff() = runTest {
        val commits = RandomDag.generate(seed = 38, commitCount = 20)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        val gate = backend.pauseNext(FakeMethod.DIFF)

        session.openDiff(DiffTarget.Unstaged("a.txt"))
        advanceUntilIdle()
        gate.awaitEntered()

        session.selectCommit(commits[2].hash)
        advanceUntilIdle()
        gate.release()
        advanceUntilIdle()

        assertNull(session.state.value.openDiff)
        assertNull(session.state.value.openDiffTarget)
        session.close()
    }

    @Test
    fun stageAndUnstageExcludeConflicts() = runTest {
        val commits = RandomDag.generate(seed = 39, commitCount = 10)
        val status = WorkingStatus(
            branchHead = commits.first().hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = false,
            entries = listOf(
                StatusEntry("conflict.txt", null, 'U', 'U', StatusKind.UNMERGED),
                StatusEntry("other.txt", null, 'M', ' ', StatusKind.ORDINARY),
            ),
        )
        val backend = backend(commits, status = status)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        session.stage(listOf("conflict.txt", "other.txt"))
        advanceUntilIdle()

        assertEquals(listOf("other.txt"), backend.callsOf(FakeMethod.STAGE).single().paths)

        backend.clearCalls()
        session.unstage(listOf("conflict.txt"))
        advanceUntilIdle()

        assertTrue(backend.callsOf(FakeMethod.UNSTAGE).isEmpty())
        assertTrue(session.state.value.message is UserMessage.Error)
        session.close()
    }

    @Test
    fun busyStaysSetThroughRefreshAndClearsAfterWrite() = runTest {
        val commits = RandomDag.generate(seed = 40, commitCount = 10)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()
        val statusGate = backend.pauseNext(FakeMethod.STATUS)

        session.stage(listOf("a.txt"))
        advanceUntilIdle()
        statusGate.awaitEntered()

        assertEquals(BusyOp.STAGE, session.state.value.busy)
        assertEquals(SessionPhase.DRAINING, session.phase.value)

        statusGate.release()
        advanceUntilIdle()

        assertNull(session.state.value.busy)
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        assertEquals(1, backend.callCount(FakeMethod.STAGE))
        assertEquals(
            listOf(FakeMethod.STAGE, FakeMethod.STATUS, FakeMethod.REFS, FakeMethod.HEAD),
            backend.calls.map { it.method },
        )
        session.close()
    }

    @Test
    fun pushAndPullAreSerializedWrites() = runTest {
        val commits = RandomDag.generate(seed = 41, commitCount = 10)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()
        val pushGate = backend.pauseNext(FakeMethod.PUSH)

        val push = session.push()
        advanceUntilIdle()
        pushGate.awaitEntered()
        assertEquals(BusyOp.PUSH, session.state.value.busy)

        val pull = session.pull()
        advanceUntilIdle()
        assertEquals(0, backend.callCount(FakeMethod.PULL))

        pushGate.release()
        advanceUntilIdle()

        assertTrue(push.isCompleted)
        assertTrue(pull.isCompleted)
        assertEquals(1, backend.callCount(FakeMethod.PUSH))
        assertEquals(1, backend.callCount(FakeMethod.PULL))
        assertEquals(1, backend.peakConcurrentWrites)
        assertNull(session.state.value.busy)
        assertNull(session.state.value.message)
        assertTrue(session.messageLog.entries.value.takeLast(2).all { it.level == MessageLevel.INFO })
        session.close()
    }

    @Test
    fun detectsInProgressGitOperations() = runTest {
        val directory = Files.createTempDirectory("freetree-session-op")
        val gitDir = directory.resolve(".git")
        Files.createDirectories(gitDir)
        val dirs = GitDirs(directory, gitDir, gitDir)
        Files.writeString(gitDir.resolve("MERGE_HEAD"), "a".repeat(40))
        val commits = RandomDag.generate(seed = 27, commitCount = 10)
        val backend = backend(commits, dirs = dirs)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        session.activate()
        advanceUntilIdle()
        assertEquals("merge", session.state.value.operationInProgress)

        Files.delete(gitDir.resolve("MERGE_HEAD"))
        Files.createDirectories(gitDir.resolve("rebase-merge"))
        watcher.emit(WatchEvent.STATUS_CHANGED)
        advanceUntilIdle()
        assertEquals("rebase", session.state.value.operationInProgress)

        session.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun discardExcludesConflicts() = runTest {
        val commits = RandomDag.generate(seed = 28, commitCount = 10)
        val status = WorkingStatus(
            branchHead = commits.first().hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = false,
            entries = listOf(
                StatusEntry("conflict.txt", null, 'U', 'U', StatusKind.UNMERGED),
                StatusEntry("other.txt", null, 'M', ' ', StatusKind.ORDINARY),
            ),
        )
        val backend = backend(commits, status = status)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        session.discardUnstaged(listOf("conflict.txt", "other.txt"))
        advanceUntilIdle()

        val discard = backend.callsOf(FakeMethod.DISCARD_UNSTAGED).single()
        assertEquals(listOf("other.txt"), discard.paths)

        backend.clearCalls()
        session.discardStaged(listOf("conflict.txt"))
        advanceUntilIdle()
        assertTrue(backend.callsOf(FakeMethod.DISCARD_STAGED).isEmpty())
        assertTrue(session.state.value.message is UserMessage.Error)
        session.close()
    }

    @Test
    fun commitGuardsEmptyMessageAndEmptyIndex() = runTest {
        val commits = RandomDag.generate(seed = 29, commitCount = 10)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()

        session.commit("   ")
        advanceUntilIdle()
        assertTrue(backend.callsOf(FakeMethod.COMMIT).isEmpty())
        assertTrue(session.state.value.message is UserMessage.Error)

        session.commit("message")
        advanceUntilIdle()
        assertTrue(backend.callsOf(FakeMethod.COMMIT).isEmpty())
        assertTrue(session.state.value.message is UserMessage.Error)

        backend.status = WorkingStatus(
            branchHead = commits.first().hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = false,
            entries = listOf(StatusEntry("a.txt", null, 'M', ' ', StatusKind.ORDINARY)),
        )
        watcher.emit(WatchEvent.STATUS_CHANGED)
        advanceUntilIdle()
        backend.clearCalls()

        session.commit("message")
        advanceUntilIdle()
        assertEquals(1, backend.callCount(FakeMethod.COMMIT))
        assertEquals("message", backend.callsOf(FakeMethod.COMMIT).single().message)
        assertNull(session.state.value.message)
        assertEquals(MessageLevel.INFO, session.lastLog().level)
        assertEquals("Commit created.", session.lastLog().text)
        session.close()
    }

    @Test
    fun failedWriteReportsStderrAndRefreshesExplicitly() = runTest {
        val commits = RandomDag.generate(seed = 30, commitCount = 10)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()
        backend.setResult(FakeMethod.STAGE, GitResult.Failure(1, "index locked"))

        session.stage(listOf("a.txt"))
        advanceUntilIdle()

        assertEquals(
            listOf(FakeMethod.STAGE, FakeMethod.STATUS, FakeMethod.REFS, FakeMethod.HEAD),
            backend.calls.map { it.method },
        )
        val message = session.state.value.message
        assertTrue(message is UserMessage.Error)
        assertEquals("index locked", message.stderr)
        assertNull(session.state.value.busy)
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        session.close()
    }

    @Test
    fun writeOnActiveSessionRefreshesAndReloadsChangedRefs() = runTest {
        val commits = RandomDag.generate(seed = 34, commitCount = 30)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        backend.clearCalls()
        val gate = backend.pause(FakeMethod.STAGE)

        session.stage(listOf("a.txt"))
        advanceUntilIdle()
        gate.awaitEntered()
        backend.refs = listOf(
            GitRef(
                fullName = "refs/heads/main",
                shortName = "main",
                kind = RefKind.LOCAL_BRANCH,
                commit = commits[6].hash,
                isCurrentBranch = true,
                upstream = null,
            ),
        )
        backend.head = HeadInfo(commits[6].hash, "main")
        gate.release()
        advanceUntilIdle()

        assertEquals(1, backend.callCount(FakeMethod.LOG))
        assertEquals(backend.refs, session.state.value.refs)
        assertEquals(backend.head, session.state.value.head)
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        assertNull(session.state.value.message)
        assertEquals(MessageLevel.INFO, session.lastLog().level)
        session.close()
    }

    @Test
    fun deactivationCancelsGraphLoad() = runTest {
        val commits = RandomDag.generate(seed = 31, commitCount = 30)
        val backend = backend(commits)
        val watcher = FakeWatcher()
        val session = session(backend, watcher)
        val gate = backend.pause(FakeMethod.LOG)

        session.activate()
        advanceUntilIdle()
        gate.awaitEntered()
        assertNull(session.state.value.graph)

        session.deactivate()
        assertEquals(SessionPhase.INACTIVE, session.phase.value)
        assertFalse(watcher.isRunning)
        gate.release()
        advanceUntilIdle()

        assertNull(session.state.value.graph)
        assertEquals(SessionPhase.INACTIVE, session.phase.value)
        session.close()
    }

    @Test
    fun canceledRefreshLeavesFrozenGraphIdleAfterReactivation() = runTest {
        val commits = RandomDag.generate(seed = 43, commitCount = 30)
        val backend = backend(commits)
        val session = session(backend, FakeWatcher())
        session.activate()
        advanceUntilIdle()
        val frozen = session.state.value.graph!!
        backend.clearCalls()
        val gate = backend.pauseNext(FakeMethod.LOG)

        session.refresh()
        advanceUntilIdle()
        gate.awaitEntered()
        assertTrue(session.state.value.load is LoadState.Loading)

        session.deactivate()
        assertEquals(LoadState.Idle, session.state.value.load)
        gate.release()
        advanceUntilIdle()

        session.activate()
        advanceUntilIdle()

        assertSame(frozen, session.state.value.graph)
        assertEquals(LoadState.Idle, session.state.value.load)
        assertEquals(1, backend.callCount(FakeMethod.LOG))
        session.close()
    }

    @Test
    fun graphLoadPublishesPartialSnapshotsThenComplete() = runTest {
        val commits = RandomDag.generate(seed = 32, commitCount = 2_500)
        val backend = backend(commits)
        backend.logDelayMillis = 1
        val session = session(backend, FakeWatcher())

        session.activate()
        advanceTimeBy(150)
        runCurrent()

        val partial = assertNotNull(session.state.value.graph)
        assertFalse(partial.complete)
        assertTrue(partial.rowCount in 1 until commits.size)
        assertTrue(session.state.value.load is LoadState.Loading)

        advanceUntilIdle()
        val complete = assertNotNull(session.state.value.graph)
        assertTrue(complete.complete)
        assertEquals(commits.size, complete.rowCount)
        assertEquals(LoadState.Idle, session.state.value.load)
        session.close()
    }

    @Test
    fun watcherStartFailureFallsBackToPolling() = runTest {
        val commits = RandomDag.generate(seed = 33, commitCount = 10)
        val backend = backend(commits)
        val watcher = ThrowingWatcher()
        val session = session(backend, watcher)

        session.activate()
        runCurrent()

        val message = session.state.value.message
        assertTrue(message is UserMessage.Error)
        assertTrue(message.text.contains("polling"))
        assertEquals(1, watcher.startAttempts)
        assertNotNull(session.state.value.graph)
        backend.clearCalls()

        advanceTimeBy(3_001)
        runCurrent()

        assertTrue(backend.callsOf(FakeMethod.STATUS).isNotEmpty())
        assertEquals(1, watcher.stopAttempts)
        session.close()
    }

    private fun backend(
        commits: List<RawCommit>,
        status: WorkingStatus = WorkingStatus(
            branchHead = commits.firstOrNull()?.hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = commits.isEmpty(),
            entries = emptyList(),
        ),
        dirs: GitDirs = FakeGitBackend.defaultGitDirs(),
    ): FakeGitBackend = FakeGitBackend(
        commits = commits,
        refs = listOf(
            GitRef(
                fullName = "refs/heads/main",
                shortName = "main",
                kind = RefKind.LOCAL_BRANCH,
                commit = commits.first().hash,
                isCurrentBranch = true,
                upstream = null,
            ),
        ),
        head = HeadInfo(commits.first().hash, "main"),
        status = status,
        dirs = dirs,
    )

    private fun TestScope.session(backend: FakeGitBackend, watcher: RepoWatcher): RepoSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RepoSession(
            id = "tab",
            path = sessionPath,
            initialTab = TabState(id = "tab", path = sessionPath.toString()),
            backendFactory = { backend },
            watcherFactory = { watcher },
            readDispatcher = dispatcher,
            writeDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )
    }

    private fun RepoSession.lastLog(): MessageLogEntry = messageLog.entries.value.last()

    private class ThrowingWatcher : RepoWatcher {
        var startAttempts = 0
        var stopAttempts = 0

        override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
            startAttempts++
            throw IOException("watch service unavailable")
        }

        override fun stop() {
            stopAttempts++
        }
    }
}
