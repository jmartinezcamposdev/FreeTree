package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val firstPath = Path.of("/repo/one")
    private val secondPath = Path.of("/repo/two")

    @Test
    fun t1AtMostOneSessionIsActive() = runTest {
        val commits = RandomDag.generate(seed = 1, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = assertNotNull(manager.activeId.value)
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = assertNotNull(manager.activeId.value)

        assertEquals(2, manager.tabs.value.size)
        assertEquals(SessionPhase.INACTIVE, manager.session(firstId)!!.phase.value)
        assertEquals(SessionPhase.ACTIVE, manager.session(secondId)!!.phase.value)
        assertEquals(1, listOf(firstId, secondId).count { manager.session(it)!!.phase.value == SessionPhase.ACTIVE })
        assertSame(manager.session(secondId), manager.activeSession.value)
        manager.close()
    }

    @Test
    fun t2InactiveSessionHasNoWatcherOrBackendWork() = runTest {
        val commits = RandomDag.generate(seed = 2, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val watchers = mutableListOf<FakeWatcher>()
        val manager = manager(
            mapOf(firstPath.toString() to first, secondPath.toString() to second),
            watchers = watchers,
        )

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstWatcher = watchers.single()
        assertTrue(firstWatcher.isRunning)

        first.clearCalls()
        manager.openRepo(secondPath)
        advanceUntilIdle()

        assertFalse(firstWatcher.isRunning)
        assertEquals(1, firstWatcher.stopCount)
        assertEquals(SessionPhase.INACTIVE, manager.session(firstId)!!.phase.value)
        assertTrue(first.calls.isEmpty())
        assertFalse(firstWatcher.emit(com.jmartinezcampos.freetree.core.watch.WatchEvent.STATUS_CHANGED))
        advanceUntilIdle()
        assertTrue(first.calls.isEmpty())
        manager.close()
    }

    @Test
    fun t3LateSelectionResultDoesNotModifyDeactivatedSession() = runTest {
        val commits = RandomDag.generate(seed = 3, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstSession = manager.session(firstId)!!
        val gate = first.pause(FakeMethod.COMMIT_DETAIL)

        firstSession.selectCommit(commits[3].hash)
        advanceUntilIdle()
        gate.awaitEntered()
        assertNull(firstSession.state.value.commitDetail)

        manager.openRepo(secondPath)
        advanceUntilIdle()
        gate.release()
        advanceUntilIdle()

        assertNull(firstSession.state.value.commitDetail)
        assertTrue(firstSession.state.value.changedFiles.isEmpty())
        assertEquals(Selection.Commit(commits[3].hash), firstSession.state.value.selection)
        manager.close()
    }

    @Test
    fun t4SwitchShowsFrozenSnapshotAndDoesNotTouchBackendSynchronously() = runTest {
        val commits = RandomDag.generate(seed = 4, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstSession = manager.session(firstId)!!
        val frozen = assertNotNull(firstSession.state.value.graph)
        assertTrue(frozen.complete)

        manager.openRepo(secondPath)
        advanceUntilIdle()
        first.clearCalls()

        manager.activate(firstId)
        assertSame(frozen, firstSession.state.value.graph)
        assertSame(firstSession, manager.activeSession.value)
        assertTrue(first.calls.isEmpty())

        advanceUntilIdle()
        assertSame(frozen, firstSession.state.value.graph)
        assertEquals(0, first.callCount(FakeMethod.LOG))
        manager.close()
    }

    @Test
    fun t5TabStateAndSelectionAreIndependentPerTab() = runTest {
        val commits = RandomDag.generate(seed = 5, commitCount = 30)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstSession = manager.session(firstId)!!
        val firstCommit = commits[4].hash

        firstSession.applyQuery("author:Ada")
        firstSession.setHiddenRefs(setOf("refs/heads/main"))
        firstSession.setShowTags(false)
        firstSession.selectCommit(firstCommit)
        firstSession.setScrollAnchor(firstCommit, 4)
        advanceUntilIdle()
        assertTrue(firstSession.state.value.graph!!.flat)

        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        val secondSession = manager.session(secondId)!!
        secondSession.applyQuery("msg:commit")
        secondSession.selectWorkingTree()
        secondSession.setScrollAnchor(null, 0)
        advanceUntilIdle()

        manager.activate(firstId)
        advanceUntilIdle()

        assertEquals("author:Ada", firstSession.tab.value.query)
        assertEquals(setOf("refs/heads/main"), firstSession.tab.value.hiddenRefs)
        assertFalse(firstSession.tab.value.showTags)
        assertEquals(Selection.Commit(firstCommit), firstSession.state.value.selection)
        assertEquals(firstCommit, firstSession.tab.value.selectedHash)
        assertEquals(firstCommit, firstSession.tab.value.scrollAnchorHash)
        assertTrue(firstSession.state.value.graph!!.flat)

        assertEquals("msg:commit", secondSession.tab.value.query)
        assertEquals(Selection.WorkingTree, secondSession.state.value.selection)
        assertNull(secondSession.tab.value.selectedHash)
        assertEquals(secondSession.tab.value.query, manager.tabs.value.first { it.id == secondId }.query)
        manager.close()
    }

    @Test
    fun t6WritesSurviveTabSwitchesAndStaySerialized() = runTest {
        val commits = RandomDag.generate(seed = 6, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstSession = manager.session(firstId)!!
        val gate = first.pauseNext(FakeMethod.STAGE)

        val firstWrite = firstSession.stage(listOf("a.txt"))
        advanceUntilIdle()
        gate.awaitEntered()
        assertEquals(SessionPhase.DRAINING, firstSession.phase.value)
        assertEquals(BusyOp.STAGE, firstSession.state.value.busy)

        val secondWrite = firstSession.stage(listOf("b.txt"))
        advanceUntilIdle()
        assertEquals(1, first.callCount(FakeMethod.STAGE))

        manager.openRepo(secondPath)
        advanceUntilIdle()
        assertTrue(firstWrite.isActive)
        assertEquals(SessionPhase.DRAINING, firstSession.phase.value)

        gate.release()
        advanceUntilIdle()

        assertTrue(firstWrite.isCompleted)
        assertTrue(secondWrite.isCompleted)
        assertEquals(2, first.callCount(FakeMethod.STAGE))
        assertEquals(1, first.peakConcurrentWrites)
        assertEquals(SessionPhase.INACTIVE, firstSession.phase.value)
        assertNull(firstSession.state.value.busy)
        assertNull(firstSession.state.value.message)
        assertTrue(manager.messageLog.entries.value.any { it.level == MessageLevel.INFO })
        assertTrue(first.callsOf(FakeMethod.STATUS).isNotEmpty())
        assertTrue(first.callsOf(FakeMethod.REFS).isNotEmpty())
        manager.close()
    }

    @Test
    fun t7OpeningSameRepositoryActivatesExistingTab() = runTest {
        val commits = RandomDag.generate(seed = 7, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val watchers = mutableListOf<FakeWatcher>()
        val manager = manager(mapOf(firstPath.toString() to first), watchers = watchers)

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val tab = manager.tabs.value.single()
        val session = manager.session(tab.id)
        val watcher = watchers.single()

        manager.openRepo(firstPath.resolve("."))
        advanceUntilIdle()

        assertEquals(1, manager.tabs.value.size)
        assertEquals(tab.id, manager.activeId.value)
        assertSame(session, manager.activeSession.value)
        assertEquals(1, watchers.size)
        assertEquals(1, watcher.startCount)
        manager.close()
    }

    @Test
    fun closingLastTabActivatesPreviousTab() = runTest {
        val commits = RandomDag.generate(seed = 12, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!

        manager.closeTab(secondId)
        advanceUntilIdle()

        assertEquals(listOf(firstId), manager.tabs.value.map { it.id })
        assertEquals(firstId, manager.activeId.value)
        assertSame(manager.session(firstId), manager.activeSession.value)
        assertEquals(SessionPhase.ACTIVE, manager.session(firstId)!!.phase.value)
        assertNull(manager.session(secondId))
        manager.close()
    }

    @Test
    fun closingOnlyTabClearsActiveSession() = runTest {
        val commits = RandomDag.generate(seed = 13, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!

        manager.closeTab(firstId)
        advanceUntilIdle()

        assertTrue(manager.tabs.value.isEmpty())
        assertNull(manager.activeId.value)
        assertNull(manager.activeSession.value)
        assertNull(manager.session(firstId))
        manager.close()
    }

    @Test
    fun closingInactiveTabKeepsActiveSession() = runTest {
        val commits = RandomDag.generate(seed = 14, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        val activeSession = manager.activeSession.value

        manager.closeTab(firstId)
        advanceUntilIdle()

        assertEquals(listOf(secondId), manager.tabs.value.map { it.id })
        assertEquals(secondId, manager.activeId.value)
        assertSame(activeSession, manager.activeSession.value)
        assertNull(manager.session(firstId))
        manager.close()
    }

    @Test
    fun closingActiveTabSkipsNeighborThatIsStillDraining() = runTest {
        val commits = RandomDag.generate(seed = 18, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val third = backend(commits, commits.first().hash)
        val manager = manager(
            mapOf(
                firstPath.toString() to first,
                secondPath.toString() to second,
                Path.of("/repo/three").toString() to third,
            ),
        )

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        manager.openRepo(Path.of("/repo/three"))
        advanceUntilIdle()
        val thirdId = manager.activeId.value!!
        manager.activate(firstId)
        advanceUntilIdle()

        val secondSession = manager.session(secondId)!!
        val gate = second.pause(FakeMethod.STAGE)
        secondSession.stage(listOf("b.txt"))
        advanceUntilIdle()
        gate.awaitEntered()

        manager.closeTab(secondId)
        advanceUntilIdle()
        assertTrue(manager.tabs.value.any { it.id == secondId })

        manager.closeTab(firstId)
        advanceUntilIdle()

        assertEquals(thirdId, manager.activeId.value)
        assertSame(manager.session(thirdId), manager.activeSession.value)

        gate.release()
        advanceUntilIdle()

        assertEquals(listOf(thirdId), manager.tabs.value.map { it.id })
        manager.close()
    }

    @Test
    fun reopeningWhileTabDrainsDefersUntilCloseCompletes() = runTest {
        val commits = RandomDag.generate(seed = 15, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        val firstSession = manager.session(firstId)!!
        val gate = first.pause(FakeMethod.STAGE)

        firstSession.stage(listOf("a.txt"))
        advanceUntilIdle()
        gate.awaitEntered()
        manager.closeTab(firstId)
        advanceUntilIdle()

        assertEquals(listOf(firstId), manager.tabs.value.map { it.id })
        assertNull(manager.activeId.value)

        manager.openRepo(firstPath)
        advanceUntilIdle()

        assertEquals(listOf(firstId), manager.tabs.value.map { it.id })
        assertNull(manager.activeId.value)

        gate.release()
        advanceUntilIdle()

        val reopened = manager.tabs.value.single()
        assertTrue(reopened.id != firstId)
        assertEquals(firstPath.toString(), reopened.path)
        assertEquals(reopened.id, manager.activeId.value)
        assertEquals(reopened.id, manager.activeSession.value?.id)
        assertNull(manager.session(firstId))
        assertEquals(SessionPhase.ACTIVE, manager.activeSession.value?.phase?.value)
        manager.close()
    }

    @Test
    fun restoreDeduplicatesPathsAndActivatesRepresentative() = runTest {
        val commits = RandomDag.generate(seed = 16, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val persistence = InMemorySessionPersistence(
            PersistedSessionState(
                tabs = listOf(
                    TabState("a", firstPath.toString(), query = "author:Ada"),
                    TabState("b", firstPath.resolve(".").toString(), query = "msg:other"),
                    TabState("c", secondPath.toString()),
                ),
                activeId = "b",
            ),
        )
        val manager = manager(
            mapOf(firstPath.toString() to first, secondPath.toString() to second),
            persistence = persistence,
            restore = true,
        )
        advanceUntilIdle()

        assertEquals(listOf("a", "c"), manager.tabs.value.map { it.id })
        assertEquals("a", manager.activeId.value)
        assertEquals("author:Ada", manager.session("a")!!.tab.value.query)
        assertNull(manager.session("b"))
        assertNull(manager.session("c"))
        assertTrue(first.callsOf(FakeMethod.REFS).isNotEmpty())
        assertTrue(second.calls.isEmpty())
        manager.close()
    }

    @Test
    fun restoreWithUnknownActiveIdKeepsNoActiveSession() = runTest {
        val commits = RandomDag.generate(seed = 17, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val persistence = InMemorySessionPersistence(
            PersistedSessionState(
                tabs = listOf(TabState("a", firstPath.toString())),
                activeId = "missing",
            ),
        )
        val manager = manager(
            mapOf(firstPath.toString() to first),
            persistence = persistence,
            restore = true,
        )
        advanceUntilIdle()

        assertEquals(listOf("a"), manager.tabs.value.map { it.id })
        assertNull(manager.activeId.value)
        assertNull(manager.activeSession.value)
        assertNull(manager.session("a"))
        assertTrue(first.calls.isEmpty())
        manager.close()
    }

    @Test
    fun restoredUnavailableRepositoryReportsErrorAndStaysActive() = runTest {
        val missingPath = Path.of("/repo/missing")
        val persistence = InMemorySessionPersistence(
            PersistedSessionState(
                tabs = listOf(TabState("missing", missingPath.toString())),
                activeId = "missing",
            ),
        )
        val manager = manager(emptyMap(), persistence = persistence, restore = true)
        advanceUntilIdle()

        assertEquals(listOf("missing"), manager.tabs.value.map { it.id })
        assertEquals("missing", manager.activeId.value)
        val session = assertNotNull(manager.activeSession.value)
        assertTrue(session.state.value.load is LoadState.Error)
        assertTrue(session.state.value.message is UserMessage.Error)
        manager.close()
    }

    @Test
    fun restoreSkipsInvalidPathsAndReportsThem() = runTest {
        val persistence = InMemorySessionPersistence(
            PersistedSessionState(
                tabs = listOf(
                    TabState("bad", "\u0000invalid"),
                    TabState("good", firstPath.toString()),
                ),
                activeId = "bad",
            ),
        )
        val manager = manager(emptyMap(), persistence = persistence, restore = true)
        advanceUntilIdle()

        assertEquals(listOf("good"), manager.tabs.value.map { it.id })
        assertNull(manager.activeId.value)
        assertNull(manager.activeSession.value)
        assertNotNull(manager.persistenceError.value)
        manager.close()
    }

    @Test
    fun closingActiveTabActivatesNeighborAndRemovesTab() = runTest {
        val commits = RandomDag.generate(seed = 8, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        manager.activate(firstId)
        advanceUntilIdle()

        manager.closeTab(firstId)
        advanceUntilIdle()

        assertEquals(listOf(secondId), manager.tabs.value.map { it.id })
        assertEquals(secondId, manager.activeId.value)
        assertSame(manager.session(secondId), manager.activeSession.value)
        assertNull(manager.session(firstId))
        manager.close()
    }

    @Test
    fun closingDuringWriteWaitsForDrainThenRemovesTab() = runTest {
        val commits = RandomDag.generate(seed = 9, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val manager = manager(mapOf(firstPath.toString() to first, secondPath.toString() to second))

        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        manager.activate(firstId)
        advanceUntilIdle()
        val firstSession = manager.session(firstId)!!
        val snapshot = assertNotNull(firstSession.state.value.graph)

        val gate = first.pause(FakeMethod.STAGE)
        val write = firstSession.stage(listOf("a.txt"))
        advanceUntilIdle()
        gate.awaitEntered()

        manager.closeTab(firstId)
        advanceUntilIdle()

        assertEquals(setOf(firstId, secondId), manager.tabs.value.map { it.id }.toSet())
        assertEquals(secondId, manager.activeId.value)
        assertEquals(SessionPhase.DRAINING, firstSession.phase.value)
        assertTrue(write.isActive)

        gate.release()
        advanceUntilIdle()

        assertTrue(write.isCompleted)
        assertEquals(listOf(secondId), manager.tabs.value.map { it.id })
        assertNull(manager.session(firstId))
        assertEquals(SessionPhase.INACTIVE, firstSession.phase.value)
        assertNull(firstSession.state.value.graph)
        assertNull(firstSession.state.value.message)
        assertEquals(MessageLevel.INFO, manager.messageLog.entries.value.last().level)
        manager.close()
    }

    @Test
    fun persistenceRoundTripRestoresTabsAndLoadsOnlyActiveTab() = runTest {
        val commits = RandomDag.generate(seed = 10, commitCount = 20)
        val first = backend(commits, commits.first().hash)
        val second = backend(commits, commits.first().hash)
        val directory = Files.createTempDirectory("freetree-session-state")
        val persistence = JsonSessionPersistence(directory.resolve("state.json"))
        val backends = mapOf(firstPath.toString() to first, secondPath.toString() to second)

        val manager = manager(backends, persistence = persistence, restore = true)
        manager.openRepo(firstPath)
        advanceUntilIdle()
        val firstId = manager.activeId.value!!
        manager.session(firstId)!!.applyQuery("author:Ada")
        manager.session(firstId)!!.setHiddenRefs(setOf("refs/heads/main"))
        manager.session(firstId)!!.selectCommit(commits[2].hash)
        advanceUntilIdle()
        manager.openRepo(secondPath)
        advanceUntilIdle()
        val secondId = manager.activeId.value!!
        manager.session(secondId)!!.applyQuery("msg:commit")
        advanceUntilIdle()

        val expectedTabs = manager.tabs.value
        manager.close()
        first.clearCalls()
        second.clearCalls()

        val restored = manager(backends, persistence = persistence, restore = true)
        assertEquals(expectedTabs, restored.tabs.value)
        assertEquals(secondId, restored.activeId.value)
        assertNotNull(restored.session(secondId))
        assertNull(restored.session(firstId))
        advanceUntilIdle()
        assertTrue(first.calls.isEmpty())
        assertTrue(second.callsOf(FakeMethod.REFS).isNotEmpty())

        restored.activate(firstId)
        advanceUntilIdle()
        assertEquals("author:Ada", restored.session(firstId)!!.tab.value.query)
        assertEquals(setOf("refs/heads/main"), restored.session(firstId)!!.tab.value.hiddenRefs)
        assertTrue(first.callsOf(FakeMethod.REFS).isNotEmpty())
        restored.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun malformedPersistenceIsReportedInsteadOfSwallowed() = runTest {
        val directory = Files.createTempDirectory("freetree-session-bad-state")
        val file = directory.resolve("state.json")
        Files.writeString(file, "{not valid json")
        val manager = manager(emptyMap(), persistence = JsonSessionPersistence(file), restore = true)

        assertNotNull(manager.persistenceError.value)
        assertTrue(manager.tabs.value.isEmpty())
        manager.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun persistenceSaveFailureIsReported() = runTest {
        val commits = RandomDag.generate(seed = 11, commitCount = 10)
        val first = backend(commits, commits.first().hash)
        val persistence = object : SessionPersistence {
            override fun load(): PersistedSessionState? = null
            override fun save(state: PersistedSessionState) {
                throw java.io.IOException("read-only state directory")
            }
        }
        val manager = manager(mapOf(firstPath.toString() to first), persistence = persistence)

        manager.openRepo(firstPath)
        advanceUntilIdle()

        val error = assertNotNull(manager.persistenceError.value)
        assertTrue(error.contains("Could not save"))
        manager.close()
    }

    private fun backend(commits: List<RawCommit>, headCommit: String): FakeGitBackend = FakeGitBackend(
        commits = commits,
        refs = listOf(
            GitRef(
                fullName = "refs/heads/main",
                shortName = "main",
                kind = RefKind.LOCAL_BRANCH,
                commit = headCommit,
                isCurrentBranch = true,
                upstream = null,
            ),
        ),
        head = HeadInfo(headCommit, "main"),
    )

    private fun TestScope.manager(
        backends: Map<String, FakeGitBackend>,
        watchers: MutableList<FakeWatcher> = mutableListOf(),
        persistence: SessionPersistence = InMemorySessionPersistence(),
        restore: Boolean = false,
    ): SessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SessionManager(
            backendFactory = { path -> backends.getValue(path.toString()) },
            watcherFactory = { FakeWatcher().also(watchers::add) },
            persistence = persistence,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            readDispatcher = dispatcher,
            writeDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
            restore = restore,
        )
    }
}
