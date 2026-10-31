package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.jmartinezcampos.freetree.core.session.MessageLevel
import com.jmartinezcampos.freetree.core.session.MessageLog
import com.jmartinezcampos.freetree.core.session.MessageLogEntry
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun MessagesWindow(
    messageLog: MessageLog,
    themeMode: ThemeMode,
    onDismiss: () -> Unit,
) {
    Window(
        onCloseRequest = onDismiss,
        title = "Messages Log",
        state = rememberWindowState(
            width = GraphStyle.MESSAGES_WINDOW_WIDTH,
            height = GraphStyle.MESSAGES_WINDOW_HEIGHT,
        ),
    ) {
        AppTheme(themeMode) {
            MessagesLogContent(messageLog)
        }
    }
}

@Composable
internal fun MessagesLogContent(messageLog: MessageLog) {
    val entries by messageLog.entries.collectAsState()
    val colors = LocalGraphColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(UiTestTags.MESSAGES_WINDOW),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = GraphStyle.SPACE_XL, vertical = GraphStyle.SPACE_LG),
        ) {
            Text(
                text = "Messages Log",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
            )
            Text(
                text = if (entries.size == 1) {
                    "1 message from all repositories, kept for this session."
                } else {
                    "${entries.size} messages from all repositories, kept for this session."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
        Divider()
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTestTags.MESSAGES_EMPTY),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No messages yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                )
            }
        } else {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = GraphStyle.SCROLLBAR_GUTTER)
                        .testTag(UiTestTags.MESSAGES_LIST),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        MessageLogRow(entry)
                        Divider()
                    }
                }
                ModernScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun MessageLogRow(entry: MessageLogEntry) {
    val colors = LocalGraphColors.current
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val error = entry.level == MessageLevel.ERROR
    val levelColor = if (error) colors.error else colors.onSurfaceMuted
    val textColor = if (error) colors.error else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GraphStyle.SPACE_XL, vertical = GraphStyle.SPACE_SM)
            .testTag(UiTestTags.messageLogRow(entry.id)),
        verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            text = formatLogTime(entry.timestampMillis),
            style = GraphStyle.monoTextStyle,
            color = colors.onSurfaceMuted,
            modifier = Modifier.width(GraphStyle.LOG_TIMESTAMP_WIDTH),
        )
        Text(
            text = if (error) "ERROR" else "INFO",
            style = MaterialTheme.typography.labelSmall,
            color = levelColor,
            modifier = Modifier.width(GraphStyle.LOG_LEVEL_WIDTH),
        )
        Text(
            text = entry.repoName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(GraphStyle.LOG_REPO_WIDTH)
                .padding(end = GraphStyle.SPACE_LG),
        )
        Column(Modifier.weight(GraphStyle.FILL_WEIGHT)) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            if (expanded) {
                listOfNotNull(
                    entry.repoPath.takeIf { it.isNotBlank() },
                    entry.stderr?.takeIf { it.isNotBlank() },
                ).forEach { detail ->
                    SelectionContainer {
                        Text(
                            text = detail,
                            style = GraphStyle.monoTextStyle,
                            color = colors.onSurfaceMuted,
                            modifier = Modifier.padding(top = GraphStyle.SPACE_XS),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(GraphStyle.SPACE_MD))
        LogAction(
            description = "Copy message",
            testTag = UiTestTags.messageLogCopy(entry.id),
            onClick = { copyToClipboard(messageCopyText(entry)) },
        ) {
            CopyIcon()
        }
        Spacer(Modifier.width(GraphStyle.SPACE_XXS))
        LogAction(
            description = if (expanded) "Collapse message" else "Expand message",
            testTag = UiTestTags.messageLogToggle(entry.id),
            onClick = { expanded = !expanded },
        ) {
            Text(
                text = if (expanded) "▼" else "▲",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun LogAction(
    description: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(GraphStyle.LOG_ACTION_SIZE)
            .clip(RoundedCornerShape(GraphStyle.LOG_CORNER_RADIUS))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun CopyIcon() {
    val color = LocalGraphColors.current.onSurfaceMuted
    Canvas(Modifier.size(GraphStyle.LOG_ICON_SIZE)) {
        val stroke = Stroke(width = GraphStyle.LOG_ICON_STROKE.toPx())
        val page = Size(size.width * 0.62f, size.height * 0.62f)
        val corner = CornerRadius(size.width * 0.12f)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.32f, 0f),
            size = page,
            cornerRadius = corner,
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, size.height * 0.32f),
            size = page,
            cornerRadius = corner,
            style = stroke,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(GraphStyle.DIVIDER_THICKNESS)
            .background(LocalGraphColors.current.divider),
    )
}

private fun formatLogTime(timestampMillis: Long): String =
    LOG_TIME_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

internal fun messageCopyText(entry: MessageLogEntry): String = buildString {
    append(entry.text.trimEnd())
    val details = entry.stderr?.trimEnd()
    if (!details.isNullOrBlank()) {
        append('\n')
        append(details)
    }
}

private fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        System.err.println("FreeTree: cannot copy the message: ${e.message}")
    }
}
