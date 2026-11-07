package com.jmartinezcampos.freetree.app.ui

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind

internal data class BranchPickerEntry(
    val displayName: String,
    val localName: String,
    val remoteRef: String?,
    val isRemote: Boolean,
    val isCurrent: Boolean,
) {
    val key: String get() = "$displayName|${remoteRef.orEmpty()}"
}

internal fun buildBranchPickerEntries(
    refs: List<GitRef>,
    currentBranch: String?,
): List<BranchPickerEntry> {
    val localBranches = refs
        .asSequence()
        .filter { it.kind == RefKind.LOCAL_BRANCH }
        .sortedWith(
            compareByDescending<GitRef> { it.shortName == currentBranch }
                .thenBy { it.shortName },
        )
        .map { ref ->
            BranchPickerEntry(
                displayName = ref.shortName,
                localName = ref.shortName,
                remoteRef = null,
                isRemote = false,
                isCurrent = ref.shortName == currentBranch || ref.isCurrentBranch,
            )
        }
        .toList()

    val localNames = localBranches.map { it.localName }.toSet()
    val remoteBranches = refs
        .asSequence()
        .filter { it.kind == RefKind.REMOTE_BRANCH }
        .filter { remoteBranchName(it) !in localNames }
        .sortedBy { it.shortName }
        .map { ref ->
            val logicalName = remoteBranchName(ref)
            BranchPickerEntry(
                displayName = ref.shortName,
                localName = logicalName,
                remoteRef = ref.shortName,
                isRemote = true,
                isCurrent = false,
            )
        }
        .toList()

    return localBranches + remoteBranches
}

internal fun filterBranchPickerEntries(
    entries: List<BranchPickerEntry>,
    query: String,
): List<BranchPickerEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { it.displayName.contains(needle, ignoreCase = true) }
}

private fun remoteBranchName(ref: GitRef): String = ref.shortName.substringAfter('/')
