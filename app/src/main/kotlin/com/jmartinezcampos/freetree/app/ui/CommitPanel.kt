package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.UserMessage

@Composable
internal fun CommitPanel(
    state: RepoUiState,
    session: RepoSession?,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val detail = state.commitDetail
    val filesScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .testTag(UiTestTags.COMMIT_PANEL),
    ) {
        if (detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            return@Column
        }

        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_MD),
                verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
            ) {
                Text(
                    text = detail.hash,
                    style = GraphStyle.monoTextStyle,
                    color = colors.onSurface,
                    modifier = Modifier.testTag(UiTestTags.COMMIT_HASH),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = detail.authorName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.testTag(UiTestTags.COMMIT_AUTHOR),
                    )
                    Text(
                        text = formatDate(detail.authorTimeSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Parents:",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                    )
                    if (detail.parents.isEmpty()) {
                        Text(
                            text = "none (root)",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                        )
                    } else {
                        detail.parents.forEach { parent ->
                            Text(
                                text = abbreviateHash(parent),
                                style = GraphStyle.monoTextStyle,
                                color = colors.primary,
                                modifier = Modifier
                                    .testTag(UiTestTags.parentLink(parent))
                                    .clickable { session?.selectCommit(parent) },
                            )
                        }
                    }
                }
                if (detail.parents.size > 1) {
                    Text(
                        text = "Merge commit with ${detail.parents.size} parents",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                    )
                }
                Text(
                    text = detail.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = GraphStyle.SPACE_XS),
                )
            }
        }

        Text(
            text = "Changed files (${state.changedFiles.size})",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XS),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(GraphStyle.FILL_WEIGHT),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = GraphStyle.SCROLLBAR_GUTTER)
                    .verticalScroll(filesScrollState),
            ) {
                state.changedFiles.forEach { change ->
                    ChangedFileRow(
                        change = change,
                        selected = state.openDiffTarget == DiffTarget.InCommit(detail.hash, change),
                        onClick = { session?.openDiff(DiffTarget.InCommit(detail.hash, change)) },
                    )
                }
            }
            ModernScrollbar(
                scrollState = filesScrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
                alwaysVisible = false,
                testTag = UiTestTags.COMMIT_FILES_SCROLLBAR,
            )
        }

        val target = state.openDiffTarget
        if (target is DiffTarget.InCommit && target.hash == detail.hash) {
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
    }
}

@Composable
private fun ChangedFileRow(
    change: FileChange,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val status = statusLabel(change.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.selectedRow else colors.surface)
            .clickable(onClick = onClick)
            .testTag(UiTestTags.changedFile(change.path))
            .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(change.status, colors),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = change.path,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        val oldPath = change.oldPath
        if (oldPath != null) {
            Text(
                text = "from $oldPath",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
