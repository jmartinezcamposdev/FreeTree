package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.filter.BranchFilter
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.WorkingStatus

data class RepoUiState(
    val graph: GraphSnapshot? = null,
    val load: LoadState = LoadState.Idle,
    val refs: List<GitRef> = emptyList(),
    val head: HeadInfo? = null,
    val labelsByCommit: Map<String, List<GitRef>> = emptyMap(),
    val status: WorkingStatus? = null,
    val operationInProgress: String? = null,
    val selection: Selection = Selection.WorkingTree,
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
    val branchFilter: BranchFilter = BranchFilter(),
    val filterBusy: Boolean = false,
    val filterError: String? = null,
    val filterVisibleRefs: Set<String>? = null,
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

enum class BusyOp { STAGE, DISCARD, COMMIT, PUSH, PULL, CHECKOUT, BRANCH, TAG, DELETE_BRANCH }

sealed interface UserMessage {
    data class Info(val text: String) : UserMessage
    data class Error(val text: String, val stderr: String? = null) : UserMessage
}
