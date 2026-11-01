package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class CommitNameKind { BRANCH, TAG }

internal sealed interface CommitActionDialog {
    data class CreateName(val kind: CommitNameKind, val hash: String) : CommitActionDialog
    data class DeleteLocal(val branch: String, val safe: Boolean) : CommitActionDialog
    data class DeleteRemote(
        val remote: String,
        val branch: String,
        val safe: Boolean,
    ) : CommitActionDialog
}

@Composable
internal fun CommitActionDialogHost(
    dialog: CommitActionDialog?,
    onCreateName: (CommitNameKind, String, String) -> Unit,
    onDeleteLocal: (String) -> Unit,
    onDeleteRemote: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is CommitActionDialog.CreateName -> NameDialog(
            kind = dialog.kind,
            onConfirm = { name -> onCreateName(dialog.kind, name, dialog.hash) },
            onDismiss = onDismiss,
        )
        is CommitActionDialog.DeleteLocal -> UnsafeDeleteDialog(
            title = "Branch not fully merged",
            message = "The branch \"${dialog.branch}\" contains commits that are not reachable " +
                "from the current HEAD or another local branch. Deleting it may lose those commits.",
            confirmLabel = "Delete anyway",
            onConfirm = { onDeleteLocal(dialog.branch) },
            onDismiss = onDismiss,
        )
        is CommitActionDialog.DeleteRemote -> UnsafeDeleteDialog(
            title = "Delete remote branch",
            message = buildString {
                append("Delete remote branch \"${dialog.remote}/${dialog.branch}\"? ")
                append("This removes the branch from the remote and cannot be undone.")
                if (!dialog.safe) {
                    append("\n\nThis branch is not merged into the current HEAD or another local branch.")
                }
            },
            confirmLabel = "Delete remote branch",
            onConfirm = { onDeleteRemote(dialog.remote, dialog.branch) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun NameDialog(
    kind: CommitNameKind,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isBranch = kind == CommitNameKind.BRANCH
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val title = if (isBranch) "Create branch here" else "Create tag here"

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.COMMIT_NAME_DIALOG),
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = if (isBranch) {
                        "Enter a name for the new branch. It will be created at the selected commit and checked out."
                    } else {
                        "Enter a name for the new lightweight tag at the selected commit."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(if (isBranch) "Branch name" else "Tag name") },
                    isError = error != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(UiTestTags.COMMIT_NAME_INPUT),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag(UiTestTags.COMMIT_NAME_ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val problem = validateRefName(name, isBranch)
                    if (problem != null) {
                        error = problem
                    } else {
                        onConfirm(name.trim())
                    }
                },
                modifier = Modifier.testTag(UiTestTags.COMMIT_NAME_CONFIRM),
            ) {
                Text(if (isBranch) "Create and checkout" else "Create tag")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.COMMIT_NAME_CANCEL),
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun UnsafeDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.DELETE_BRANCH_DIALOG),
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTestTags.DELETE_BRANCH_CONFIRM),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DELETE_BRANCH_CANCEL),
            ) {
                Text("Cancel")
            }
        },
    )
}

internal fun validateRefName(raw: String, isBranch: Boolean): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "Name cannot be empty."
    if (name.startsWith("-")) return "Name cannot start with '-'."
    if (name.endsWith("/") || name.endsWith(".")) return "Name cannot end with '/' or '.'."
    if (name.contains("..") || name.contains("//")) return "Name cannot contain '..' or '//'."
    if (name.endsWith(".lock")) return "Name cannot end with '.lock'."
    if (name.any { it in " ~^:?*[\\" }) return "Name contains characters that are not allowed."
    if (name.contains("@{") || name == "@") return "Name contains characters that are not allowed."
    if (name.any { it.code < 32 || it.code == 127 }) return "Name contains control characters."
    if (isBranch && name == "HEAD") return "HEAD is not a valid branch name."
    return null
}
