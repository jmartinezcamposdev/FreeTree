package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.LaneLayoutEngine
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.query.Query
import com.jmartinezcampos.freetree.core.query.parseQuery
import com.jmartinezcampos.freetree.core.query.resolve
import com.jmartinezcampos.freetree.core.watch.JdkRepoWatcher
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionPhase { INACTIVE, ACTIVE, DRAINING }

typealias SessionLifecycle = SessionPhase

class RepoSession(
    val id: String,
    path: Path,
    initialTab: TabState,
    private val backendFactory: suspend (Path) -> GitBackend,
    private val watcherFactory: (Path) -> RepoWatcher,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    val messageLog: MessageLog = MessageLog(),
    private val onTabChanged: (TabState) -> Unit = {},
) : AutoCloseable {
    val path: Path = path.toAbsolutePath().normalize()

    private val lock = Any()
    private val loadLock = Any()
    private val backendMutex = Mutex()
    private val graphMutex = Mutex()
    private val writeMutex = Mutex()

    private val initialQuery = parseQuery(initialTab.query)
    private val normalizedInitialTab = initialTab.copy(path = this.path.toString())
    private val _tab = MutableStateFlow(normalizedInitialTab)
    val tab: StateFlow<TabState> = _tab.asStateFlow()
    val tabState: StateFlow<TabState> get() = tab

    private val _state = MutableStateFlow(
        RepoUiState(
            query = normalizedInitialTab.query,
            queryError = initialQuery.exceptionOrNull()?.message,
            hiddenRefs = normalizedInitialTab.hiddenRefs,
            showRemotes = normalizedInitialTab.showRemotes,
            showTags = normalizedInitialTab.showTags,
            showHead = normalizedInitialTab.showHead,
            selection = normalizedInitialTab.selectedHash?.let(Selection::Commit) ?: Selection.WorkingTree,
        ),
    )
    val state: StateFlow<RepoUiState> = _state.asStateFlow()
    val uiState: StateFlow<RepoUiState> get() = state

    private val _lifecycle = MutableStateFlow(SessionPhase.INACTIVE)
    val lifecycle: StateFlow<SessionPhase> = _lifecycle.asStateFlow()
    val phase: StateFlow<SessionPhase> get() = lifecycle

    @Volatile
    private var lastValidQuery: Query = initialQuery.getOrNull() ?: Query()

    private var generation = 0
    @Volatile
    private var loadSequence = 0L
    @Volatile
    private var activeRequested = false
    private var closeRequested = false
    private var closed = false
    private var readScope: CoroutineScope? = null
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val activeWriteJobs = LinkedHashSet<Job>()

    @Volatile
    private var backend: GitBackend? = null

    @Volatile
    private var dirs: GitDirs? = null

    private val watcher: RepoWatcher by lazy { watcherFactory(path) }
    private var watcherRunning = false
    private var pollingJob: Job? = null

    private var graphLoadJob: Job? = null
    @Volatile
    private var graphSpec: LogSpec? = null
    @Volatile
    private var graphRefs: List<GitRef>? = null
    @Volatile
    private var graphHead: HeadInfo? = null

    private var selectionJob: Job? = null
    private var diffJob: Job? = null
    private var diffSequence = 0L

    @Volatile
    private var openDiffUnlimited = false

    @Volatile
    private var scrollAnchorIndex = 0

    fun activate() {
        val scope: CoroutineScope
        val activation: Int
        synchronized(lock) {
            check(!closed) { "Session $id is closed" }
            if (activeRequested) return
            activeRequested = true
            generation++
            activation = generation
            scope = CoroutineScope(SupervisorJob() + readDispatcher)
            readScope = scope
            _lifecycle.value = if (activeWriteJobs.isEmpty()) {
                SessionPhase.ACTIVE
            } else {
                SessionPhase.DRAINING
            }
        }
        scope.launch { activationReads(activation) }
    }

    fun deactivate() {
        val scope: CoroutineScope?
        synchronized(lock) {
            activeRequested = false
            generation++
            scope = readScope
            readScope = null
            selectionJob?.cancel()
            selectionJob = null
            diffJob?.cancel()
            diffJob = null
            diffSequence++
            graphLoadJob?.cancel()
            graphLoadJob = null
            pollingJob?.cancel()
            pollingJob = null
            _lifecycle.value = if (activeWriteJobs.isEmpty()) {
                SessionPhase.INACTIVE
            } else {
                SessionPhase.DRAINING
            }
            if (_state.value.graph?.complete == true) {
                _state.update { it.copy(load = LoadState.Idle) }
            }
        }
        scope?.cancel()
        stopWatcher()
    }

    fun applyQuery(text: String) {
        val parsed = parseQuery(text)
        val reload = parsed.isSuccess
        synchronized(lock) {
            if (parsed.isSuccess) {
                lastValidQuery = parsed.getOrThrow()
                _state.update { it.copy(query = text, queryError = null) }
            } else {
                val error = parsed.exceptionOrNull()?.message ?: "Invalid query"
                _state.update { it.copy(query = text, queryError = error) }
            }
        }
        updateTab { it.copy(query = text) }
        if (reload) requestGraphLoad(LoadMode.CLEAR)
    }

    fun setHiddenRefs(hidden: Set<String>) {
        _state.update {
            it.copy(hiddenRefs = hidden, labelsByCommit = visibleLabels(it.refs, hidden, it.showRemotes, it.showTags))
        }
        updateTab { it.copy(hiddenRefs = hidden) }
        requestGraphLoad(LoadMode.CLEAR)
    }

    fun toggleRef(fullName: String) {
        val hidden = _state.value.hiddenRefs
        setHiddenRefs(if (fullName in hidden) hidden - fullName else hidden + fullName)
    }

    fun setShowRemotes(show: Boolean) {
        _state.update {
            it.copy(showRemotes = show, labelsByCommit = visibleLabels(it.refs, it.hiddenRefs, show, it.showTags))
        }
        updateTab { it.copy(showRemotes = show) }
        requestGraphLoad(LoadMode.CLEAR)
    }

    fun setShowTags(show: Boolean) {
        _state.update {
            it.copy(showTags = show, labelsByCommit = visibleLabels(it.refs, it.hiddenRefs, it.showRemotes, show))
        }
        updateTab { it.copy(showTags = show) }
        requestGraphLoad(LoadMode.CLEAR)
    }

    fun setShowHead(show: Boolean) {
        _state.update { it.copy(showHead = show) }
        updateTab { it.copy(showHead = show) }
        requestGraphLoad(LoadMode.CLEAR)
    }

    fun select(selection: Selection) {
        val effective = if (selection is Selection.None) Selection.WorkingTree else selection
        val selectedHash = (effective as? Selection.Commit)?.hash
        synchronized(lock) {
            selectionJob?.cancel()
            selectionJob = null
            diffJob?.cancel()
            diffJob = null
            diffSequence++
            openDiffUnlimited = false
            _state.update {
                it.copy(
                    selection = effective,
                    commitDetail = null,
                    changedFiles = emptyList(),
                    openDiff = null,
                    openDiffTarget = null,
                )
            }
        }
        updateTab { it.copy(selectedHash = selectedHash) }
        if (effective is Selection.Commit) loadCommitDetails(effective.hash)
    }

    fun selectWorkingTree() = select(Selection.WorkingTree)

    fun selectCommit(hash: String) = select(Selection.Commit(hash))

    fun openDiff(target: DiffTarget, unlimited: Boolean = false) {
        val context = activeReadContext()
        synchronized(lock) {
            diffSequence++
            val sequence = diffSequence
            openDiffUnlimited = unlimited
            diffJob?.cancel()
            diffJob = null
            _state.update { it.copy(openDiffTarget = target, openDiff = null) }
            if (context == null || !isCurrentLocked(context.activation)) return
            diffJob = context.scope.launch {
                val backend = try {
                    git()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    reportDiffError(context.activation, sequence, e)
                    return@launch
                }
                val diff = try {
                    backend.diff(target, unlimited)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    reportDiffError(context.activation, sequence, e)
                    // The path may have changed on disk; bring the changes panel back in sync.
                    refreshStatus(context.activation)
                    return@launch
                }
                if (!isCurrentDiff(context.activation, sequence)) return@launch
                publishRead(context.activation) { it.copy(openDiff = diff, openDiffTarget = target) }
            }
        }
    }

    fun closeDiff() {
        synchronized(lock) {
            diffSequence++
            openDiffUnlimited = false
            diffJob?.cancel()
            diffJob = null
            _state.update { it.copy(openDiff = null, openDiffTarget = null) }
        }
    }

    fun setScrollAnchor(hash: String?, index: Int) {
        scrollAnchorIndex = index.coerceAtLeast(0)
        updateTab { it.copy(scrollAnchorHash = hash) }
    }

    fun refresh() {
        val context = activeReadContext() ?: return
        context.scope.launch {
            if (!isCurrent(context.activation)) return@launch
            refreshRefsAndHead(context.activation)
            requestGraphLoad(LoadMode.REFRESH)
            refreshStatus(context.activation)
        }
    }

    fun stage(paths: List<String>): Job {
        if (paths.isEmpty()) return rejected("No paths to stage.")
        val filtered = withoutConflicts(paths) ?: return rejected("Cannot stage unmerged paths.")
        return launchWrite(BusyOp.STAGE) { it.stage(filtered) }
    }

    fun unstage(paths: List<String>): Job {
        if (paths.isEmpty()) return rejected("No paths to unstage.")
        val filtered = withoutConflicts(paths) ?: return rejected("Cannot unstage unmerged paths.")
        return launchWrite(BusyOp.STAGE) { it.unstage(filtered) }
    }

    fun discardStaged(paths: List<String>): Job {
        if (paths.isEmpty()) return rejected("No paths to discard.")
        val filtered = withoutConflicts(paths) ?: return rejected("Cannot discard unmerged paths.")
        return launchWrite(BusyOp.DISCARD) { it.discardStaged(filtered) }
    }

    fun discardUnstaged(paths: List<String>): Job {
        if (paths.isEmpty()) return rejected("No paths to discard.")
        val filtered = withoutConflicts(paths) ?: return rejected("Cannot discard unmerged paths.")
        return launchWrite(BusyOp.DISCARD) { it.discardUnstaged(filtered) }
    }

    fun commit(message: String): Job {
        if (message.isBlank()) return rejected("Commit message is empty.")
        val status = _state.value.status ?: return rejected("Working status is not loaded yet.")
        if (status.entries.any { it.kind == StatusKind.UNMERGED }) {
            return rejected("Resolve conflicts before committing.")
        }
        val staged = status.entries.count { it.index != ' ' && it.index != '?' }
        if (staged == 0) return rejected("Nothing staged to commit.")
        return launchWrite(BusyOp.COMMIT) { it.commit(message) }
    }

    fun push(): Job = launchWrite(BusyOp.PUSH) { it.push() }

    fun pull(): Job = launchWrite(BusyOp.PULL) { it.pull() }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun publishMessage(message: UserMessage) {
        messageLog.record(clockMillis(), path, message)
        _state.update {
            it.copy(message = message as? UserMessage.Error)
        }
    }

    private fun publishReadMessage(activation: Int, message: UserMessage) {
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            messageLog.record(clockMillis(), path, message)
            _state.update {
                it.copy(message = message as? UserMessage.Error)
            }
        }
    }

    internal fun beginClose(): List<Job> = synchronized(lock) {
        closeRequested = true
        activeWriteJobs.filterNot { it.isCompleted }
    }

    internal fun discardSnapshot() {
        synchronized(lock) {
            graphSpec = null
            graphRefs = null
            graphHead = null
            openDiffUnlimited = false
            _state.update {
                it.copy(
                    graph = null,
                    load = LoadState.Idle,
                    commitDetail = null,
                    changedFiles = emptyList(),
                    openDiff = null,
                    openDiffTarget = null,
                )
            }
        }
    }

    override fun close() {
        deactivate()
        synchronized(lock) {
            closeRequested = true
            closed = true
            readScope?.cancel()
            readScope = null
            pollingJob?.cancel()
            pollingJob = null
        }
        stopWatcher()
        writeScope.cancel()
    }

    private suspend fun activationReads(activation: Int) {
        val backend = try {
            git()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportReadError(activation, "Repository", e)
            publishLoadIfEmpty(activation, e)
            return
        }

        val repositoryDirs = try {
            currentDirs(backend)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportReadError(activation, "Repository paths", e)
            null
        }
        if (repositoryDirs != null) startWatcher(activation, repositoryDirs)

        coroutineScope {
            val refsRead = async { readOrReport(activation, "Refs") { backend.refs() } }
            val headRead = async { readOrReport(activation, "HEAD") { backend.head() } }
            val statusRead = async { readOrReport(activation, "Status") { backend.status() } }

            val refs = refsRead.await()
            val head = headRead.await()
            val status = statusRead.await()

            var refsChanged = false
            var headChanged = false
            if (refs != null && head != null) {
                val change = publishRefs(activation, refs, head)
                refsChanged = change.first
                headChanged = change.second
            } else if (refs != null) {
                refsChanged = publishRefs(activation, refs, _state.value.head ?: HeadInfo(null, null)).first
            }

            if (status != null) publishStatus(activation, status)
            if (repositoryDirs != null) publishOperation(activation, repositoryDirs)
            if (refs == null) publishLoadIfEmpty(activation, null)

            val snapshot = _state.value.graph
            val desired = currentSpec()
            val stale = snapshot == null ||
                graphSpec != desired ||
                graphRefs != refs ||
                graphHead != head ||
                refsChanged ||
                headChanged
            if (stale) requestGraphLoad(LoadMode.CLEAR)

            val selection = _state.value.selection
            if (selection is Selection.Commit && _state.value.commitDetail == null) {
                loadCommitDetails(selection.hash)
            }

            val diffTarget = _state.value.openDiffTarget
            if (diffTarget != null && _state.value.openDiff == null) {
                openDiff(diffTarget, openDiffUnlimited)
            }
        }
    }

    private suspend fun <T> readOrReport(
        activation: Int,
        label: String,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        reportReadError(activation, label, e)
        null
    }

    private fun publishRefs(activation: Int, refs: List<GitRef>, head: HeadInfo): Pair<Boolean, Boolean> {
        var refsChanged = false
        var headChanged = false
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return false to false
            val previous = _state.value
            refsChanged = previous.refs != refs
            headChanged = previous.head != head
            _state.update {
                it.copy(
                    refs = refs,
                    head = head,
                    labelsByCommit = visibleLabels(refs, it.hiddenRefs, it.showRemotes, it.showTags),
                )
            }
        }
        return refsChanged to headChanged
    }

    private fun publishStatus(activation: Int, status: WorkingStatus) {
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            _state.update { it.copy(status = status) }
        }
    }

    private fun publishOperation(activation: Int, repositoryDirs: GitDirs) {
        val operation = try {
            detectOperationInProgress(repositoryDirs)
        } catch (e: Exception) {
            reportReadError(activation, "Repository operation", e)
            return
        }
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            _state.update { it.copy(operationInProgress = operation) }
        }
    }

    private suspend fun refreshRefsAndHead(activation: Int): Boolean {
        val backend = try {
            git()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportReadError(activation, "Refs", e)
            return false
        }
        val (refs, head) = coroutineScope {
            val refsRead = async { readOrReport(activation, "Refs") { backend.refs() } }
            val headRead = async { readOrReport(activation, "HEAD") { backend.head() } }
            refsRead.await() to headRead.await()
        }
        if (refs == null || head == null) return false
        return publishRefs(activation, refs, head).let { it.first || it.second }
    }

    private suspend fun refreshStatus(activation: Int?) {
        val backend = try {
            git()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportReadError(activation, "Status", e)
            return
        }
        val status = try {
            backend.status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportReadError(activation, "Status", e)
            return
        }
        if (activation == null) {
            _state.update { it.copy(status = status) }
        } else {
            publishStatus(activation, status)
        }
        val repositoryDirs = dirs
        if (repositoryDirs != null) {
            if (activation == null) {
                val operation = try {
                    detectOperationInProgress(repositoryDirs)
                } catch (e: Exception) {
                    reportReadError(null, "Repository operation", e)
                    return
                }
                _state.update { it.copy(operationInProgress = operation) }
            } else {
                publishOperation(activation, repositoryDirs)
            }
        }
    }

    private fun loadCommitDetails(hash: String) {
        val context = activeReadContext() ?: return
        synchronized(lock) {
            if (!isCurrentLocked(context.activation)) return
            selectionJob?.cancel()
            selectionJob = context.scope.launch {
                delay(SELECTION_DEBOUNCE_MILLIS)
                if (!isCurrent(context.activation)) return@launch
                val backend = try {
                    git()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    reportReadError(context.activation, "Commit detail", e)
                    return@launch
                }
                val (detail, files) = coroutineScope {
                    val detailRead = async { readOrReport(context.activation, "Commit detail") { backend.commitDetail(hash) } }
                    val filesRead = async { readOrReport(context.activation, "Changed files") { backend.changedFiles(hash) } }
                    detailRead.await() to filesRead.await()
                }
                if (detail == null) return@launch
                val currentSelection = _state.value.selection
                if (currentSelection !is Selection.Commit || currentSelection.hash != hash) return@launch
                publishRead(context.activation) {
                    it.copy(commitDetail = detail, changedFiles = files.orEmpty())
                }
            }
        }
    }

    private fun startWatcher(activation: Int, repositoryDirs: GitDirs) {
        if (!isCurrent(activation)) return
        val failure = try {
            watcher.start(repositoryDirs, watchWorkTree = true) { event -> onWatchEvent(activation, event) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try {
                watcher.stop()
            } catch (stopFailure: Throwable) {
                e.addSuppressed(stopFailure)
            }
            e
        }

        synchronized(lock) {
            if (isCurrentLocked(activation) && failure == null) {
                watcherRunning = true
            }
        }

        if (failure == null) {
            val startFailure = (watcher as? JdkRepoWatcher)?.lastStartFailure
            if (startFailure != null) {
                synchronized(lock) { watcherRunning = false }
                watcher.stop()
                reportWatcherFailure(activation, startFailure)
                startPolling(activation)
                return
            }
        }

        if (failure != null) {
            reportWatcherFailure(activation, failure)
            startPolling(activation)
            return
        }

        if (!isCurrent(activation)) {
            watcher.stop()
        }
    }

    private fun onWatchEvent(activation: Int, event: WatchEvent) {
        val scope = synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            readScope ?: return
        }
        scope.launch {
            when (event) {
                WatchEvent.REFS_CHANGED -> {
                    val changed = refreshRefsAndHead(activation)
                    if (changed) requestGraphLoad(LoadMode.REFRESH)
                }
                WatchEvent.STATUS_CHANGED -> Unit
            }
            refreshStatus(activation)
        }
    }

    private fun startPolling(activation: Int) {
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            pollingJob?.cancel()
            val readScope = readScope ?: return
            pollingJob = readScope.launch {
                while (isActive) {
                    delay(WATCHER_POLL_MILLIS)
                    if (!isCurrent(activation)) return@launch
                    val changed = refreshRefsAndHead(activation)
                    if (changed) requestGraphLoad(LoadMode.REFRESH)
                    refreshStatus(activation)
                }
            }
        }
    }

    private fun reportWatcherFailure(activation: Int, error: Throwable) {
        publishReadMessage(
            activation,
            UserMessage.Error(
                "Repository watcher unavailable; polling every 3 s. ${describe(error)}",
                stderr(error),
            ),
        )
    }

    private fun stopWatcher() {
        val shouldStop = synchronized(lock) {
            val running = watcherRunning
            watcherRunning = false
            running
        }
        if (shouldStop) watcher.stop()
    }

    private fun requestGraphLoad(mode: LoadMode) {
        val scope: CoroutineScope
        val activation: Int
        synchronized(lock) {
            if (!activeRequested || closed || closeRequested) return
            scope = readScope ?: return
            activation = generation
        }
        synchronized(loadLock) {
            loadSequence++
            val sequence = loadSequence
            graphLoadJob?.cancel()
            graphLoadJob = scope.launch {
                graphMutex.withLock {
                    if (!isCurrentLoad(activation, sequence)) return@withLock
                    performGraphLoad(activation, sequence, mode)
                }
            }
        }
    }

    private suspend fun performGraphLoad(activation: Int, sequence: Long, mode: LoadMode) {
        val backend = try {
            git()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishLoadError(activation, sequence, e)
            return
        }

        val spec = currentSpec()
        val previousState = _state.value
        val refsAtStart = previousState.refs.toList()
        val headAtStart = previousState.head
        val anchorAtStart = _tab.value.scrollAnchorHash
        val anchorIndexAtStart = if (anchorAtStart != null) {
            previousState.graph?.indexOfHash(anchorAtStart)?.takeIf { it >= 0 } ?: scrollAnchorIndex
        } else {
            scrollAnchorIndex
        }
        if (mode == LoadMode.CLEAR) {
            synchronized(lock) {
                if (!isCurrentLoadLocked(activation, sequence)) return
                graphSpec = null
                graphRefs = null
                graphHead = null
                _state.update {
                    it.copy(
                        graph = null,
                        load = LoadState.Loading(0),
                    )
                }
            }
        } else {
            synchronized(lock) {
                if (!isCurrentLoadLocked(activation, sequence)) return
                _state.update { it.copy(load = LoadState.Loading(it.graph?.rowCount ?: 0)) }
            }
        }

        val engine = LaneLayoutEngine(flat = spec.flat)
        if (spec.revs.isEmpty() && !spec.includeHead) {
            finishGraphLoad(
                activation,
                sequence,
                engine.snapshot(complete = true),
                spec,
                refsAtStart,
                headAtStart,
                anchorIndexAtStart,
            )
            return
        }

        var rows = 0
        var publishedRows = 0
        var publishedAt = clockMillis()
        try {
            backend.logStream(spec).collect { commit ->
                currentCoroutineContext().ensureActive()
                engine.add(commit)
                rows++
                val now = clockMillis()
                if (mode == LoadMode.CLEAR && (rows - publishedRows >= PUBLISH_ROWS || now - publishedAt >= PUBLISH_MILLIS)) {
                    val snapshot = engine.snapshot(complete = false)
                    publishPartial(activation, sequence, snapshot, rows)
                    publishedRows = rows
                    publishedAt = now
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishLoadError(activation, sequence, e)
            return
        }
        finishGraphLoad(
            activation,
            sequence,
            engine.snapshot(complete = true),
            spec,
            refsAtStart,
            headAtStart,
            anchorIndexAtStart,
        )
    }

    private fun publishPartial(activation: Int, sequence: Long, snapshot: GraphSnapshot, rows: Int) {
        synchronized(lock) {
            if (!isCurrentLoadLocked(activation, sequence)) return
            _state.update { it.copy(graph = snapshot, load = LoadState.Loading(rows)) }
        }
    }

    private fun finishGraphLoad(
        activation: Int,
        sequence: Long,
        snapshot: GraphSnapshot,
        spec: LogSpec,
        refs: List<GitRef>,
        head: HeadInfo?,
        fallbackAnchorIndex: Int,
    ) {
        var selectedHash: String? = null
        var selectionChanged = false
        var anchorHash: String? = null
        var anchorChanged = false
        synchronized(lock) {
            if (!isCurrentLoadLocked(activation, sequence)) return
            graphSpec = spec
            graphRefs = refs
            graphHead = head
            val previous = _state.value
            val selection = previous.selection
            val adjusted = when {
                selection is Selection.Commit && snapshot.indexOfHash(selection.hash) < 0 -> {
                    Selection.WorkingTree
                }
                selection is Selection.None -> Selection.WorkingTree
                else -> selection
            }
            val selectionReplaced = adjusted != selection
            val detail = if (selectionReplaced) null else previous.commitDetail
            val files = if (selectionReplaced) emptyList() else previous.changedFiles
            val diff = if (selectionReplaced) null else previous.openDiff
            val diffTarget = if (selectionReplaced) null else previous.openDiffTarget
            _state.update {
                it.copy(
                    graph = snapshot,
                    load = LoadState.Idle,
                    selection = adjusted,
                    commitDetail = detail,
                    changedFiles = files,
                    openDiff = diff,
                    openDiffTarget = diffTarget,
                )
            }
            if (adjusted != selection) {
                selectionChanged = true
                selectedHash = (adjusted as? Selection.Commit)?.hash
            }
            val currentTab = _tab.value
            val previousAnchor = currentTab.scrollAnchorHash
            val nextAnchor = when {
                previousAnchor != null && snapshot.indexOfHash(previousAnchor) >= 0 -> previousAnchor
                previousAnchor != null && snapshot.rowCount > 0 ->
                    snapshot.hash(fallbackAnchorIndex.coerceIn(0, snapshot.rowCount - 1))
                else -> null
            }
            if (nextAnchor != previousAnchor) {
                anchorChanged = true
                anchorHash = nextAnchor
            }
        }
        if (selectionChanged || anchorChanged) {
            updateTab {
                it.copy(
                    selectedHash = if (selectionChanged) selectedHash else it.selectedHash,
                    scrollAnchorHash = if (anchorChanged) anchorHash else it.scrollAnchorHash,
                )
            }
        }
    }

    private fun publishLoadError(activation: Int, sequence: Long, error: Throwable) {
        synchronized(lock) {
            if (!isCurrentLoadLocked(activation, sequence)) return
            val message = UserMessage.Error(
                "Could not load the graph. ${describe(error)}",
                stderr(error),
            )
            messageLog.record(clockMillis(), path, message)
            _state.update {
                it.copy(
                    load = LoadState.Error(describe(error)),
                    message = message,
                )
            }
        }
    }

    private fun publishLoadIfEmpty(activation: Int, error: Throwable?) {
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            if (_state.value.graph == null) {
                _state.update {
                    it.copy(load = LoadState.Error(error?.let(::describe) ?: "Could not load the repository."))
                }
            }
        }
    }

    private fun currentSpec(): LogSpec {
        val snapshot = _state.value
        return resolve(
            lastValidQuery,
            snapshot.refs,
            snapshot.hiddenRefs,
            snapshot.showRemotes,
            snapshot.showTags,
            snapshot.showHead,
        )
    }

    private fun launchWrite(op: BusyOp, action: suspend (GitBackend) -> GitResult): Job {
        val job: Job
        synchronized(lock) {
            if (closeRequested || closed) return rejected("The tab is closing.")
            val started = writeScope.launch(start = CoroutineStart.LAZY) { performWrite(op, action) }
            activeWriteJobs += started
            started.invokeOnCompletion {
                synchronized(lock) {
                    activeWriteJobs.remove(started)
                    if (activeWriteJobs.isEmpty() && !closed) {
                        _lifecycle.value = if (activeRequested) SessionPhase.ACTIVE else SessionPhase.INACTIVE
                    }
                }
            }
            job = started
            _lifecycle.value = SessionPhase.DRAINING
        }
        job.start()
        return job
    }

    private suspend fun performWrite(op: BusyOp, action: suspend (GitBackend) -> GitResult) {
        writeMutex.withLock {
            _state.update { it.copy(busy = op, message = null) }
            try {
                try {
                    val backend = git()
                    val result = action(backend)
                    publishMessage(writeMessage(op, result))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    publishMessage(UserMessage.Error(describe(e), stderr(e)))
                }
                try {
                    refreshAfterWrite()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    publishMessage(
                        UserMessage.Error("Could not refresh after the write. ${describe(e)}", stderr(e)),
                    )
                }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private suspend fun refreshAfterWrite() {
        val backend = git()
        val previous = _state.value
        try {
            val status = backend.status()
            _state.update { it.copy(status = status) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishMessage(UserMessage.Error("Could not refresh status. ${describe(e)}", stderr(e)))
        }

        val refs = try {
            backend.refs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishMessage(UserMessage.Error("Could not refresh refs. ${describe(e)}", stderr(e)))
            null
        }
        val head = try {
            backend.head()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishMessage(UserMessage.Error("Could not refresh HEAD. ${describe(e)}", stderr(e)))
            null
        }

        var changed = false
        if (refs != null && head != null) {
            changed = previous.refs != refs || previous.head != head
            _state.update {
                it.copy(
                    refs = refs,
                    head = head,
                    labelsByCommit = visibleLabels(refs, it.hiddenRefs, it.showRemotes, it.showTags),
                )
            }
        }

        val repositoryDirs = dirs
        if (repositoryDirs != null) {
            val operation = try {
                detectOperationInProgress(repositoryDirs)
            } catch (e: Exception) {
                publishMessage(UserMessage.Error("Could not detect the repository operation. ${describe(e)}"))
                null
            }
            _state.update { it.copy(operationInProgress = operation) }
        }

        if (changed && activeRequested) requestGraphLoad(LoadMode.REFRESH)
    }

    private fun withoutConflicts(paths: List<String>): List<String>? {
        val status = _state.value.status ?: return paths
        val conflicts = status.entries
            .filter { it.kind == StatusKind.UNMERGED }
            .flatMap { listOfNotNull(it.path, it.origPath) }
            .toSet()
        val filtered = paths.filterNot { it in conflicts }
        return filtered.takeIf { it.isNotEmpty() }
    }

    private fun writeMessage(op: BusyOp, result: GitResult): UserMessage = when (result) {
        is GitResult.Ok -> {
            val output = result.stdout.trim()
            val text = when (op) {
                BusyOp.STAGE -> "Index updated."
                BusyOp.DISCARD -> "Discarded changes."
                BusyOp.COMMIT -> "Commit created."
                BusyOp.PUSH -> output.ifBlank { "Push complete." }
                BusyOp.PULL -> output.ifBlank { "Pull complete." }
            }
            UserMessage.Info(text)
        }
        is GitResult.Failure -> UserMessage.Error(
            "Git command failed (exit ${result.exitCode}).",
            result.stderr.ifBlank { null },
        )
    }

    private fun rejected(text: String): Job {
        publishMessage(UserMessage.Error(text))
        return Job().apply { complete() }
    }

    private fun updateTab(transform: (TabState) -> TabState) {
        var changed: TabState? = null
        synchronized(lock) {
            val next = transform(_tab.value)
            if (next != _tab.value) {
                _tab.value = next
                changed = next
            }
        }
        changed?.let(onTabChanged)
    }

    private fun activeReadContext(): ReadContext? = synchronized(lock) {
        if (!activeRequested || closed || closeRequested) return null
        val scope = readScope ?: return null
        ReadContext(scope, generation)
    }

    private fun publishRead(activation: Int, transform: (RepoUiState) -> RepoUiState) {
        synchronized(lock) {
            if (!isCurrentLocked(activation)) return
            _state.update(transform)
        }
    }

    private fun reportReadError(activation: Int?, label: String, error: Throwable) {
        val message = UserMessage.Error("$label failed. ${describe(error)}", stderr(error))
        if (activation == null) {
            publishMessage(message)
            return
        }
        publishReadMessage(activation, message)
    }

    private fun reportDiffError(activation: Int, sequence: Long, error: Throwable) {
        if (!isCurrentDiff(activation, sequence)) return
        reportReadError(activation, "Diff", error)
    }

    private fun isCurrentDiff(activation: Int, sequence: Long): Boolean = synchronized(lock) {
        isCurrentLocked(activation) && diffSequence == sequence
    }

    private fun isCurrent(activation: Int): Boolean = synchronized(lock) { isCurrentLocked(activation) }

    private fun isCurrentLocked(activation: Int): Boolean =
        activeRequested && !closed && generation == activation

    private fun isCurrentLoad(activation: Int, sequence: Long): Boolean =
        synchronized(lock) { isCurrentLoadLocked(activation, sequence) }

    private fun isCurrentLoadLocked(activation: Int, sequence: Long): Boolean =
        isCurrentLocked(activation) && loadSequence == sequence

    private suspend fun git(): GitBackend {
        backend?.let { return it }
        return backendMutex.withLock {
            backend ?: backendFactory(path).also { backend = it }
        }
    }

    private suspend fun currentDirs(backend: GitBackend): GitDirs {
        dirs?.let { return it }
        val discovered = backend.gitDirs()
        dirs = discovered
        return discovered
    }

    private data class ReadContext(val scope: CoroutineScope, val activation: Int)

    private enum class LoadMode { CLEAR, REFRESH }

    companion object {
        private const val PUBLISH_ROWS = 2_000
        private const val PUBLISH_MILLIS = 100L
        private const val WATCHER_POLL_MILLIS = 3_000L
        private const val SELECTION_DEBOUNCE_MILLIS = 100L
    }
}

internal fun detectOperationInProgress(dirs: GitDirs): String? {
    fun exists(name: String): Boolean = Files.exists(dirs.gitDir.resolve(name))

    return when {
        exists("rebase-merge") || exists("rebase-apply") || exists("REBASE_HEAD") -> "rebase"
        exists("CHERRY_PICK_HEAD") -> "cherry-pick"
        exists("REVERT_HEAD") -> "revert"
        exists("MERGE_HEAD") -> "merge"
        else -> null
    }
}

private fun visibleLabels(
    refs: List<GitRef>,
    hidden: Set<String>,
    showRemotes: Boolean,
    showTags: Boolean,
): Map<String, List<GitRef>> = refs
    .filter { ref ->
        ref.fullName !in hidden &&
            when (ref.kind) {
                RefKind.LOCAL_BRANCH -> true
                RefKind.REMOTE_BRANCH -> showRemotes
                RefKind.TAG -> showTags
            }
    }
    .groupBy { it.commit }

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"

private fun stderr(error: Throwable): String? = (error as? GitException)?.stderr?.ifBlank { null }
