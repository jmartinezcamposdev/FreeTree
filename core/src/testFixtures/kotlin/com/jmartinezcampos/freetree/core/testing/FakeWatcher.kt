package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent

class FakeWatcher : RepoWatcher {

    data class StartCall(val dirs: GitDirs, val watchWorkTree: Boolean)

    private val lock = Any()
    private val startLog = mutableListOf<StartCall>()
    private val emitted = mutableListOf<WatchEvent>()
    private var stopLog = 0
    private var activeCallback: ((WatchEvent) -> Unit)? = null
    private var recordedCallback: ((WatchEvent) -> Unit)? = null

    val starts: List<StartCall>
        get() = synchronized(lock) { startLog.toList() }

    val startCount: Int
        get() = synchronized(lock) { startLog.size }

    val stopCount: Int
        get() = synchronized(lock) { stopLog }

    val isRunning: Boolean
        get() = synchronized(lock) { activeCallback != null }

    val lastStart: StartCall?
        get() = synchronized(lock) { startLog.lastOrNull() }

    val lastCallback: ((WatchEvent) -> Unit)?
        get() = synchronized(lock) { recordedCallback }

    val emittedEvents: List<WatchEvent>
        get() = synchronized(lock) { emitted.toList() }

    override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
        synchronized(lock) {
            startLog += StartCall(dirs, watchWorkTree)
            activeCallback = onEvent
            recordedCallback = onEvent
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopLog++
            activeCallback = null
        }
    }

    fun emit(event: WatchEvent): Boolean {
        val callback = synchronized(lock) { activeCallback } ?: return false
        synchronized(lock) { emitted += event }
        callback(event)
        return true
    }

    fun emitAll(vararg events: WatchEvent) {
        events.forEach { emit(it) }
    }
}
