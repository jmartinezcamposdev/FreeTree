package com.jmartinezcampos.freetree.app

import com.jmartinezcampos.freetree.core.git.GitBackend
import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal object DemoRepository {
    val path: Path = Path.of(System.getProperty("java.io.tmpdir"), "freetree-demo").toAbsolutePath()
}

internal class DemoGitBackend(private val path: Path = DemoRepository.path) : GitBackend {
    private val commits: List<RawCommit> = listOf(
        commit(0, "Merge feature/search into main", listOf(1, 2), "Ada", "Merge search ranking fix"),
        commit(1, "Add search ranking", listOf(3), "Grace", "Rank exact matches first"),
        commit(2, "Fix empty state", listOf(3), "Linus", "Show a helpful empty state"),
        commit(3, "Add query parser", listOf(4), "Ada", "Parse ref, author, msg, path terms"),
        commit(4, "Draw lane graph", listOf(5), "Barbara", "Draw passes, merges, and nodes"),
        commit(5, "Initial import", emptyList(), "Ken", "Initial FreeTree demo"),
    )
    private val refs: List<GitRef> = listOf(
        GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, "origin/main"),
        GitRef("refs/heads/feature/search", "feature/search", RefKind.LOCAL_BRANCH, commits[1].hash, false, null),
        GitRef("refs/remotes/origin/main", "origin/main", RefKind.REMOTE_BRANCH, commits[0].hash, false, null),
        GitRef("refs/tags/v1.0", "v1.0", RefKind.TAG, commits[4].hash, false, null),
    )

    @Volatile
    private var status: WorkingStatus = WorkingStatus(
        branchHead = commits[0].hash,
        upstream = "origin/main",
        ahead = 2,
        behind = 1,
        unborn = false,
        entries = listOf(
            StatusEntry("src/App.kt", null, 'M', ' ', StatusKind.ORDINARY),
            StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
            StatusEntry("notes.txt", null, '?', '?', StatusKind.UNTRACKED),
        ),
    )

    override suspend fun refs(): List<GitRef> = refs

    override suspend fun head(): HeadInfo = HeadInfo(commits[0].hash, "main")

    override fun logStream(spec: LogSpec): Flow<RawCommit> = flow {
        commits.forEach { emit(it) }
    }

    override suspend fun commitDetail(hash: String): CommitDetail {
        val raw = commits.first { it.hash == hash }
        return CommitDetail(
            hash = raw.hash,
            parents = raw.parents,
            authorName = raw.authorName,
            authorEmail = "${raw.authorName.lowercase()}@example.com",
            authorTimeSec = raw.authorTimeSec,
            committerName = raw.authorName,
            committerTimeSec = raw.authorTimeSec,
            message = raw.subject,
        )
    }

    override suspend fun changedFiles(hash: String): List<FileChange> = listOf(
        FileChange('M', "README.md", null),
        FileChange('A', "src/App.kt", null),
        FileChange('D', "docs/old.md", null),
        FileChange('R', "src/ui/Panel.kt", "src/Panel.kt"),
    )

    override suspend fun diff(target: DiffTarget, unlimited: Boolean): DiffText = DiffText(
        lines = listOf(
            DiffLine(DiffLineKind.FILE_HEADER, null, null, "diff --git a/README.md b/README.md"),
            DiffLine(DiffLineKind.HUNK_HEADER, null, null, "@@ -1,3 +1,3 @@"),
            DiffLine(DiffLineKind.CONTEXT, 1, 1, "FreeTree"),
            DiffLine(DiffLineKind.DEL, 2, null, "Old line"),
            DiffLine(DiffLineKind.ADD, null, 2, "New line"),
            DiffLine(DiffLineKind.CONTEXT, 3, 3, "Demo repository"),
        ),
        binary = false,
        truncated = false,
    )

    override suspend fun status(): WorkingStatus = status

    override suspend fun stage(paths: List<String>): GitResult {
        status = status.copy(entries = status.entries.map { entry ->
            if (entry.path in paths && entry.worktree != ' ') {
                entry.copy(index = if (entry.kind == StatusKind.UNTRACKED) 'A' else entry.worktree, worktree = ' ')
            } else {
                entry
            }
        })
        return GitResult.Ok("")
    }

    override suspend fun unstage(paths: List<String>): GitResult {
        status = status.copy(entries = status.entries.map { entry ->
            if (entry.path in paths) entry.copy(index = ' ') else entry
        })
        return GitResult.Ok("")
    }

    override suspend fun discardStaged(paths: List<String>): GitResult = unstage(paths)

    override suspend fun discardUnstaged(paths: List<String>): GitResult {
        status = status.copy(entries = status.entries.filterNot { entry ->
            entry.path in paths && entry.kind == StatusKind.UNTRACKED
        }.map { entry ->
            if (entry.path in paths) entry.copy(worktree = ' ') else entry
        })
        return GitResult.Ok("")
    }

    override suspend fun commit(message: String): GitResult {
        status = status.copy(entries = status.entries.filter { it.index == ' ' || it.index == '?' })
        return GitResult.Ok("")
    }

    override suspend fun push(): GitResult = GitResult.Ok("Demo push complete.")

    override suspend fun pull(): GitResult = GitResult.Ok("Demo pull complete.")

    override suspend fun gitDirs(): GitDirs = GitDirs(
        workTree = path,
        gitDir = path.resolve(".git"),
        commonDir = path.resolve(".git"),
    )

    private fun commit(
        index: Int,
        subject: String,
        parents: List<Int>,
        author: String,
        message: String,
    ): RawCommit = RawCommit(
        hash = "demo${index.toString().padStart(36, '0')}",
        parents = parents.map { parent -> "demo${parent.toString().padStart(36, '0')}" },
        authorName = author,
        authorTimeSec = BASE_TIME - index * HOUR_SECONDS,
        subject = "$subject — $message",
    )

    private companion object {
        const val BASE_TIME = 1_780_000_000L
        const val HOUR_SECONDS = 3_600L
    }
}

internal class DemoWatcher : RepoWatcher {
    override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) = Unit

    override fun stop() = Unit
}
