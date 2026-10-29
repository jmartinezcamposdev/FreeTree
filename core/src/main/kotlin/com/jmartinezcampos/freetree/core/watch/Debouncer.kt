package com.jmartinezcampos.freetree.core.watch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class Debouncer(
    scope: CoroutineScope,
    private val windowMillis: Long = 300,
    private val maxWaitMillis: Long = 2_000,
    private val onEmit: (WatchEvent) -> Unit,
) {
    private val events = Channel<WatchEvent>(Channel.UNLIMITED)
    private val job = scope.launch { consume() }

    fun offer(event: WatchEvent) {
        events.trySend(event)
    }

    fun close() {
        events.close()
        job.cancel()
    }

    private suspend fun consume() {
        try {
            while (true) {
                var pending = events.receive()
                try {
                    withTimeout(maxWaitMillis) {
                        while (true) {
                            val next = withTimeoutOrNull(windowMillis) { events.receive() } ?: break
                            pending = merge(pending, next)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Maximum wait elapsed; emit what has accumulated.
                }
                onEmit(pending)
            }
        } catch (_: ClosedReceiveChannelException) {
            // The watcher was stopped.
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun merge(first: WatchEvent, second: WatchEvent): WatchEvent =
        if (first == WatchEvent.REFS_CHANGED || second == WatchEvent.REFS_CHANGED) {
            WatchEvent.REFS_CHANGED
        } else {
            WatchEvent.STATUS_CHANGED
        }
}
