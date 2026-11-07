package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.filter.BranchFilter
import kotlinx.serialization.Serializable

@Serializable
data class TabState(
    val id: String,
    val path: String,
    val query: String = "",
    val hiddenRefs: Set<String> = emptySet(),
    val showRemotes: Boolean = true,
    val showTags: Boolean = true,
    val showHead: Boolean = true,
    val branchFilter: BranchFilter = BranchFilter(),
    val selectedHash: String? = null,
    val scrollAnchorHash: String? = null,
)
