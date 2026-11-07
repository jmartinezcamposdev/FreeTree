package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import java.nio.file.Path
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

enum class FakeMethod(val write: Boolean = false) {
    REFS,
    HEAD,
    LOG,
    COMMIT_DETAIL,
    CHANGED_FILES,
    DIFF,
    STATUS,
    STAGE(write = true),
    UNSTAGE(write = true),
    DISCARD_STAGED(write = true),
    DISCARD_UNSTAGED(write = true),
    COMMIT(write = true),
    PUSH(write = true),
    PULL(write = true),
    CHECKOUT_LOCAL(write = true),
    CHECKOUT_DETACHED(write = true),
    CHECKOUT_REMOTE(write = true),
    CREATE_BRANCH(write = true),
    CREATE_TAG(write = true),
    DELETE_LOCAL_BRANCH(write = true),
    DELETE_REMOTE_BRANCH(write = true),
    REACHABLE_FROM_HEAD,
    BRANCHES_CONTAINING,
    GIT_DIRS,
}

data class BackendCall(
    val sequence: Long,
    val method: FakeMethod,
    val spec: LogSpec? = null,
    val hash: String? = null,
    val paths: List<String> = emptyList(),
    val target: DiffTarget? = null,
    val unlimited: Boolean = false,
    val message: String? = null,
    val branch: String? = null,
    val remote: String? = null,
    val name: String? = null,
    val force: Boolean = false,
)

class SuspensionGate internal constructor() {
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    val isReleased: Boolean
        get() = released.isCompleted

    suspend fun awaitEntered() {
        entered.await()
    }

    internal suspend fun pass() {
        entered.complete(Unit)
        released.await()
    }

    fun release() {
        released.complete(Unit)
    }
}

/**
 * In-memory [GitBackend] for session tests. All state is replaceable snapshots; the call log and
 * concurrency counters are guarded by a private lock. Write methods return configured results and,
 * unless [statefulWrites] is enabled, do not mutate snapshots. [logStream] emits [commits] in order
 * and records the received [LogSpec].
 *
 * With [statefulWrites] the fake applies stage/unstage/discard/commit to [status] (and commit to
 * [commits], [refs] and [head]) so UI and acceptance tests can observe the sections change.
 */
class FakeGitBackend(
    commits: List<RawCommit> = emptyList(),
    refs: List<GitRef> = emptyList(),
    head: HeadInfo = HeadInfo(null, null),
    status: WorkingStatus = WorkingStatus(null, null, 0, 0, true, emptyList()),
    dirs: GitDirs = defaultGitDirs(),
    commitDetails: Map<String, CommitDetail> = emptyMap(),
    changedFiles: Map<String, List<FileChange>> = emptyMap(),
    diffs: Map<DiffTarget, DiffText> = emptyMap(),
    branchesContaining: Map<String, List<String>> = emptyMap(),
    reachableFromHead: Set<String> = emptySet(),
    private val statefulWrites: Boolean = false,
) : GitBackend {

    private val lock = Any()
    private val callLog = mutableListOf<BackendCall>()
    private val failures = mutableMapOf<FakeMethod, ArrayDeque<Throwable>>()
    private val alwaysFailing = mutableMapOf<FakeMethod, Throwable>()
    private val gates = mutableMapOf<FakeMethod, SuspensionGate>()
    private val nextGates = mutableMapOf<FakeMethod, SuspensionGate>()
    private val results = mutableMapOf<FakeMethod, GitResult>()
    private var nextSequence = 0L
    private var activeCalls = 0
    private var peakCalls = 0
    private var activeWrites = 0
    private var peakWrites = 0
    private var committedWrites = 0

    @Volatile
    var commits: List<RawCommit> = commits.toList()

    @Volatile
    var refs: List<GitRef> = refs.toList()

    @Volatile
    var head: HeadInfo = head

    @Volatile
    var status: WorkingStatus = status

    @Volatile
    var dirs: GitDirs = dirs

    @Volatile
    var commitDetails: Map<String, CommitDetail> = commitDetails.toMap()

    @Volatile
    var changedFiles: Map<String, List<FileChange>> = changedFiles.mapValues { it.value.toList() }

    @Volatile
    var diffs: Map<DiffTarget, DiffText> = diffs.toMap()

    @Volatile
    var branchesContaining: Map<String, List<String>> =
        branchesContaining.mapValues { it.value.toList() }

    @Volatile
    var reachableFromHead: Set<String> = reachableFromHead.toSet()

    @Volatile
    var logDelayMillis: Long = 0L

    @Volatile
    var logFailAfter: Int? = null

    @Volatile
    var logFailure: Throwable = GitException(128, "fake log failure")

    val calls: List<BackendCall>
        get() = synchronized(lock) { callLog.toList() }

    val peakConcurrentCalls: Int
        get() = synchronized(lock) { peakCalls }

    val peakConcurrentWrites: Int
        get() = synchronized(lock) { peakWrites }

    fun callsOf(method: FakeMethod): List<BackendCall> = calls.filter { it.method == method }

    fun callCount(method: FakeMethod): Int = synchronized(lock) { callLog.count { it.method == method } }

    fun clearCalls() {
        synchronized(lock) { callLog.clear() }
    }

    fun failNext(method: FakeMethod, error: Throwable = GitException(128, "fake failure")) {
        synchronized(lock) {
            failures.getOrPut(method) { ArrayDeque() }.addLast(error)
        }
    }

    fun failAlways(method: FakeMethod, error: Throwable = GitException(128, "fake failure")) {
        synchronized(lock) { alwaysFailing[method] = error }
    }

    fun clearFailures() {
        synchronized(lock) {
            failures.clear()
            alwaysFailing.clear()
        }
    }

    fun pause(method: FakeMethod): SuspensionGate = synchronized(lock) {
        gates.put(method, SuspensionGate())?.release()
        gates.getValue(method)
    }

    fun pauseNext(method: FakeMethod): SuspensionGate = synchronized(lock) {
        nextGates.put(method, SuspensionGate())?.release()
        nextGates.getValue(method)
    }

    fun resume(method: FakeMethod) {
        val pending = synchronized(lock) {
            listOfNotNull(gates.remove(method), nextGates.remove(method))
        }
        pending.forEach { it.release() }
    }

    fun clearSuspensions() {
        val pending = synchronized(lock) {
            val all = gates.values + nextGates.values
            gates.clear()
            nextGates.clear()
            all
        }
        pending.forEach { it.release() }
    }

    fun setResult(method: FakeMethod, result: GitResult) {
        synchronized(lock) { results[method] = result }
    }

    fun clearResults() {
        synchronized(lock) { results.clear() }
    }

    override suspend fun refs(): List<GitRef> = call(FakeMethod.REFS) { refs }

    override suspend fun head(): HeadInfo = call(FakeMethod.HEAD) { head }

    override fun logStream(spec: LogSpec): Flow<RawCommit> = flow {
        begin(FakeMethod.LOG, spec = spec)
        try {
            passGate(FakeMethod.LOG)
            throwIfFailing(FakeMethod.LOG)
            val snapshot = commits
            val delayMillis = logDelayMillis
            var emitted = 0
            for (commit in snapshot) {
                currentCoroutineContext().ensureActive()
                if (delayMillis > 0) delay(delayMillis) else yield()
                val failAfter = logFailAfter
                if (failAfter != null && emitted >= failAfter) throw logFailure
                emit(commit)
                emitted++
            }
        } finally {
            end(FakeMethod.LOG)
        }
    }

    override suspend fun commitDetail(hash: String): CommitDetail =
        call(FakeMethod.COMMIT_DETAIL, hash = hash) {
            commitDetails[hash] ?: commits.firstOrNull { it.hash == hash }?.toDetail()
                ?: throw GitException(128, "unknown commit: $hash")
        }

    override suspend fun changedFiles(hash: String): List<FileChange> =
        call(FakeMethod.CHANGED_FILES, hash = hash) { changedFiles[hash].orEmpty() }

    override suspend fun diff(target: DiffTarget, unlimited: Boolean): DiffText =
        call(FakeMethod.DIFF, target = target, unlimited = unlimited) {
            diffs[target] ?: DiffText(emptyList(), binary = false, truncated = false)
        }

    override suspend fun status(): WorkingStatus = call(FakeMethod.STATUS) { status }

    override suspend fun stage(paths: List<String>): GitResult =
        call(FakeMethod.STAGE, paths = paths.toList()) {
            resultFor(FakeMethod.STAGE).also { result ->
                if (statefulWrites && result is GitResult.Ok) applyStage(paths)
            }
        }

    override suspend fun unstage(paths: List<String>): GitResult =
        call(FakeMethod.UNSTAGE, paths = paths.toList()) {
            resultFor(FakeMethod.UNSTAGE).also { result ->
                if (statefulWrites && result is GitResult.Ok) applyUnstage(paths)
            }
        }

    override suspend fun discardStaged(paths: List<String>): GitResult =
        call(FakeMethod.DISCARD_STAGED, paths = paths.toList()) {
            resultFor(FakeMethod.DISCARD_STAGED).also { result ->
                if (statefulWrites && result is GitResult.Ok) applyDiscardStaged(paths)
            }
        }

    override suspend fun discardUnstaged(paths: List<String>): GitResult =
        call(FakeMethod.DISCARD_UNSTAGED, paths = paths.toList()) {
            resultFor(FakeMethod.DISCARD_UNSTAGED).also { result ->
                if (statefulWrites && result is GitResult.Ok) applyDiscardUnstaged(paths)
            }
        }

    override suspend fun commit(message: String): GitResult =
        call(FakeMethod.COMMIT, message = message) {
            resultFor(FakeMethod.COMMIT).also { result ->
                if (statefulWrites && result is GitResult.Ok) applyCommit(message)
            }
        }

    override suspend fun push(): GitResult = call(FakeMethod.PUSH) { resultFor(FakeMethod.PUSH) }

    override suspend fun pull(): GitResult = call(FakeMethod.PULL) { resultFor(FakeMethod.PULL) }

    override suspend fun checkoutLocal(branch: String): GitResult =
        call(FakeMethod.CHECKOUT_LOCAL, branch = branch) { resultFor(FakeMethod.CHECKOUT_LOCAL) }

    override suspend fun checkoutDetached(hash: String): GitResult =
        call(FakeMethod.CHECKOUT_DETACHED, hash = hash) { resultFor(FakeMethod.CHECKOUT_DETACHED) }

    override suspend fun checkoutRemoteAsLocal(
        localBranch: String,
        remoteRef: String,
    ): GitResult = call(
        FakeMethod.CHECKOUT_REMOTE,
        branch = localBranch,
        name = remoteRef,
    ) { resultFor(FakeMethod.CHECKOUT_REMOTE) }

    override suspend fun createBranchAndCheckout(name: String, startHash: String): GitResult =
        call(FakeMethod.CREATE_BRANCH, hash = startHash, name = name) {
            resultFor(FakeMethod.CREATE_BRANCH)
        }

    override suspend fun createTag(name: String, hash: String): GitResult =
        call(FakeMethod.CREATE_TAG, hash = hash, name = name) {
            resultFor(FakeMethod.CREATE_TAG)
        }

    override suspend fun deleteLocalBranch(name: String, force: Boolean): GitResult =
        call(FakeMethod.DELETE_LOCAL_BRANCH, branch = name, force = force) {
            resultFor(FakeMethod.DELETE_LOCAL_BRANCH)
        }

    override suspend fun deleteRemoteBranch(remote: String, branch: String): GitResult =
        call(FakeMethod.DELETE_REMOTE_BRANCH, remote = remote, branch = branch) {
            resultFor(FakeMethod.DELETE_REMOTE_BRANCH)
        }

    override suspend fun isCommitReachableFromHead(hash: String): Boolean =
        call(FakeMethod.REACHABLE_FROM_HEAD, hash = hash) { hash in reachableFromHead }

    override suspend fun localBranchesContaining(hash: String): List<String> =
        call(FakeMethod.BRANCHES_CONTAINING, hash = hash) { branchesContaining[hash].orEmpty() }

    override suspend fun gitDirs(): GitDirs = call(FakeMethod.GIT_DIRS) { dirs }

    private suspend fun <T> call(
        method: FakeMethod,
        spec: LogSpec? = null,
        hash: String? = null,
        paths: List<String> = emptyList(),
        target: DiffTarget? = null,
        unlimited: Boolean = false,
        message: String? = null,
        branch: String? = null,
        remote: String? = null,
        name: String? = null,
        force: Boolean = false,
        block: suspend () -> T,
    ): T {
        begin(method, spec, hash, paths, target, unlimited, message, branch, remote, name, force)
        try {
            passGate(method)
            throwIfFailing(method)
            return block()
        } finally {
            end(method)
        }
    }

    private fun begin(
        method: FakeMethod,
        spec: LogSpec? = null,
        hash: String? = null,
        paths: List<String> = emptyList(),
        target: DiffTarget? = null,
        unlimited: Boolean = false,
        message: String? = null,
        branch: String? = null,
        remote: String? = null,
        name: String? = null,
        force: Boolean = false,
    ) {
        synchronized(lock) {
            callLog += BackendCall(
                sequence = nextSequence++,
                method = method,
                spec = spec,
                hash = hash,
                paths = paths,
                target = target,
                unlimited = unlimited,
                message = message,
                branch = branch,
                remote = remote,
                name = name,
                force = force,
            )
            activeCalls++
            peakCalls = maxOf(peakCalls, activeCalls)
            if (method.write) {
                activeWrites++
                peakWrites = maxOf(peakWrites, activeWrites)
            }
        }
    }

    private fun end(method: FakeMethod) {
        synchronized(lock) {
            activeCalls--
            if (method.write) activeWrites--
        }
    }

    private fun gateFor(method: FakeMethod): SuspensionGate? = synchronized(lock) {
        nextGates.remove(method) ?: gates[method]
    }

    private suspend fun passGate(method: FakeMethod) {
        gateFor(method)?.pass()
    }

    private fun throwIfFailing(method: FakeMethod) {
        val failure = synchronized(lock) {
            failures[method]?.pollFirst() ?: alwaysFailing[method]
        }
        if (failure != null) throw failure
    }

    private fun resultFor(method: FakeMethod): GitResult =
        synchronized(lock) { results[method] ?: GitResult.Ok("") }

    private fun applyStage(paths: List<String>) = mutateStatus { entries ->
        paths.toSet().forEach { path ->
            val index = entries.indexOfFirst { it.path == path }
            if (index < 0) return@forEach
            val entry = entries[index]
            entries[index] = when {
                entry.kind == StatusKind.UNTRACKED || entry.index == '?' ->
                    entry.copy(index = 'A', worktree = ' ', kind = StatusKind.ORDINARY)
                entry.index == 'A' && entry.worktree != ' ' ->
                    entry.copy(worktree = ' ', kind = StatusKind.ORDINARY)
                entry.worktree != ' ' ->
                    entry.copy(index = entry.worktree, worktree = ' ', kind = StatusKind.ORDINARY)
                else -> entry
            }
        }
    }

    private fun applyUnstage(paths: List<String>) = mutateStatus { entries ->
        paths.toSet().forEach { path ->
            val index = entries.indexOfFirst { it.path == path }
            if (index < 0) return@forEach
            val entry = entries[index]
            if (entry.index == ' ' || entry.index == '?') return@forEach
            entries[index] = if (entry.index == 'A' && entry.worktree == ' ') {
                entry.copy(index = '?', worktree = '?', kind = StatusKind.UNTRACKED)
            } else {
                entry.copy(
                    index = ' ',
                    worktree = entry.worktree.takeIf { it != ' ' } ?: entry.index,
                    kind = StatusKind.ORDINARY,
                )
            }
        }
    }

    private fun applyDiscardStaged(paths: List<String>) = mutateStatus { entries ->
        paths.toSet().forEach { path ->
            val index = entries.indexOfFirst { it.path == path }
            if (index < 0) return@forEach
            val entry = entries[index]
            when {
                entry.index == ' ' || entry.index == '?' -> Unit
                entry.index == 'A' ->
                    entries[index] = entry.copy(index = '?', worktree = '?', kind = StatusKind.UNTRACKED)
                entry.index == 'D' ->
                    entries[index] = entry.copy(index = ' ', worktree = 'D', kind = StatusKind.ORDINARY)
                entry.worktree == ' ' ->
                    entries.removeAt(index)
                else ->
                    entries[index] = entry.copy(index = ' ', kind = StatusKind.ORDINARY)
            }
        }
    }

    private fun applyDiscardUnstaged(paths: List<String>) = mutateStatus { entries ->
        paths.toSet().forEach { path ->
            val index = entries.indexOfFirst { it.path == path }
            if (index < 0) return@forEach
            val entry = entries[index]
            when {
                entry.kind == StatusKind.UNTRACKED || entry.index == '?' -> entries.removeAt(index)
                entry.index == ' ' -> entries.removeAt(index)
                else -> entries[index] = entry.copy(worktree = ' ', kind = StatusKind.ORDINARY)
            }
        }
    }

    private fun applyCommit(message: String) = synchronized(lock) {
        val staged = status.entries.filter { it.index != ' ' && it.index != '?' && it.index != 'U' }
        if (staged.isEmpty()) return@synchronized
        val parent = head.commit
        val nextHash = (++committedWrites).toString(16).padStart(40, '0')
        val now = (commits.firstOrNull()?.authorTimeSec ?: 0L) + 60L
        val commit = RawCommit(
            hash = nextHash,
            parents = listOfNotNull(parent),
            authorName = "Fake Analyst",
            authorTimeSec = now,
            subject = message.lineSequence().firstOrNull().orEmpty(),
            authorEmail = "fake.analyst@example.com",
        )
        val changed = staged.map { entry ->
            FileChange(
                status = when (entry.index) {
                    'A', '?' -> 'A'
                    'D' -> 'D'
                    'R' -> 'R'
                    else -> 'M'
                },
                path = entry.path,
                oldPath = entry.origPath,
            )
        }
        commits = listOf(commit) + commits
        commitDetails = commitDetails + (nextHash to commit.toDetail().copy(message = message))
        changedFiles = changedFiles + (nextHash to changed)
        head = HeadInfo(nextHash, head.branchShortName)
        val branchName = head.branchShortName
        if (branchName != null) {
            refs = refs.map { ref ->
                if (ref.fullName == "refs/heads/$branchName") ref.copy(commit = nextHash) else ref
            }
        }
        val remaining = status.entries.mapNotNull { entry ->
            if (entry.index == ' ' || entry.index == '?' || entry.index == 'U') {
                entry
            } else if (entry.worktree != ' ') {
                entry.copy(index = ' ', kind = StatusKind.ORDINARY)
            } else {
                null
            }
        }
        status = status.copy(
            branchHead = nextHash,
            ahead = status.ahead + 1,
            unborn = false,
            entries = remaining,
        )
    }

    private fun mutateStatus(block: (MutableList<StatusEntry>) -> Unit) {
        synchronized(lock) {
            val entries = status.entries.toMutableList()
            block(entries)
            status = status.copy(entries = entries.toList())
        }
    }

    private fun RawCommit.toDetail() = CommitDetail(
        hash = hash,
        parents = parents,
        authorName = authorName,
        authorEmail = "author@example.com",
        authorTimeSec = authorTimeSec,
        committerName = authorName,
        committerTimeSec = authorTimeSec,
        message = subject,
    )

    companion object {
        fun defaultGitDirs(): GitDirs {
            val root = Path.of(System.getProperty("java.io.tmpdir"), "freetree-fake-repo")
            return GitDirs(workTree = root, gitDir = root.resolve(".git"), commonDir = root.resolve(".git"))
        }
    }
}
