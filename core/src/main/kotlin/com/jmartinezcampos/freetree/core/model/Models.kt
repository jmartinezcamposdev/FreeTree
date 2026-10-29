package com.jmartinezcampos.freetree.core.model

import java.nio.file.Path

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
    val workTree: Path,
    val gitDir: Path,
    val commonDir: Path,
)

sealed interface GitResult {
    data class Ok(val stdout: String) : GitResult
    data class Failure(val exitCode: Int, val stderr: String) : GitResult
}

class GitException(val exitCode: Int, val stderr: String) : Exception(stderr)
