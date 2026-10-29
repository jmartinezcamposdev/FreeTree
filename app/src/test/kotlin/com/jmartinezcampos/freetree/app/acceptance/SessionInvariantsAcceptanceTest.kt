package com.jmartinezcampos.freetree.app.acceptance

import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.session.BusyOp
import com.jmartinezcampos.freetree.core.session.LoadState
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionPhase
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenarios
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionInvariantsAcceptanceTest {

    private val alpha = Path.of("/repo/alpha")
    private val beta = Path.of("/repo/beta")
    private val gamma = Path.of("/repo/gamma")

    @Test
    fun t1AtMostOneSessionIsActiveAcrossRepeatedActivations() = runTest {
        val harness = harness(alpha, beta, gamma)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val betaId = openAndAdvance(harness, beta)
            val gammaId = openAndAdvance(harness, gamma)

            assertExclusiveActive(harness, gammaId)
            harness.manager.activate(alphaId)
            advanceUntilIdle()
            assertExclusiveActive(harness, alphaId)
            harness.manager.activate(betaId)
            advanceUntilIdle()
            assertExclusiveActive(harness, betaId)

            val active = harness.manager.activeSession.value
            assertNotNull(active)
            assertSame(harness.manager.session(betaId), active)
            assertEquals(alphaId, harness.manager.tabs.value.first().id)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t2InactiveSessionStopsWatcherAndPerformsNoBackendWork() = runTest {
        val harness = harness(alpha, beta)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val alphaSession = requireNotNull(harness.manager.session(alphaId))
            val alphaBackend = harness.backend(alpha)
            val alphaWatcher = harness.watcher(alpha)
            val frozen = requireNotNull(alphaSession.state.value.graph)

            openAndAdvance(harness, beta)

            assertEquals(SessionPhase.INACTIVE, alphaSession.phase.value)
            assertFalse(alphaWatcher.isRunning)
            assertEquals(1, alphaWatcher.stopCount)
            assertSame(frozen, alphaSession.state.value.graph)
            assertEquals(LoadState.Idle, alphaSession.state.value.load)

            alphaBackend.clearCalls()
            assertFalse(alphaWatcher.emit(WatchEvent.STATUS_CHANGED))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(alphaBackend.calls.isEmpty())
            assertSame(frozen, alphaSession.state.value.graph)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t3LateDetailAndDiffResultsDoNotModifyADeactivatedSession() = runTest {
        val harness = harness(alpha, beta)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val alphaSession = requireNotNull(harness.manager.session(alphaId))
            val alphaBackend = harness.backend(alpha)
            val commit = FakeRepoScenarios.standard().commits[2].hash

            val detailGate = alphaBackend.pauseNext(FakeMethod.COMMIT_DETAIL)
            alphaSession.selectCommit(commit)
            advanceUntilIdle()
            detailGate.awaitEntered()
            assertNull(alphaSession.state.value.commitDetail)

            openAndAdvance(harness, beta)
            detailGate.release()
            advanceUntilIdle()

            assertNull(alphaSession.state.value.commitDetail)
            assertTrue(alphaSession.state.value.changedFiles.isEmpty())
            assertEquals(Selection.Commit(commit), alphaSession.state.value.selection)
            assertEquals(SessionPhase.INACTIVE, alphaSession.phase.value)

            harness.manager.activate(alphaId)
            advanceUntilIdle()
            val target = DiffTarget.Unstaged("README.md")
            val diffGate = alphaBackend.pauseNext(FakeMethod.DIFF)
            alphaSession.openDiff(target)
            advanceUntilIdle()
            diffGate.awaitEntered()
            assertNull(alphaSession.state.value.openDiff)

            openAndAdvance(harness, beta)
            diffGate.release()
            advanceUntilIdle()
            assertNull(alphaSession.state.value.openDiff)
            assertEquals(target, alphaSession.state.value.openDiffTarget)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t4SwitchingShowsTheFrozenSnapshotBeforeAnyReadStarts() = runTest {
        val harness = harness(alpha, beta)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val alphaSession = requireNotNull(harness.manager.session(alphaId))
            val frozen = requireNotNull(alphaSession.state.value.graph)
            val alphaBackend = harness.backend(alpha)

            val betaId = openAndAdvance(harness, beta)
            harness.backend(beta).clearCalls()
            alphaBackend.clearCalls()

            harness.manager.activate(alphaId)
            assertSame(alphaSession, harness.manager.activeSession.value)
            assertSame(frozen, alphaSession.state.value.graph)
            assertEquals(LoadState.Idle, alphaSession.state.value.load)
            assertTrue(alphaBackend.calls.isEmpty(), "activation must not read synchronously")

            advanceUntilIdle()
            assertEquals(SessionPhase.ACTIVE, alphaSession.phase.value)
            assertEquals(SessionPhase.INACTIVE, harness.manager.session(betaId)!!.phase.value)
            assertTrue(alphaBackend.callsOf(FakeMethod.REFS).isNotEmpty())
            assertSame(frozen, alphaSession.state.value.graph)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t5QueryHiddenRefsSelectionAndAnchorStayPerTab() = runTest {
        val harness = harness(alpha, beta)
        val scenario = FakeRepoScenarios.standard()
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val alphaSession = requireNotNull(harness.manager.session(alphaId))
            val alphaSelection = scenario.commits[3].hash

            alphaSession.applyQuery("author:Ada")
            alphaSession.setHiddenRefs(setOf("refs/tags/v1.0"))
            alphaSession.setShowRemotes(false)
            alphaSession.selectCommit(alphaSelection)
            alphaSession.setScrollAnchor(alphaSelection, 3)
            advanceUntilIdle()
            assertTrue(alphaSession.state.value.graph!!.flat)

            val betaId = openAndAdvance(harness, beta)
            val betaSession = requireNotNull(harness.manager.session(betaId))
            betaSession.applyQuery("msg:Add")
            betaSession.setHiddenRefs(setOf("refs/heads/topic"))
            betaSession.selectWorkingTree()
            advanceUntilIdle()

            harness.manager.activate(alphaId)
            advanceUntilIdle()
            assertEquals("author:Ada", alphaSession.tab.value.query)
            assertEquals(setOf("refs/tags/v1.0"), alphaSession.tab.value.hiddenRefs)
            assertFalse(alphaSession.tab.value.showRemotes)
            assertEquals(Selection.Commit(alphaSelection), alphaSession.state.value.selection)
            assertEquals(alphaSelection, alphaSession.tab.value.scrollAnchorHash)
            assertTrue(alphaSession.state.value.graph!!.flat)

            val alphaTab = harness.manager.tabs.value.first { it.id == alphaId }
            assertEquals("author:Ada", alphaTab.query)
            assertEquals(setOf("refs/tags/v1.0"), alphaTab.hiddenRefs)
            assertFalse(alphaTab.showRemotes)
            assertEquals(alphaSelection, alphaTab.selectedHash)

            harness.manager.activate(betaId)
            advanceUntilIdle()
            assertEquals("msg:Add", betaSession.tab.value.query)
            assertEquals(setOf("refs/heads/topic"), betaSession.tab.value.hiddenRefs)
            assertEquals(Selection.WorkingTree, betaSession.state.value.selection)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t6WriteSurvivesATabSwitchAndItsResultIsNotified() = runTest {
        val harness = harness(alpha, beta)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val alphaId = requireNotNull(harness.manager.activeId.value)
            val alphaSession = requireNotNull(harness.manager.session(alphaId))
            val alphaBackend = harness.backend(alpha)
            val gate = alphaBackend.pauseNext(FakeMethod.STAGE)

            val write = alphaSession.stage(listOf("README.md"))
            advanceUntilIdle()
            gate.awaitEntered()
            assertEquals(SessionPhase.DRAINING, alphaSession.phase.value)
            assertEquals(BusyOp.STAGE, alphaSession.state.value.busy)

            openAndAdvance(harness, beta)
            assertEquals(alphaId, alphaSession.id)
            assertFalse(write.isCompleted)
            gate.release()
            advanceUntilIdle()

            assertTrue(write.isCompleted)
            assertNull(alphaSession.state.value.busy)
            assertEquals(SessionPhase.INACTIVE, alphaSession.phase.value)
            assertTrue(alphaSession.state.value.message is UserMessage.Info)
            val readme = alphaSession.state.value.status!!.entries.single { it.path == "README.md" }
            assertEquals('M', readme.index)
            assertEquals(' ', readme.worktree)
            assertEquals(1, alphaBackend.callCount(FakeMethod.STAGE))
            assertTrue(alphaBackend.callsOf(FakeMethod.STATUS).isNotEmpty())
            assertTrue(alphaBackend.callsOf(FakeMethod.REFS).isNotEmpty())
            assertEquals(SessionPhase.INACTIVE, harness.manager.session(alphaId)!!.phase.value)
            assertEquals(SessionPhase.ACTIVE, harness.manager.activeSession.value!!.phase.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun t7OpeningTheSameNormalizedPathReusesTheOnlyTab() = runTest {
        val harness = harness(alpha)
        try {
            harness.manager.openRepo(alpha)
            advanceUntilIdle()
            val tab = harness.manager.tabs.value.single()
            val session = requireNotNull(harness.manager.session(tab.id))
            val watcher = harness.watcher(alpha)
            assertEquals(1, watcher.startCount)

            harness.manager.openRepo(alpha.resolve("sub/.."))
            advanceUntilIdle()

            assertEquals(1, harness.manager.tabs.value.size)
            assertEquals(tab.id, harness.manager.activeId.value)
            assertSame(session, harness.manager.activeSession.value)
            assertEquals(1, watcher.startCount)
            assertEquals(1, harness.persistence.saved!!.tabs.size)
            assertEquals(tab.id, harness.persistence.saved!!.activeId)
        } finally {
            harness.close()
        }
    }

    private suspend fun TestScope.openAndAdvance(harness: AcceptanceHarness, path: Path): String {
        harness.manager.openRepo(path)
        advanceUntilIdle()
        return requireNotNull(harness.manager.activeId.value)
    }

    private fun assertExclusiveActive(harness: AcceptanceHarness, expectedId: String) {
        val sessions = harness.manager.tabs.value.mapNotNull { harness.manager.session(it.id) }
        assertEquals(harness.manager.tabs.value.size, sessions.size)
        assertEquals(1, sessions.count { it.phase.value == SessionPhase.ACTIVE })
        assertEquals(expectedId, sessions.single { it.phase.value == SessionPhase.ACTIVE }.id)
        assertEquals(expectedId, harness.manager.activeId.value)
        assertSame(
            harness.manager.session(expectedId),
            harness.manager.activeSession.value,
        )
    }
}
