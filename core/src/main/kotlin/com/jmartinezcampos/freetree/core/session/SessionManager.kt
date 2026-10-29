package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.watch.JdkRepoWatcher
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class SessionManager(
    private val backendFactory: suspend (Path) -> GitBackend = { CliGitBackend.open(it) },
    private val watcherFactory: (Path) -> RepoWatcher = { JdkRepoWatcher() },
    private val persistence: SessionPersistence = JsonSessionPersistence(defaultSessionStatePath()),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    restore: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private val tabStates = LinkedHashMap<String, TabState>()
    private val sessions = LinkedHashMap<String, RepoSession>()
    private val closingTabs = LinkedHashSet<String>()

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _activeSession = MutableStateFlow<RepoSession?>(null)
    val activeSession: StateFlow<RepoSession?> = _activeSession.asStateFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    @Volatile
    private var closed = false

    init {
        if (restore) restoreTabs()
    }

    fun openRepo(path: Path) {
        if (closed) return
        val normalized = path.toAbsolutePath().normalize()
        val existing = synchronized(lock) {
            tabStates.values.firstOrNull { normalizePath(it.path) == normalized }
        }
        if (existing != null) {
            activate(existing.id)
            return
        }

        val id = UUID.randomUUID().toString()
        val tab = TabState(id = id, path = normalized.toString())
        synchronized(lock) {
            tabStates[id] = tab
            _tabs.value = tabStates.values.toList()
        }
        persist()
        activate(id)
    }

    fun closeTab(id: String) {
        if (closed) return
        val plan = synchronized(lock) {
            if (id in closingTabs) return
            val tab = tabStates[id] ?: return
            closingTabs += id
            ClosePlan(
                tab = tab,
                session = sessions[id],
                wasActive = _activeId.value == id,
                neighborId = neighborLocked(id),
            )
        }

        plan.session?.deactivate()
        val pending = plan.session?.beginClose().orEmpty()

        if (plan.wasActive) {
            if (plan.neighborId != null) {
                activateInternal(plan.neighborId, persist = false)
            } else {
                synchronized(lock) {
                    _activeId.value = null
                    _activeSession.value = null
                }
            }
        }

        if (pending.isEmpty()) {
            finalizeClose(plan)
        } else {
            scope.launch {
                pending.joinAll()
                finalizeClose(plan)
            }
        }
    }

    fun activate(id: String) {
        activateInternal(id, persist = true)
    }

    fun session(id: String): RepoSession? = synchronized(lock) { sessions[id] }

    override fun close() {
        if (closed) return
        closed = true
        persist()
        val loaded = synchronized(lock) { sessions.values.toList() }
        loaded.forEach { session ->
            session.deactivate()
            session.close()
        }
        scope.cancel()
        synchronized(lock) {
            sessions.clear()
            closingTabs.clear()
            tabStates.clear()
            _tabs.value = emptyList()
            _activeId.value = null
            _activeSession.value = null
        }
    }

    private fun activateInternal(id: String, persist: Boolean) {
        if (closed) return
        var previous: RepoSession? = null
        val target: RepoSession
        synchronized(lock) {
            if (_activeId.value == id || id in closingTabs) return
            val tab = tabStates[id] ?: return
            previous = _activeId.value?.let { sessions[it] }
            target = sessions.getOrPut(id) { createSession(tab) }
            _activeId.value = id
            _activeSession.value = target
        }
        previous?.deactivate()
        target.activate()
        if (persist) persist()
    }

    private fun createSession(tab: TabState): RepoSession = RepoSession(
        id = tab.id,
        path = Path.of(tab.path),
        initialTab = tab,
        backendFactory = backendFactory,
        watcherFactory = watcherFactory,
        readDispatcher = readDispatcher,
        writeDispatcher = writeDispatcher,
        clockMillis = clockMillis,
        onTabChanged = ::onSessionTabChanged,
    )

    private fun onSessionTabChanged(tab: TabState) {
        var changed = false
        synchronized(lock) {
            val current = tabStates[tab.id] ?: return
            if (current != tab) {
                tabStates[tab.id] = tab
                _tabs.value = tabStates.values.toList()
                changed = true
            }
        }
        if (changed) persist()
    }

    private fun finalizeClose(plan: ClosePlan) {
        val closingSession = synchronized(lock) {
            closingTabs.remove(plan.tab.id)
            tabStates.remove(plan.tab.id)
            val session = sessions.remove(plan.tab.id)
            _tabs.value = tabStates.values.toList()
            if (_activeId.value == plan.tab.id) {
                _activeId.value = null
                _activeSession.value = null
            }
            session
        }
        closingSession?.discardSnapshot()
        closingSession?.close()
        persist()
    }

    private fun neighborLocked(id: String): String? {
        val ids = tabStates.keys.toList()
        val index = ids.indexOf(id)
        if (index < 0) return null
        return ids.getOrNull(index + 1) ?: ids.getOrNull(index - 1)
    }

    private fun restoreTabs() {
        val loaded = try {
            persistence.load()
        } catch (e: Throwable) {
            _persistenceError.value = "Could not read session state: ${e.message ?: e::class.simpleName}"
            null
        }
        if (loaded == null) return

        synchronized(lock) {
            loaded.tabs.forEach { tab ->
                if (tab.id.isNotBlank()) tabStates.putIfAbsent(tab.id, tab)
            }
            _tabs.value = tabStates.values.toList()
        }

        val active = loaded.activeId?.takeIf { id -> synchronized(lock) { id in tabStates } }
        if (active != null) activateInternal(active, persist = false)
    }

    private fun persist() {
        val snapshot = synchronized(lock) {
            PersistedSessionState(
                tabs = tabStates.values.toList(),
                activeId = _activeId.value,
            )
        }
        try {
            persistence.save(snapshot)
            _persistenceError.value = null
        } catch (e: Throwable) {
            _persistenceError.value = "Could not save session state: ${e.message ?: e::class.simpleName}"
        }
    }

    private data class ClosePlan(
        val tab: TabState,
        val session: RepoSession?,
        val wasActive: Boolean,
        val neighborId: String?,
    )
}

private fun normalizePath(value: String): Path? = try {
    Path.of(value).toAbsolutePath().normalize()
} catch (e: InvalidPathException) {
    null
}
