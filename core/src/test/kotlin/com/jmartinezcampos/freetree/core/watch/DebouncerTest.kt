package com.jmartinezcampos.freetree.core.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {
    @Test
    fun emitsAfterTheTrailingWindow() = runTest {
        val events = mutableListOf<WatchEvent>()
        val debouncer = Debouncer(this, onEmit = { events += it })

        debouncer.offer(WatchEvent.STATUS_CHANGED)
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(WatchEvent.STATUS_CHANGED), events)
        debouncer.close()
    }

    @Test
    fun coalescesStatusEventsAndRefsAbsorbsStatus() = runTest {
        val events = mutableListOf<WatchEvent>()
        val debouncer = Debouncer(this, onEmit = { events += it })

        debouncer.offer(WatchEvent.STATUS_CHANGED)
        runCurrent()
        advanceTimeBy(100)
        debouncer.offer(WatchEvent.REFS_CHANGED)
        advanceTimeBy(100)
        debouncer.offer(WatchEvent.STATUS_CHANGED)
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf(WatchEvent.REFS_CHANGED), events)
        debouncer.close()
    }

    @Test
    fun statusEventsCoalesceToOne() = runTest {
        val events = mutableListOf<WatchEvent>()
        val debouncer = Debouncer(this, onEmit = { events += it })

        repeat(5) {
            debouncer.offer(WatchEvent.STATUS_CHANGED)
            advanceTimeBy(50)
        }
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf(WatchEvent.STATUS_CHANGED), events)
        debouncer.close()
    }

    @Test
    fun emitsAtTheMaximumWaitEvenWhileEventsKeepArriving() = runTest {
        val events = mutableListOf<WatchEvent>()
        val debouncer = Debouncer(this, onEmit = { events += it })

        debouncer.offer(WatchEvent.STATUS_CHANGED)
        runCurrent()
        repeat(19) {
            advanceTimeBy(100)
            debouncer.offer(WatchEvent.STATUS_CHANGED)
            runCurrent()
        }
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(WatchEvent.STATUS_CHANGED, events.single())
        assertEquals(2_000, testScheduler.currentTime)
        debouncer.close()
    }

    @Test
    fun closeDropsPendingEvents() = runTest {
        val events = mutableListOf<WatchEvent>()
        val debouncer = Debouncer(this, onEmit = { events += it })

        debouncer.offer(WatchEvent.REFS_CHANGED)
        debouncer.close()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(events.isEmpty())
    }
}
