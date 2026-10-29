package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.LoadState
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SessionWithoutWatcherAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var manager: SessionManager
    private lateinit var session: RepoSession
    private val silentWatcher = SilentWatcher()

    @BeforeTest
    fun setUp() = runBlocking {
        repo = GitTestRepo.create()
        repo.writeFile("a.txt", "one\n")
        repo.commitAll("root")
        repo.createBranch("feature/external").requireSuccess()
        manager = SessionManager(
            backendFactory = { path -> CliGitBackend.open(path) },
            watcherFactory = { silentWatcher },
            persistence = InMemorySessionPersistence(),
            restore = false,
        )
        manager.openRepo(repo.workTree)
        session = await("active session") { manager.activeSession.value }
        awaitLoaded(session)
        assertTrue(silentWatcher.started, "session did not start the watcher")
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        repo.close()
    }

    @Test
    fun explicitRefreshAndWritesStayCorrectWithoutWatcherEvents() = runBlocking {
        repo.writeFile("a.txt", "external change\n")
        session.refresh()
        await("external status") {
            session.state.value.status?.entries?.firstOrNull { it.path == "a.txt" }
                ?.takeIf { it.worktree == 'M' }
        }
        assertEquals("M", repo.gitOk("status", "--porcelain=v1", "--", "a.txt").substring(0, 1))

        repo.createBranch("feature/created-externally").requireSuccess()
        session.refresh()
        await("external refs") {
            session.state.value.refs.firstOrNull { it.fullName == "refs/heads/feature/created-externally" }
        }

        session.stage(listOf("a.txt")).join()
        await("staged status") {
            session.state.value.status?.entries?.firstOrNull { it.path == "a.txt" }
                ?.takeIf { it.index == 'M' && it.worktree == ' ' }
        }
        assertEquals(setOf("a.txt"), repo.stagedPaths())

        session.unstage(listOf("a.txt")).join()
        await("unstaged status") {
            session.state.value.status?.entries?.firstOrNull { it.path == "a.txt" }
                ?.takeIf { it.index == ' ' && it.worktree == 'M' }
        }
        assertTrue(repo.stagedPaths().isEmpty())

        session.stage(listOf("a.txt")).join()
        val previousHead = repo.headHash()
        session.commit("commit without watcher").join()
        await("commit head") { session.state.value.head?.commit?.takeIf { it != previousHead } }
        val head = repo.headHash()
        assertEquals("commit without watcher", repo.gitOk("log", "-1", "--format=%s"))
        assertTrue(repo.statusV1().isEmpty())
        await("graph after commit") {
            session.state.value.graph?.takeIf { it.complete && it.indexOfHash(head) >= 0 }
        }
        assertEquals(head, session.state.value.head?.commit)

        repo.git("checkout", "--quiet", "feature/external").requireSuccess()
        session.refresh()
        await("checkout head") { session.state.value.head?.branchShortName?.takeIf { it == "feature/external" } }
        await("graph after checkout") {
            session.state.value.graph?.takeIf { it.complete && it.indexOfHash(repo.headHash()) >= 0 }
        }
        assertTrue(silentWatcher.eventsNeverEmitted)
    }

    @Test
    fun watcherStartFailureFallsBackToPollingWithoutBlockingRefreshes() = runBlocking {
        val failingWatcher = FailingWatcher()
        val failingManager = SessionManager(
            backendFactory = { path -> CliGitBackend.open(path) },
            watcherFactory = { failingWatcher },
            persistence = InMemorySessionPersistence(),
            restore = false,
        )
        try {
            failingManager.openRepo(repo.workTree)
            val failingSession = await("active failing session") { failingManager.activeSession.value }
            awaitLoaded(failingSession)
            assertEquals(1, failingWatcher.starts)
            assertTrue(failingWatcher.stopCount >= 1, "failed start must stop the watcher")
            val message = await("watcher warning") {
                (failingSession.state.value.message as? UserMessage.Error)
                    ?.takeIf { it.text.contains("watcher", ignoreCase = true) }
            }
            assertTrue(message.text.isNotBlank())

            repo.writeFile("a.txt", "polled change\n")
            await("polled status", timeoutMillis = 15_000) {
                failingSession.state.value.status?.entries?.firstOrNull { it.path == "a.txt" }
                    ?.takeIf { it.worktree == 'M' }
            }

            failingSession.refresh()
            await("explicit refresh", timeoutMillis = 15_000) {
                failingSession.state.value.graph?.takeIf { it.complete }
            }
            assertEquals(LoadState.Idle, failingSession.state.value.load)
        } finally {
            failingManager.close()
        }
    }

    private suspend fun awaitLoaded(session: RepoSession) {
        await("initial load", timeoutMillis = 20_000) {
            val state = session.state.value
            state
                .takeIf {
                    it.graph?.complete == true &&
                        it.status != null &&
                        it.refs.isNotEmpty() &&
                        it.head?.commit != null
                }
        }
    }

    private fun <T : Any> await(
        description: String,
        timeoutMillis: Long = 10_000,
        block: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            block()?.let { return it }
            Thread.sleep(25)
        }
        error("Timed out waiting for $description")
    }

    private class SilentWatcher : RepoWatcher {
        @Volatile
        var started = false
            private set

        @Volatile
        var eventsNeverEmitted = true
            private set

        override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
            started = true
        }

        override fun stop() = Unit
    }

    private class FailingWatcher : RepoWatcher {
        @Volatile
        var starts = 0
            private set

        @Volatile
        var stopCount = 0
            private set

        override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
            starts += 1
            throw IllegalStateException("watcher unavailable for acceptance")
        }

        override fun stop() {
            stopCount += 1
        }
    }
}
