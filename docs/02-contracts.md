# 02 — Contracts

Parent: [SPEC.md](SPEC.md). Create these in Phase 0; change them only through `temp/DECISIONS.md` (local-only; never tracked).

```kotlin
// core/model/Models.kt — package com.jmartinezcampos.freetree.core.model

data class RawCommit(
    val hash: String,
    val parents: List<String>,
    val authorName: String,
    val authorTimeSec: Long,
    val subject: String,
)

enum class RefKind { LOCAL_BRANCH, REMOTE_BRANCH, TAG }

data class GitRef(
    val fullName: String,
    val shortName: String,
    val kind: RefKind,
    val commit: String,
    val isCurrentBranch: Boolean,
    val upstream: String?,
)

data class HeadInfo(val commit: String?, val branchShortName: String?)

data class LogSpec(
    val revs: List<String>,
    val includeHead: Boolean,
    val author: String? = null,
    val grep: String? = null,
    val since: String? = null,
    val until: String? = null,
    val path: String? = null,
) {
    val flat: Boolean
        get() = author != null || grep != null || since != null || until != null || path != null
}

data class FileChange(val status: Char, val path: String, val oldPath: String?)

data class CommitDetail(
    val hash: String,
    val parents: List<String>,
    val authorName: String,
    val authorEmail: String,
    val authorTimeSec: Long,
    val committerName: String,
    val committerTimeSec: Long,
    val message: String,
)

sealed interface DiffTarget {
    data class InCommit(val hash: String, val change: FileChange) : DiffTarget
    data class Unstaged(val path: String) : DiffTarget
    data class Staged(val path: String) : DiffTarget
}

data class DiffText(val lines: List<DiffLine>, val binary: Boolean, val truncated: Boolean)

data class DiffLine(
    val kind: DiffLineKind,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
)

enum class DiffLineKind { FILE_HEADER, HUNK_HEADER, ADD, DEL, CONTEXT, NOTE }

enum class StatusKind { ORDINARY, RENAMED, UNMERGED, UNTRACKED }

data class StatusEntry(
    val path: String,
    val origPath: String?,
    val index: Char,
    val worktree: Char,
    val kind: StatusKind,
)

data class WorkingStatus(
    val branchHead: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val unborn: Boolean,
    val entries: List<StatusEntry>,
)

data class GitDirs(
    val workTree: java.nio.file.Path,
    val gitDir: java.nio.file.Path,
    val commonDir: java.nio.file.Path,
)

sealed interface GitResult {
    data class Ok(val stdout: String) : GitResult
    data class Failure(val exitCode: Int, val stderr: String) : GitResult
}

class GitException(val exitCode: Int, val stderr: String) : Exception(stderr)
```

```kotlin
// core/git/GitBackend.kt
interface GitBackend {
    suspend fun refs(): List<GitRef>
    suspend fun head(): HeadInfo
    fun logStream(spec: LogSpec): Flow<RawCommit>   // cold, cancellable, throws GitException

    suspend fun commitDetail(hash: String): CommitDetail
    suspend fun changedFiles(hash: String): List<FileChange>
    suspend fun diff(target: DiffTarget, unlimited: Boolean = false): DiffText
    suspend fun status(): WorkingStatus

    suspend fun stage(paths: List<String>): GitResult
    suspend fun unstage(paths: List<String>): GitResult
    suspend fun discardStaged(paths: List<String>): GitResult
    suspend fun discardUnstaged(paths: List<String>): GitResult
    suspend fun commit(message: String): GitResult
    suspend fun push(): GitResult
    suspend fun pull(): GitResult

    suspend fun gitDirs(): GitDirs
}
```

```kotlin
// core/watch/RepoWatcher.kt
interface RepoWatcher {
    fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit)
    fun stop()
}

enum class WatchEvent { REFS_CHANGED, STATUS_CHANGED }
```

```kotlin
// core/session/TabState.kt
@Serializable
data class TabState(
    val id: String,
    val path: String,
    val query: String = "",
    val hiddenRefs: Set<String> = emptySet(),
    val showRemotes: Boolean = true,
    val showTags: Boolean = true,
    val showHead: Boolean = true,
    val selectedHash: String? = null,
    val scrollAnchorHash: String? = null,
)
```

```kotlin
// core/session/RepoUiState.kt
data class RepoUiState(
    val graph: GraphSnapshot? = null,
    val load: LoadState = LoadState.Idle,
    val refs: List<GitRef> = emptyList(),
    val head: HeadInfo? = null,
    val labelsByCommit: Map<String, List<GitRef>> = emptyMap(),
    val status: WorkingStatus? = null,
    val operationInProgress: String? = null,
    val selection: Selection = Selection.None,
    val commitDetail: CommitDetail? = null,
    val changedFiles: List<FileChange> = emptyList(),
    val openDiff: DiffText? = null,
    val openDiffTarget: DiffTarget? = null,
    val query: String = "",
    val queryError: String? = null,
    val hiddenRefs: Set<String> = emptySet(),
    val showRemotes: Boolean = true,
    val showTags: Boolean = true,
    val showHead: Boolean = true,
    val busy: BusyOp? = null,
    val message: UserMessage? = null,
)

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val rowsLoaded: Int) : LoadState
    data class Error(val message: String) : LoadState
}

sealed interface Selection {
    data object None : Selection
    data object WorkingTree : Selection
    data class Commit(val hash: String) : Selection
}

enum class BusyOp { STAGE, DISCARD, COMMIT, PUSH, PULL }

sealed interface UserMessage {
    data class Info(val text: String) : UserMessage
    data class Error(val text: String, val stderr: String? = null) : UserMessage
}
```

```kotlin
// core/session/SessionManager.kt
class SessionManager(/* backend factory, watcher factory, persistence */) {
    val tabs: StateFlow<List<TabState>>
    val activeId: StateFlow<String?>
    val activeSession: StateFlow<RepoSession?>

    fun openRepo(path: java.nio.file.Path)
    fun closeTab(id: String)
    fun activate(id: String)
}
```

```kotlin
// core/graph — public surface
class LaneLayoutEngine(flat: Boolean = false, chunkSize: Int = 4096) {
    fun add(c: RawCommit)
    fun snapshot(complete: Boolean): GraphSnapshot
}

class GraphSnapshot {
    val rowCount: Int
    val maxLanes: Int
    val complete: Boolean
    val flat: Boolean
    fun hash(row: Int): String
    fun subject(row: Int): String
    fun author(row: Int): String
    fun timeSec(row: Int): Long
    fun parentCount(row: Int): Int
    fun nodeLane(row: Int): Int
    fun nodeColor(row: Int): Int
    fun segments(row: Int): SegmentsView
    fun indexOfHash(hash: String): Int
}

enum class SegKind { PASS, IN, OUT }

data class GraphSegment(
    val from: Int,
    val to: Int,
    val color: Int,
    val kind: SegKind,
    val merge: Boolean,
)

interface SegmentsView {
    val size: Int
    operator fun get(index: Int): GraphSegment
}
```

```kotlin
// core/query/Query.kt
data class Query(
    val refIncludes: List<String> = emptyList(),
    val refExcludes: List<String> = emptyList(),
    val author: String? = null,
    val grep: String? = null,
    val since: String? = null,
    val until: String? = null,
    val path: String? = null,
)

fun parseQuery(text: String): Result<Query>
fun resolve(
    q: Query,
    refs: List<GitRef>,
    hidden: Set<String>,
    showRemotes: Boolean,
    showTags: Boolean,
    showHead: Boolean,
): LogSpec
```
