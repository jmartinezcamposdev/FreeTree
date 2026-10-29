package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import com.jmartinezcampos.freetree.core.watch.JdkRepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WatcherAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var dirs: GitDirs
    private lateinit var watcher: JdkRepoWatcher
    private val events = CopyOnWriteArrayList<WatchEvent>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val warnings = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() = runBlocking {
        repo = GitTestRepo.create()
        repo.writeFile("a.txt", "one\n")
        repo.commitAll("root")
        dirs = repo.openBackend().gitDirs()
    }

    @AfterTest
    fun tearDown() {
        if (::watcher.isInitialized) watcher.stop()
        repo.close()
    }

    @Test
    fun reportsRefsForBranchesCommitsAndCheckouts() {
        startWatcher(watchWorkTree = false)

        repo.createBranch("feature/watcher").requireSuccess()
        assertTrue(waitFor { it == WatchEvent.REFS_CHANGED }, "branch event missing: $events")

        events.clear()
        repo.commitFile("a.txt", "two\n", "second")
        assertTrue(waitFor { it == WatchEvent.REFS_CHANGED }, "commit event missing: $events")

        events.clear()
        repo.git("checkout", "--quiet", "-b", "checkout-target").requireSuccess()
        assertTrue(waitFor { it == WatchEvent.REFS_CHANGED }, "checkout event missing: $events")
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun reportsIndexAndWorkTreeStatusChanges() {
        startWatcher(watchWorkTree = true)

        repo.writeFile("a.txt", "changed\n")
        assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "work-tree event missing: $events")

        events.clear()
        repo.stage("a.txt").requireSuccess()
        assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "index event missing: $events")
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun reportsPackedRefsAfterGcAndLooseRefsAfterwards() {
        startWatcher(watchWorkTree = false)
        events.clear()

        repo.git("gc", "-q", "--prune=now").requireSuccess()
        assertTrue(waitFor { it == WatchEvent.REFS_CHANGED }, "gc event missing: $events")
        assertTrue(Files.exists(dirs.commonDir.resolve("packed-refs")), "gc did not pack refs")

        events.clear()
        repo.createBranch("loose-after-gc").requireSuccess()
        assertTrue(waitFor { it == WatchEvent.REFS_CHANGED }, "post-gc branch event missing: $events")
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun reportsOperationFilesAndRebaseDirectories() {
        startWatcher(watchWorkTree = false)

        listOf("MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD").forEach { name ->
            events.clear()
            Files.writeString(dirs.gitDir.resolve(name), "${repo.headHash()}\n")
            assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "$name event missing: $events")
        }

        listOf("rebase-merge", "rebase-apply").forEach { name ->
            events.clear()
            val directory = dirs.gitDir.resolve(name)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("head-name"), "refs/heads/main\n")
            assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "$name event missing: $events")
        }
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun ignoresObjectsAndExcludedWorkTreeDirectories() {
        val excluded = listOf(
            "node_modules",
            "build",
            "target",
            ".gradle",
            ".idea",
            "dist",
            "out",
            "__pycache__",
            ".venv",
        )
        excluded.forEach { name ->
            repo.createUntrackedFile("$name/pkg/file.txt", "ignored\n")
        }
        startWatcher(watchWorkTree = true)

        excluded.forEach { name ->
            repo.writeFile("$name/pkg/updated.txt", "ignored\n")
        }
        Files.createDirectories(dirs.commonDir.resolve("objects/zz"))
        Files.writeString(dirs.commonDir.resolve("objects/zz/deadbeef"), "object\n")
        assertTrue(eventsNoneFor(1_200), events.toString())

        repo.createUntrackedFile("src/visible.txt", "visible\n")
        assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "visible work-tree event missing: $events")
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun coalescesWorkTreeBurstsWithoutLosingTheLastOne() {
        startWatcher(watchWorkTree = true)

        repeat(40) { index ->
            repo.createUntrackedFile("burst/file-$index.txt", "burst\n")
        }
        assertTrue(waitFor { it == WatchEvent.STATUS_CHANGED }, "burst event missing: $events")
        Thread.sleep(1_000)

        assertTrue(events.all { it == WatchEvent.STATUS_CHANGED }, events.toString())
        assertTrue(events.size in 1..6, "burst was not coalesced: $events")
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test
    fun stopStopsEventsAndCleansUpItsThread() {
        val baseline = watcherThreads()
        startWatcher(watchWorkTree = true)
        assertTrue(waitForThreads(baseline + 1), "watcher thread did not start")

        watcher.stop()
        watcher.stop()
        assertTrue(waitForThreads(baseline), "watcher thread still alive: ${watcherThreads()}")

        events.clear()
        repo.writeFile("a.txt", "after stop\n")
        Thread.sleep(700)
        assertTrue(events.isEmpty(), events.toString())
    }

    private fun startWatcher(watchWorkTree: Boolean) {
        events.clear()
        failures.clear()
        warnings.clear()
        watcher = JdkRepoWatcher(
            onFailure = { failures += it },
            onWarning = { warnings += it },
        )
        watcher.start(dirs, watchWorkTree) { events += it }
        Thread.sleep(300)
        events.clear()
        assertTrue(failures.isEmpty(), failures.toString())
    }

    private fun waitFor(
        timeoutMillis: Long = 5_000,
        predicate: (WatchEvent) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (events.any(predicate)) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun eventsNoneFor(millis: Long): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (events.isNotEmpty()) return false
            Thread.sleep(25)
        }
        return events.isEmpty()
    }

    private fun watcherThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "freetree-repo-watcher" && it.isAlive }

    private fun waitForThreads(expected: Int, timeoutMillis: Long = 3_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (watcherThreads() == expected) return true
            Thread.sleep(25)
        }
        return false
    }
}
