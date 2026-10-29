package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitTestRepo
import com.jmartinezcampos.freetree.core.model.GitDirs
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JdkRepoWatcherGitIntegrationTest {
    private lateinit var repo: GitTestRepo
    private lateinit var dirs: GitDirs

    @BeforeTest
    fun setUp() = runBlocking {
        repo = GitTestRepo.create()
        repo.write("a.txt", "one\n")
        repo.commitAll("root")
        dirs = CliGitBackend.open(repo.root, repo.gitExecutable).gitDirs()
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun detectsCommitsAndBranchCheckouts() {
        val events = CopyOnWriteArrayList<WatchEvent>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val watcher = JdkRepoWatcher(onFailure = { failures += it })
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            repo.write("a.txt", "two\n")
            repo.commitAll("second")
            assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "commit event missing: $events")

            settle()
            events.clear()
            repo.git("checkout", "-q", "-b", "feature")
            assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "checkout event missing: $events")
            assertTrue(failures.isEmpty(), failures.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun detectsPackedRefsFromGitGcAndKeepsWatchingLooseRefs() {
        repo.git("branch", "loose-one")
        repo.git("branch", "loose-two")
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = false, onEvent = { events += it })
        try {
            settle()
            events.clear()
            repo.git("gc", "-q")
            assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "gc event missing: $events")

            settle()
            events.clear()
            repo.git("branch", "loose-after-gc")
            assertTrue(
                waitFor(events) { it == WatchEvent.REFS_CHANGED },
                "post-gc branch event missing: $events",
            )
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun detectsIndexAndOperationFileChanges() {
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = false, onEvent = { events += it })
        try {
            settle()
            events.clear()
            repo.write("a.txt", "changed\n")
            repo.git("add", "a.txt")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "index event missing: $events")

            settle()
            events.clear()
            Files.writeString(dirs.gitDir.resolve("MERGE_HEAD"), "${repo.head()}\n")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "MERGE_HEAD missing: $events")

            settle()
            events.clear()
            val rebase = dirs.gitDir.resolve("rebase-merge")
            Files.createDirectories(rebase)
            Files.writeString(rebase.resolve("head-name"), "refs/heads/main\n")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "rebase event missing: $events")
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun ignoresGitObjectsChanges() {
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            settle()
            events.clear()
            val objects = dirs.commonDir.resolve("objects")
            Files.createDirectories(objects.resolve("zz"))
            Files.writeString(objects.resolve("zz/deadbeef"), "object")
            Thread.sleep(1_000)
            assertTrue(events.isEmpty(), events.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun debouncesWorkTreeBursts() {
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            settle()
            events.clear()
            repeat(40) { index ->
                repo.write("burst/$index.txt", "burst\n")
            }
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "burst event missing: $events")
            Thread.sleep(1_000)

            assertTrue(events.count { it == WatchEvent.STATUS_CHANGED } in 1..5, events.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun stopsAndRestartsOnTheSameRepository() {
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            repo.write("a.txt", "two\n")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "first event missing: $events")

            watcher.stop()
            settle()
            events.clear()
            repo.write("a.txt", "three\n")
            Thread.sleep(700)
            assertTrue(events.isEmpty(), events.toString())

            watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
            settle()
            repo.write("a.txt", "four\n")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "restart event missing: $events")
        } finally {
            watcher.stop()
        }
        assertTrue(waitForThreads(0), "watcher thread still alive: ${watcherThreads()}")
    }

    private fun settle() {
        Thread.sleep(500)
    }

    private fun waitFor(
        events: List<WatchEvent>,
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
