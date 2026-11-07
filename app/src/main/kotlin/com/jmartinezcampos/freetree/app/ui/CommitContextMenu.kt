package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind

internal sealed interface CommitMenuAction {
    data class CopyText(val text: String) : CommitMenuAction
    data class CheckoutLocal(val branch: String) : CommitMenuAction
    data class CheckoutRemote(val localBranch: String, val remoteRef: String) : CommitMenuAction
    data class CheckoutDetached(val hash: String) : CommitMenuAction
    data class CreateBranch(val hash: String) : CommitMenuAction
    data class CreateTag(val hash: String) : CommitMenuAction
    data class DeleteLocal(val branch: String, val hash: String) : CommitMenuAction
    data class DeleteRemote(val remote: String, val branch: String, val hash: String) : CommitMenuAction
}

internal sealed interface CommitMenuEntry {
    data class Item(
        val label: String,
        val action: CommitMenuAction,
        val enabled: Boolean = true,
    ) : CommitMenuEntry

    data object Separator : CommitMenuEntry
}

internal fun buildCommitMenuEntries(
    refs: List<GitRef>,
    hash: String,
    head: HeadInfo?,
    busy: Boolean,
): List<CommitMenuEntry> {
    val localAtCommit = refs.filter { it.kind == RefKind.LOCAL_BRANCH && it.commit == hash }
    val remoteAtCommit = refs
        .filter { it.kind == RefKind.REMOTE_BRANCH && it.commit == hash }
        .sortedWith(compareBy({ !it.shortName.startsWith("origin/") }, { it.shortName }))
    val allLocalNames = refs
        .asSequence()
        .filter { it.kind == RefKind.LOCAL_BRANCH }
        .map { it.shortName }
        .toSet()

    val entries = mutableListOf<CommitMenuEntry>()
    entries += CommitMenuEntry.Item(
        label = "Copy commit hash",
        action = CommitMenuAction.CopyText(hash),
        enabled = !busy,
    )

    val copiedNames = LinkedHashSet<String>()
    localAtCommit.forEach { copiedNames += it.shortName }
    remoteAtCommit.forEach { copiedNames += remoteBranchName(it) }
    copiedNames.forEach { name ->
        entries += CommitMenuEntry.Item(
            label = "Copy branch name: $name",
            action = CommitMenuAction.CopyText(name),
            enabled = !busy,
        )
    }

    val checkoutEntries = mutableListOf<CommitMenuEntry>()
    val localCheckoutNames = LinkedHashSet<String>()
    localAtCommit.forEach { ref ->
        if (localCheckoutNames.add(ref.shortName)) {
            checkoutEntries += CommitMenuEntry.Item(
                label = "Checkout ${ref.shortName}",
                action = CommitMenuAction.CheckoutLocal(ref.shortName),
                enabled = !busy,
            )
        }
    }
    remoteAtCommit.forEach { ref ->
        val logicalName = remoteBranchName(ref)
        if (logicalName in allLocalNames || logicalName in localCheckoutNames) {
            if (localCheckoutNames.add(logicalName)) {
                checkoutEntries += CommitMenuEntry.Item(
                    label = "Checkout $logicalName",
                    action = CommitMenuAction.CheckoutLocal(logicalName),
                    enabled = !busy,
                )
            }
        } else {
            checkoutEntries += CommitMenuEntry.Item(
                label = "Checkout local $logicalName (from ${ref.shortName})",
                action = CommitMenuAction.CheckoutRemote(logicalName, ref.shortName),
                enabled = !busy,
            )
        }
    }
    if (localAtCommit.isEmpty() && remoteAtCommit.isEmpty()) {
        checkoutEntries += CommitMenuEntry.Item(
            label = "Checkout commit ${abbreviateHash(hash)}",
            action = CommitMenuAction.CheckoutDetached(hash),
            enabled = !busy,
        )
    }
    if (checkoutEntries.isNotEmpty()) {
        entries += CommitMenuEntry.Separator
        entries += checkoutEntries
    }

    entries += CommitMenuEntry.Separator
    entries += CommitMenuEntry.Item(
        label = "Create branch here…",
        action = CommitMenuAction.CreateBranch(hash),
        enabled = !busy,
    )
    entries += CommitMenuEntry.Item(
        label = "Create tag here…",
        action = CommitMenuAction.CreateTag(hash),
        enabled = !busy,
    )

    val deleteEntries = mutableListOf<CommitMenuEntry>()
    localAtCommit.forEach { ref ->
        deleteEntries += CommitMenuEntry.Item(
            label = "Delete local branch ${ref.shortName}",
            action = CommitMenuAction.DeleteLocal(ref.shortName, hash),
            enabled = !busy && !ref.isCurrentBranch && head?.branchShortName != ref.shortName,
        )
    }
    remoteAtCommit.forEach { ref ->
        val remote = ref.shortName.substringBefore('/')
        val branch = remoteBranchName(ref)
        if (remote.isNotEmpty() && branch.isNotEmpty()) {
            deleteEntries += CommitMenuEntry.Item(
                label = "Delete remote branch ${ref.shortName}",
                action = CommitMenuAction.DeleteRemote(remote, branch, hash),
                enabled = !busy,
            )
        }
    }
    if (deleteEntries.isNotEmpty()) {
        entries += CommitMenuEntry.Separator
        entries += deleteEntries
    }

    return entries
}

private fun remoteBranchName(ref: GitRef): String = ref.shortName.substringAfter('/')

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun CommitContextMenu(
    refs: List<GitRef>,
    hash: String,
    head: HeadInfo?,
    busy: Boolean,
    onAction: (CommitMenuAction) -> Unit,
    onSecondaryClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = remember(hash) { ContextMenuState() }
    val clipboard = LocalClipboardManager.current
    val latestSecondaryClick = rememberUpdatedState(onSecondaryClick)
    ContextMenuArea(
        state = state,
        items = {
            buildCommitMenuEntries(refs, hash, head, busy).map { entry ->
                when (entry) {
                    CommitMenuEntry.Separator -> ContextMenuSeparator()
                    is CommitMenuEntry.Item -> ContextMenuItem(
                        label = entry.label,
                        enabled = entry.enabled,
                    ) {
                        when (val action = entry.action) {
                            is CommitMenuAction.CopyText -> {
                                clipboard.setText(AnnotatedString(action.text))
                                onAction(action)
                            }
                            else -> onAction(action)
                        }
                    }
                }
            }
        },
    ) {
        Box(
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            latestSecondaryClick.value()
                        }
                    }
                }
            },
        ) {
            content()
        }
    }
}
