package com.jmartinezcampos.freetree.app.ui

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind

internal fun showHeadHighlight(
    head: HeadInfo?,
    labelsByCommit: Map<String, List<GitRef>>,
    showHead: Boolean,
): Boolean {
    if (!showHead) return false
    val headHash = head?.commit ?: return false
    return labelsByCommit[headHash].orEmpty().none { ref ->
        ref.kind == RefKind.LOCAL_BRANCH || ref.kind == RefKind.REMOTE_BRANCH
    }
}
