package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import kotlinx.coroutines.flow.Flow

interface GitBackend {
    suspend fun refs(): List<GitRef>
    suspend fun head(): HeadInfo
    fun logStream(spec: LogSpec): Flow<RawCommit> // cold, cancellable, throws GitException

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
