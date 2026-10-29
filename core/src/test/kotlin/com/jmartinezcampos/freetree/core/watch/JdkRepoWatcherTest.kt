package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitTestRepo
import com.jmartinezcampos.freetree.core.model.GitDirs
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class JdkRepoWatcherTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("freetree-watcher-")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
    }

    @Test
    fun reportsStartFailureInsteadOfSwallowingIt() {
        val missing = root.resolve("missing-git-dir")
        val failures = mutableListOf<Throwable>()
        val watcher = JdkRepoWatcher(onFailure = { failures += it })

        watcher.start(GitDirs(workTree = root, gitDir = missing, commonDir = missing), watchWorkTree = false) {}

        assertTrue(failures.isNotEmpty())
        assertNotNull(watcher.lastStartFailure)
        watcher.stop()
    }

    @Test
    fun reportsRefsStatusAndWorkTreeChanges() {
        val dirs = fakeRepository()
        val events = CopyOnWriteArrayList<WatchEvent>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val watcher = JdkRepoWatcher(onFailure = { failures += it })
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            Files.writeString(dirs.commonDir.resolve("refs/heads/main"), "0123456789")
            assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "refs event missing: $events")

            Files.writeString(dirs.gitDir.resolve("index"), "index")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "status event missing: $events")

            events.clear()
            Files.writeString(dirs.workTree.resolve("src/Main.kt"), "class Main")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "work-tree event missing: $events")
            assertTrue(failures.isEmpty(), failures.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun watchesNestedRefDirectoriesWithWorkTreeLikeNames() {
        val dirs = fakeRepository()
        Files.createDirectories(dirs.commonDir.resolve("refs/heads/build"))
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = false, onEvent = { events += it })
        try {
            Files.writeString(dirs.commonDir.resolve("refs/heads/build/feature"), "0123456789")
            assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "refs event missing: $events")
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun ignoresLockFilesAndUnwatchedGitInternals() {
        val dirs = fakeRepository()
        Files.createDirectories(dirs.gitDir.resolve("objects/ab"))
        Files.createDirectories(dirs.gitDir.resolve("logs"))
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            Files.writeString(dirs.gitDir.resolve("index.lock"), "lock")
            Files.writeString(dirs.gitDir.resolve("objects/ab/cdef"), "object")
            Files.writeString(dirs.gitDir.resolve("logs/HEAD"), "reflog")
            Files.writeString(dirs.workTree.resolve("foo.lock"), "lock")

            Thread.sleep(1_200)
            assertTrue(events.isEmpty(), events.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun excludesBuildDirectoriesFromTheWorkTree() {
        val dirs = fakeRepository()
        Files.createDirectories(dirs.workTree.resolve("node_modules/pkg"))
        Files.createDirectories(dirs.workTree.resolve("build"))
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        try {
            Files.writeString(dirs.workTree.resolve("node_modules/pkg/file.txt"), "ignored")
            Files.writeString(dirs.workTree.resolve("build/output.txt"), "ignored")
            Thread.sleep(800)
            assertTrue(events.isEmpty(), events.toString())

            Files.writeString(dirs.workTree.resolve("src/visible.txt"), "visible")
            assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "src event missing: $events")
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun reportsPartialWatchingWhenTheDirectoryCapIsReached() {
        val dirs = fakeRepository()
        repeat(5) { Files.createDirectories(dirs.workTree.resolve("dir$it")) }
        val warnings = mutableListOf<String>()
        val watcher = JdkRepoWatcher(
            onWarning = { warnings += it },
            workTreeDirectoryCap = 2,
        )

        watcher.start(dirs, watchWorkTree = true) {}
        try {
            assertTrue(watcher.partialWatch)
            assertTrue(warnings.any { it.contains("part") }, warnings.toString())
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun stopsDeliveringEventsAfterStop() {
        val dirs = fakeRepository()
        val events = CopyOnWriteArrayList<WatchEvent>()
        val watcher = JdkRepoWatcher()
        watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
        watcher.stop()

        Files.writeString(dirs.workTree.resolve("src/after-stop.txt"), "after")
        Thread.sleep(700)

        assertTrue(events.isEmpty(), events.toString())
    }

    @Test
    fun watchesARealRepositoryStatusAndBranchChanges() = runBlocking {
        val repo = GitTestRepo.create()
        try {
            repo.write("a.txt", "one\n")
            repo.commitAll("root")
            val dirs = CliGitBackend.open(repo.root, repo.gitExecutable).gitDirs()
            val events = CopyOnWriteArrayList<WatchEvent>()
            val failures = CopyOnWriteArrayList<Throwable>()
            val watcher = JdkRepoWatcher(onFailure = { failures += it })
            watcher.start(dirs, watchWorkTree = true, onEvent = { events += it })
            try {
                repo.write("a.txt", "two\n")
                assertTrue(waitFor(events) { it == WatchEvent.STATUS_CHANGED }, "status event missing: $events")

                repo.git("branch", "from-watcher")
                assertTrue(waitFor(events) { it == WatchEvent.REFS_CHANGED }, "refs event missing: $events")

                events.clear()
                repo.git("pack-refs", "--all")
                assertTrue(
                    waitFor(events) { it == WatchEvent.REFS_CHANGED },
                    "packed-refs event missing: $events",
                )
                assertTrue(failures.isEmpty(), failures.toString())
            } finally {
                watcher.stop()
            }
        } finally {
            repo.close()
        }
    }

    private fun fakeRepository(): GitDirs {
        val workTree = root.resolve("work")
        val gitDir = workTree.resolve(".git")
        Files.createDirectories(gitDir.resolve("refs/heads"))
        Files.createDirectories(gitDir.resolve("refs/tags"))
        Files.createDirectories(workTree.resolve("src"))
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n")
        return GitDirs(workTree = workTree, gitDir = gitDir, commonDir = gitDir)
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

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
