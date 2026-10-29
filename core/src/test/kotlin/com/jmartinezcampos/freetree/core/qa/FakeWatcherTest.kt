package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakeWatcherTest {

    private val dirs = FakeGitBackend.defaultGitDirs()

    @Test
    fun startAndStopAreObservable() {
        val watcher = FakeWatcher()
        val events = mutableListOf<WatchEvent>()
        val callback: (WatchEvent) -> Unit = { events += it }

        watcher.start(dirs, watchWorkTree = true, onEvent = callback)

        assertTrue(watcher.isRunning)
        assertEquals(1, watcher.startCount)
        assertEquals(0, watcher.stopCount)
        assertEquals(dirs, watcher.lastStart?.dirs)
        assertEquals(true, watcher.lastStart?.watchWorkTree)
        assertSame(callback, watcher.lastCallback)

        watcher.stop()

        assertFalse(watcher.isRunning)
        assertEquals(1, watcher.stopCount)
        assertSame(callback, watcher.lastCallback)
    }

    @Test
    fun emitDeliversOnlyWhileRunning() {
        val watcher = FakeWatcher()
        val events = mutableListOf<WatchEvent>()
        watcher.start(dirs, watchWorkTree = false, onEvent = events::add)

        assertTrue(watcher.emit(WatchEvent.REFS_CHANGED))
        watcher.emitAll(WatchEvent.STATUS_CHANGED, WatchEvent.REFS_CHANGED)

        assertEquals(
            listOf(WatchEvent.REFS_CHANGED, WatchEvent.STATUS_CHANGED, WatchEvent.REFS_CHANGED),
            events,
        )
        assertEquals(events, watcher.emittedEvents)

        watcher.stop()

        assertFalse(watcher.emit(WatchEvent.STATUS_CHANGED))
        assertEquals(3, events.size)
        assertEquals(3, watcher.emittedEvents.size)
        assertEquals(false, watcher.lastStart?.watchWorkTree)

        watcher.start(dirs, watchWorkTree = true, onEvent = events::add)
        assertTrue(watcher.isRunning)
        assertEquals(2, watcher.startCount)
    }

    @Test
    fun recordedDirsCanPointAtFakeRepo() {
        val custom = GitDirs(
            workTree = Path.of("fake-work-tree"),
            gitDir = Path.of("fake-work-tree", ".git"),
            commonDir = Path.of("fake-work-tree", ".git"),
        )
        val watcher = FakeWatcher()
        watcher.start(custom, watchWorkTree = true) {}

        assertEquals(custom, watcher.lastStart?.dirs)
    }
}
