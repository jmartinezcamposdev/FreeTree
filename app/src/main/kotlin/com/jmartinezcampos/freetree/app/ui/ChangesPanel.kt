package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.UserMessage

@Composable
internal fun ChangesPanel(
    state: RepoUiState,
    session: RepoSession?,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val status = state.status
    val entries = status?.entries.orEmpty()
    val conflicts = entries.filter { it.kind == StatusKind.UNMERGED }
    val staged = entries.filter {
        it.kind != StatusKind.UNMERGED && it.index != '?' && !isStatusBlank(it.index)
    }
    val unstaged = entries.filter {
        it.kind != StatusKind.UNMERGED &&
            (it.kind == StatusKind.UNTRACKED || !isStatusBlank(it.worktree))
    }
    var message by remember(session?.id) { mutableStateOf("") }
    // Keyed by session too: switching tabs must not leave a confirmation for another repository.
    var pendingDiscard by remember(session?.id) { mutableStateOf<StatusEntry?>(null) }
    val changesScrollState = rememberScrollState()
    val busy = state.busy != null
    val canCommit = message.isNotBlank() && staged.isNotEmpty() && conflicts.isEmpty() && !busy && session != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .testTag(UiTestTags.CHANGES_PANEL),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(GraphStyle.FILL_WEIGHT),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = GraphStyle.SCROLLBAR_GUTTER)
                    .verticalScroll(changesScrollState),
            ) {
                if (status == null) {
                    Text(
                        text = "Loading working tree…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(GraphStyle.SPACE_LG),
                    )
                } else if (entries.isEmpty()) {
                    Text(
                        text = "Working tree clean",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(GraphStyle.SPACE_LG),
                    )
                }

                if (conflicts.isNotEmpty()) {
                    ChangeSectionHeader(title = "Conflicts", count = conflicts.size, tag = UiTestTags.CONFLICTS_SECTION)
                    Text(
                        text = "Resolve conflicts outside FreeTree. Conflict actions are disabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XS),
                    )
                    conflicts.forEach { entry ->
                        ChangeFileRow(
                            entry = entry,
                            status = entry.index,
                            tag = UiTestTags.conflictFile(entry.path),
                            readOnly = true,
                            onOpen = { session?.openDiff(DiffTarget.Unstaged(entry.path)) },
                        )
                    }
                }

                if (staged.isNotEmpty()) {
                    ChangeSectionHeader(
                        title = "Staged",
                        count = staged.size,
                        tag = UiTestTags.STAGED_SECTION,
                        action = {
                            TextButton(
                                onClick = { session?.unstage(staged.map { it.path }) },
                                enabled = !busy,
                                modifier = Modifier.testTag(UiTestTags.UNSTAGE_ALL),
                            ) {
                                Text("Unstage all", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                    staged.forEach { entry ->
                        ChangeFileRow(
                            entry = entry,
                            status = entry.index,
                            tag = UiTestTags.stagedFile(entry.path),
                            onOpen = { session?.openDiff(DiffTarget.Staged(entry.path)) },
                            actions = {
                                TextButton(
                                    onClick = { session?.discardStaged(listOf(entry.path)) },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .height(GraphStyle.CONTROL_HEIGHT)
                                        .testTag(UiTestTags.discardStaged(entry.path)),
                                ) {
                                    Text("Discard staged", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                        )
                    }
                }

                if (unstaged.isNotEmpty()) {
                    ChangeSectionHeader(
                        title = "Unstaged",
                        count = unstaged.size,
                        tag = UiTestTags.UNSTAGED_SECTION,
                        action = {
                            TextButton(
                                onClick = { session?.stage(unstaged.map { it.path }) },
                                enabled = !busy,
                                modifier = Modifier.testTag(UiTestTags.STAGE_ALL),
                            ) {
                                Text("Stage all", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                    unstaged.forEach { entry ->
                        ChangeFileRow(
                            entry = entry,
                            status = if (entry.kind == StatusKind.UNTRACKED) '?' else entry.worktree,
                            tag = UiTestTags.unstagedFile(entry.path),
                            onOpen = { session?.openDiff(DiffTarget.Unstaged(entry.path)) },
                            actions = {
                                TextButton(
                                    onClick = { session?.stage(listOf(entry.path)) },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .height(GraphStyle.CONTROL_HEIGHT)
                                        .testTag(UiTestTags.stageFile(entry.path)),
                                ) {
                                    Text("Stage", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(
                                    onClick = { pendingDiscard = entry },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .height(GraphStyle.CONTROL_HEIGHT)
                                        .testTag(UiTestTags.discardUnstaged(entry.path)),
                                ) {
                                    Text("Discard unstaged changes", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                        )
                    }
                }
            }
            ModernScrollbar(
                scrollState = changesScrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
                alwaysVisible = false,
                testTag = UiTestTags.CHANGES_SCROLLBAR,
            )
        }

        val target = state.openDiffTarget
        if (target is DiffTarget.Staged || target is DiffTarget.Unstaged) {
            val error = state.message as? UserMessage.Error
            DiffView(
                diff = state.openDiff,
                loading = state.openDiff == null && error == null,
                error = if (state.openDiff == null) error else null,
                onLoadFull = { session?.let { loadFullDiff(it, target) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(GraphStyle.FILL_WEIGHT),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised)
                .padding(GraphStyle.SPACE_MD),
            verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        text = "Commit message",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                    )
                },
                minLines = GraphStyle.MESSAGE_MIN_LINES,
                maxLines = GraphStyle.MESSAGE_MAX_LINES,
                enabled = session != null && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTags.COMMIT_MESSAGE),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        session?.commit(message)
                        message = ""
                    },
                    enabled = canCommit,
                    modifier = Modifier
                        .height(GraphStyle.CONTROL_HEIGHT)
                        .testTag(UiTestTags.COMMIT_BUTTON),
                ) {
                    Text("Commit", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    val discard = pendingDiscard
    if (discard != null) {
        DiscardDialog(
            entry = discard,
            onDismiss = { pendingDiscard = null },
            onConfirm = {
                session?.discardUnstaged(listOf(discard.path))
                pendingDiscard = null
            },
        )
    }
}

@Composable
private fun ChangeSectionHeader(
    title: String,
    count: Int,
    tag: String,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGraphColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .testTag(tag)
            .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        action?.invoke()
    }
}

@Composable
private fun ChangeFileRow(
    entry: StatusEntry,
    status: Char,
    tag: String,
    onOpen: () -> Unit,
    readOnly: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGraphColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(tag)
            .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(status, colors),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = entry.path,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        if (readOnly) {
            Text(
                text = "conflict",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
        } else {
            actions?.invoke()
        }
    }
}

@Composable
private fun DiscardDialog(
    entry: StatusEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val warning = if (entry.kind == StatusKind.UNTRACKED) {
        "This permanently deletes \"${entry.path}\" from disk. This cannot be undone."
    } else {
        "This restores \"${entry.path}\" from the index and permanently discards its unstaged " +
            "changes on disk. The staged content is kept. This cannot be undone."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.DISCARD_DIALOG),
        title = { Text("Discard unstaged changes?") },
        text = { Text(warning) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTestTags.DISCARD_CONFIRM),
            ) {
                Text("Discard changes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DISCARD_CANCEL),
            ) {
                Text("Cancel")
            }
        },
    )
}
