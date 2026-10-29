package com.jmartinezcampos.freetree.core.hardening

import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.LoadState
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.SessionPhase
import com.jmartinezcampos.freetree.core.session.TabState
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * docs/11: closing a tab during a load, a vanished repository, a diff whose file disappeared, and
 * a repository with no visible refs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHardeningTest {

    private val statePath = Path.of("/repo/hardening")

    @Test
    fun diffFailureReportsTheErrorAndRefreshesStatus() = runTest {
        val commits = RandomDag.generate(seed = 91, commitCount = 10)
        val status = WorkingStatus(
            branchHead = commits.first().hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = false,
            entries = listOf(StatusEntry("ephemeral.txt", null, ' ', '?', StatusKind.UNTRACKED)),
        )
        val backend = backend(commits, status = status)
        val session = session(backend)
        session.activate()
        advanceUntilIdle()

        backend.failNext(FakeMethod.DIFF, GitException(128, "Path is no longer present: ephemeral.txt"))
        backend.clearCalls()
        session.openDiff(DiffTarget.Unstaged("ephemeral.txt"))
        advanceUntilIdle()

        assertNull(session.state.value.openDiff)
        val message = session.state.value.message
        assertTrue(message is UserMessage.Error, "diff errors are visible")
        assertTrue(message.text.contains("Diff failed"), message.text)
        assertTrue(message.stderr.orEmpty().contains("no longer present"))
        assertTrue(
            backend.callsOf(FakeMethod.STATUS).isNotEmpty(),
            "status is refreshed after a stale diff request",
        )
        session.close()
    }

    @Test
    fun repositoryThatCannotBeReadKeepsTheSessionAliveAndRecoversOnRefresh() = runTest {
        val commits = RandomDag.generate(seed = 92, commitCount = 10)
        val backend = backend(commits)
        backend.failAlways(FakeMethod.REFS, GitException(128, "not a git repository"))
        backend.failAlways(FakeMethod.HEAD, GitException(128, "not a git repository"))
        backend.failAlways(FakeMethod.STATUS, GitException(128, "not a git repository"))
        backend.logFailAfter = 0
        val session = session(backend)

        session.activate()
        advanceUntilIdle()

        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        val failed = session.state.value.message
        assertTrue(failed is UserMessage.Error, "failures are surfaced")
        assertTrue(failed.stderr.orEmpty().isNotBlank())
        assertTrue(session.state.value.load is LoadState.Error)

        backend.clearFailures()
        backend.logFailAfter = null
        session.refresh()
        advanceUntilIdle()

        assertNotNull(session.state.value.graph)
        assertTrue(session.state.value.status != null)
        session.close()
    }

    @Test
    fun hidingEveryRefAndHeadLeavesAnEmptyCompleteGraph() = runTest {
        val commits = RandomDag.generate(seed = 93, commitCount = 10)
        val backend = backend(commits)
        val session = session(backend)
        session.activate()
        advanceUntilIdle()
        assertEquals(commits.size, session.state.value.graph?.rowCount)

        session.setShowHead(false)
        session.setHiddenRefs(setOf("refs/heads/main"))
        advanceUntilIdle()

        val graph = assertNotNull(session.state.value.graph)
        assertTrue(graph.complete)
        assertEquals(0, graph.rowCount)
        assertEquals(LoadState.Idle, session.state.value.load)
        assertTrue(session.state.value.refs.isNotEmpty())
        session.close()
    }

    @Test
    fun closingTheLastTabWhileTheGraphLoadIsSuspendedFinishesWithoutState() = runTest {
        val commits = RandomDag.generate(seed = 94, commitCount = 30)
        val backend = backend(commits)
        val persisted = InMemorySessionPersistence()
        val manager = manager(backend, persisted)
        val gate = backend.pauseNext(FakeMethod.LOG)

        manager.openRepo(statePath)
        advanceUntilIdle()
        val id = assertNotNull(manager.activeId.value)
        val session = assertNotNull(manager.session(id))
        assertEquals(SessionPhase.ACTIVE, session.phase.value)
        assertNull(session.state.value.graph, "the graph load is still paused")

        manager.closeTab(id)
        advanceUntilIdle()

        assertTrue(manager.tabs.value.isEmpty())
        assertNull(manager.activeId.value)
        assertNull(manager.activeSession.value)
        assertNull(manager.session(id))

        gate.release()
        backend.clearSuspensions()
        advanceUntilIdle()

        assertTrue(manager.tabs.value.isEmpty())
        assertNull(session.state.value.graph)
        assertTrue(persisted.saved?.tabs.orEmpty().isEmpty())
        manager.close()
    }

    private fun backend(
        commits: List<com.jmartinezcampos.freetree.core.model.RawCommit>,
        status: WorkingStatus = WorkingStatus(
            branchHead = commits.firstOrNull()?.hash,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = commits.isEmpty(),
            entries = emptyList(),
        ),
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
    )

    private fun TestScope.session(backend: FakeGitBackend): RepoSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RepoSession(
            id = "tab",
            path = statePath,
            initialTab = TabState(id = "tab", path = statePath.toString()),
            backendFactory = { backend },
            watcherFactory = { FakeWatcher() },
            readDispatcher = dispatcher,
            writeDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )
    }

    private fun TestScope.manager(backend: FakeGitBackend, persistence: InMemorySessionPersistence): SessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SessionManager(
            backendFactory = { backend },
            watcherFactory = { FakeWatcher() },
            persistence = persistence,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            readDispatcher = dispatcher,
            writeDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
            restore = false,
        )
    }
}
