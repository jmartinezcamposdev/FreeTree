package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.session.UserMessage

private const val COPY_ALL_LABEL = "Copy all"

@Suppress("DEPRECATION")
@Composable
internal fun DiffView(
    diff: DiffText?,
    loading: Boolean,
    error: UserMessage.Error?,
    onLoadFull: () -> Unit,
    selectionState: SelectionState = rememberSelectionState(),
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(diff) {
        selectionState.clear()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .testTag(UiTestTags.DIFF_VIEW),
    ) {
        when {
            error != null -> {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(GraphStyle.SPACE_MD)
                            .testTag(UiTestTags.DIFF_ERROR),
                    ) {
                        Text(
                            text = error.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                        )
                        val stderr = error.stderr
                        if (!stderr.isNullOrBlank()) {
                            Text(
                                text = stderr,
                                style = GraphStyle.monoTextStyle,
                                color = colors.onSurface,
                                modifier = Modifier.testTag(UiTestTags.ERROR_STDERR),
                            )
                        }
                    }
                }
            }
            diff == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(color = colors.primary)
                    } else {
                        Text(
                            text = "Select a file to see its diff",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
            diff.binary -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Binary file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.testTag(UiTestTags.DIFF_BINARY),
                    )
                }
            }
            else -> {
                val visibleLines = remember(diff) {
                    diff.lines.filterNot { it.kind == DiffLineKind.FILE_HEADER }
                }
                if (diff.truncated) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.diffNoteBackground)
                            .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
                    ) {
                        Text(
                            text = "Diff truncated because it is large.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurface,
                        )
                        TextButton(
                            onClick = onLoadFull,
                            modifier = Modifier.testTag(UiTestTags.DIFF_LOAD_FULL),
                        ) {
                            Text("Load full")
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (visibleLines.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(UiTestTags.DIFF_EMPTY),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No textual changes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceMuted,
                            )
                        }
                    } else {
                        ContextMenuDataProvider(
                            items = {
                                listOf(
                                    ContextMenuItem(
                                        label = COPY_ALL_LABEL,
                                        enabled = true,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(diffCopyText(diff)))
                                        },
                                    ),
                                )
                            },
                        ) {
                            SelectionContainer(
                                state = selectionState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(end = GraphStyle.SCROLLBAR_GUTTER),
                                ) {
                                    items(items = visibleLines) { line ->
                                        DiffLineRow(line)
                                    }
                                }
                            }
                        }
                        ModernScrollbar(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            alwaysVisible = false,
                            testTag = UiTestTags.DIFF_SCROLLBAR,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Text copied by "Copy all": every loaded git diff line, in order, with no gutter numbers and no
 * +/-/space markers. File headers stay out because they are not shown in the diff either.
 */
internal fun diffCopyText(diff: DiffText): String =
    diff.lines
        .asSequence()
        .filterNot { it.kind == DiffLineKind.FILE_HEADER }
        .joinToString("\n") { it.text }

@Composable
private fun DiffLineRow(line: DiffLine) {
    val colors = LocalGraphColors.current
    val background = when (line.kind) {
        DiffLineKind.ADD -> colors.diffAddBackground
        DiffLineKind.DEL -> colors.diffDelBackground
        DiffLineKind.HUNK_HEADER -> colors.diffHunkBackground
        DiffLineKind.FILE_HEADER -> colors.diffFileHeaderBackground
        DiffLineKind.NOTE -> colors.diffNoteBackground
        DiffLineKind.CONTEXT -> colors.background
    }
    val prefix = when (line.kind) {
        DiffLineKind.ADD -> "+"
        DiffLineKind.DEL -> "-"
        DiffLineKind.CONTEXT -> " "
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.DIFF_ROW_HEIGHT)
            .background(background)
            .testTag(UiTestTags.diffLine(line.oldNo, line.newNo)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisableSelection {
            Text(
                text = line.oldNo?.toString().orEmpty(),
                style = GraphStyle.monoTextStyle,
                color = colors.diffLineNumber,
                textAlign = TextAlign.End,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                modifier = Modifier
                    .width(GraphStyle.DIFF_LINE_NUMBER_WIDTH)
                    .padding(end = GraphStyle.SPACE_XS),
            )
            Text(
                text = line.newNo?.toString().orEmpty(),
                style = GraphStyle.monoTextStyle,
                color = colors.diffLineNumber,
                textAlign = TextAlign.End,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                modifier = Modifier
                    .width(GraphStyle.DIFF_LINE_NUMBER_WIDTH)
                    .padding(end = GraphStyle.SPACE_XS),
            )
        }
        if (prefix.isNotEmpty()) {
            // The +/-/space marker stays visible but is excluded from copied text.
            DisableSelection {
                Text(
                    text = prefix,
                    style = GraphStyle.monoTextStyle,
                    color = colors.onSurface,
                    maxLines = GraphStyle.MAX_TEXT_LINES,
                    modifier = Modifier.padding(start = GraphStyle.SPACE_XXS),
                )
            }
        }
        Text(
            text = truncateDiffLine(line.text),
            style = GraphStyle.monoTextStyle,
            color = colors.onSurface,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Clip,
            modifier = if (prefix.isEmpty()) {
                Modifier.padding(start = GraphStyle.SPACE_XXS)
            } else {
                Modifier
            },
        )
    }
}

/**
 * docs/11: very long diff lines are truncated on screen only; the full line stays in the model so
 * "load full" and copy/selection never change the data.
 */
private fun truncateDiffLine(text: String): String =
    if (text.length > GraphStyle.MAX_DIFF_LINE_CHARS) {
        text.take(GraphStyle.MAX_DIFF_LINE_CHARS) + "…"
    } else {
        text
    }
