package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.query.parseQuery
import com.jmartinezcampos.freetree.core.session.BusyOp
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.TabState
import kotlinx.coroutines.delay

private const val NEW_TAB_ICON_TAG = "tab-new-icon"

@Composable
internal fun TabBar(
    tabs: List<TabState>,
    activeId: String?,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalGraphColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.TAB_BAR_HEIGHT)
            .background(colors.surface)
            .testTag(UiTestTags.TAB_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(GraphStyle.TAB_BAR_START_PADDING))
        tabs.forEachIndexed { index, tab ->
            TabItem(
                tab = tab,
                index = index,
                active = tab.id == activeId,
                onActivate = { onActivate(tab.id) },
                onClose = { onClose(tab.id) },
            )
            Spacer(Modifier.width(GraphStyle.SPACE_XXS))
        }
        NewTabButton(onOpen = onOpen)
    }
}

@Composable
private fun TabItem(
    tab: TabState,
    index: Int,
    active: Boolean,
    onActivate: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(GraphStyle.TAB_CORNER_RADIUS)
    val background = when {
        active -> colors.surfaceRaised
        hovered -> colors.hoverRow
        else -> colors.surface
    }
    Row(
        modifier = Modifier
            .height(GraphStyle.TAB_HEIGHT)
            .clip(shape)
            .background(background)
            .hoverable(interactionSource)
            .semantics { selected = active }
            .testTag(UiTestTags.tab(index))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onActivate,
            )
            .padding(horizontal = GraphStyle.TAB_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tabTitle(tab.path),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = GraphStyle.TAB_TEXT_SIZE,
                lineHeight = GraphStyle.TAB_TEXT_LINE_HEIGHT,
            ),
            color = if (active) colors.onSurface else colors.onSurfaceMuted,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(GraphStyle.TAB_TITLE_WIDTH),
        )
        Spacer(Modifier.width(GraphStyle.SPACE_SM))
        Box(
            modifier = Modifier
                .size(GraphStyle.TAB_CLOSE_SIZE)
                .testTag(UiTestTags.tabClose(index))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(GraphStyle.TAB_CLOSE_ICON_SIZE)
                    .testTag(UiTestTags.tabCloseIcon(index)),
            ) {
                val stroke = GraphStyle.TAB_CLOSE_ICON_STROKE.toPx()
                val inset = stroke / 2f
                val maxX = size.width - inset
                val maxY = size.height - inset
                drawLine(
                    color = colors.onSurfaceMuted,
                    start = Offset(inset, inset),
                    end = Offset(maxX, maxY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.onSurfaceMuted,
                    start = Offset(maxX, inset),
                    end = Offset(inset, maxY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun NewTabButton(onOpen: () -> Unit) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(GraphStyle.NEW_TAB_SIZE)
            .clip(RoundedCornerShape(GraphStyle.NEW_TAB_CORNER_RADIUS))
            .background(if (hovered) colors.hoverRow else Color.Transparent)
            .hoverable(interactionSource)
            .testTag(UiTestTags.NEW_TAB)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onOpen,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(GraphStyle.NEW_TAB_ICON_SIZE)
                .testTag(NEW_TAB_ICON_TAG),
        ) {
            val stroke = GraphStyle.NEW_TAB_ICON_STROKE.toPx()
            val inset = stroke / 2f
            val midX = size.width / 2f
            val midY = size.height / 2f
            val color = if (hovered) colors.onSurface else colors.onSurfaceMuted
            drawLine(
                color = color,
                start = Offset(inset, midY),
                end = Offset(size.width - inset, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(midX, inset),
                end = Offset(midX, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun Toolbar(
    state: RepoUiState,
    session: RepoSession?,
) {
    val colors = LocalGraphColors.current
    val busy = state.busy != null
    val status = state.status
    val branch = state.head?.branchShortName?.takeIf { it.isNotBlank() }
        ?: status?.branchHead?.let(::abbreviateHash)
        ?: "detached HEAD"
    val counts = filterCounts(state)
    var showFiltersInfo by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.TOOLBAR_HEIGHT)
            .background(colors.surface)
            .testTag(UiTestTags.TOOLBAR)
            .padding(horizontal = GraphStyle.TOOLBAR_HORIZONTAL_PADDING),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(
                text = "Push",
                tag = UiTestTags.PUSH,
                iconTag = UiTestTags.PUSH_ICON,
                icon = { color, stroke -> drawPushIcon(color, stroke) },
                enabled = session != null && !busy,
                onClick = { session?.push() },
            )
            Spacer(Modifier.width(GraphStyle.TOOLBAR_BUTTON_GAP))
            ToolbarButton(
                text = "Pull",
                tag = UiTestTags.PULL,
                iconTag = UiTestTags.PULL_ICON,
                icon = { color, stroke -> drawPullIcon(color, stroke) },
                enabled = session != null && !busy,
                onClick = { session?.pull() },
            )
            if (status != null) {
                Spacer(Modifier.width(GraphStyle.TOOLBAR_STATUS_GAP))
                Text(
                    text = "↑${status.ahead} ↓${status.behind}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.testTag(UiTestTags.AHEAD_BEHIND),
                )
            }
            if (busy) {
                Spacer(Modifier.width(GraphStyle.TOOLBAR_STATUS_GAP))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(GraphStyle.BUSY_INDICATOR_SIZE)
                            .testTag(UiTestTags.BUSY_INDICATOR),
                        color = colors.primary,
                        strokeWidth = GraphStyle.STROKE_WIDTH,
                    )
                    val currentBusy = state.busy
                    if (currentBusy != null) {
                        Text(
                            text = busyLabel(currentBusy),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
            val operation = state.operationInProgress
            if (operation != null) {
                Spacer(Modifier.width(GraphStyle.TOOLBAR_STATUS_GAP))
                Text(
                    text = operation,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
        }

        ToolbarButton(
            text = branch,
            tag = UiTestTags.BRANCH_BUTTON,
            iconTag = UiTestTags.BRANCH_ICON,
            icon = { color, stroke -> drawBranchIcon(color, stroke) },
            enabled = session != null,
            onClick = {},
            textTag = UiTestTags.BRANCH_LABEL,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = GraphStyle.TOOLBAR_BRANCH_MAX_WIDTH),
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
        ) {
            if (counts.branch > 0) {
                FilterCountBadge(
                    label = "Branches",
                    count = counts.branch,
                    tag = UiTestTags.FILTERS_BRANCH_COUNT,
                )
            }
            if (counts.other > 0) {
                FilterCountBadge(
                    label = "Other",
                    count = counts.other,
                    tag = UiTestTags.FILTERS_OTHER_COUNT,
                )
            }
            ToolbarButton(
                text = "Filters",
                tag = UiTestTags.FILTERS_BUTTON,
                iconTag = UiTestTags.FILTERS_ICON,
                icon = { color, stroke -> drawFiltersIcon(color, stroke) },
                enabled = session != null,
                onClick = { showFiltersInfo = true },
            )
        }
    }

    if (showFiltersInfo) {
        AlertDialog(
            onDismissRequest = { showFiltersInfo = false },
            modifier = Modifier.testTag(UiTestTags.FILTERS_INFO_DIALOG),
            title = { Text("Filters") },
            text = {
                Text(
                    "Editing filters will be supported in a future version of FreeTree.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showFiltersInfo = false },
                    modifier = Modifier.testTag(UiTestTags.FILTERS_INFO_OK),
                ) {
                    Text("OK")
                }
            },
        )
    }
}

private fun busyLabel(op: BusyOp): String = when (op) {
    BusyOp.STAGE -> "Updating index…"
    BusyOp.DISCARD -> "Discarding…"
    BusyOp.COMMIT -> "Committing…"
    BusyOp.PUSH -> "Pushing…"
    BusyOp.PULL -> "Pulling…"
    BusyOp.CHECKOUT -> "Checking out…"
    BusyOp.BRANCH -> "Creating branch…"
    BusyOp.TAG -> "Creating tag…"
    BusyOp.DELETE_BRANCH -> "Deleting branch…"
}

internal data class FilterCounts(
    val branch: Int,
    val other: Int,
)

internal fun filterCounts(state: RepoUiState): FilterCounts {
    val query = parseQuery(state.query).getOrNull()
    val branch = (query?.refIncludes?.size ?: 0) +
        (query?.refExcludes?.size ?: 0) +
        state.hiddenRefs.size
    val other = listOfNotNull(
        query?.author,
        query?.grep,
        query?.since,
        query?.until,
        query?.path,
    ).size
    return FilterCounts(branch = branch, other = other)
}

@Composable
private fun ToolbarButton(
    text: String,
    tag: String,
    iconTag: String,
    icon: DrawScope.(Color, Float) -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    textTag: String? = null,
    fontWeight: FontWeight = FontWeight.Medium,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS)
    val background = when {
        !enabled -> colors.surface
        hovered -> colors.primary.copy(alpha = 0.14f)
        else -> colors.surfaceRaised
    }
    val contentColor = if (enabled) colors.onSurface else colors.onSurfaceMuted
    Row(
        modifier = modifier
            .height(GraphStyle.CONTROL_HEIGHT)
            .clip(shape)
            .background(background)
            .hoverable(interactionSource)
            .testTag(tag)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = GraphStyle.SPACE_SM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
    ) {
        Canvas(
            modifier = Modifier
                .size(GraphStyle.TOOLBAR_ICON_SIZE)
                .testTag(iconTag),
        ) {
            icon(contentColor, GraphStyle.TOOLBAR_ICON_STROKE.toPx())
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = contentColor,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = if (textTag != null) Modifier.testTag(textTag) else Modifier,
        )
    }
}

@Composable
private fun FilterCountBadge(
    label: String,
    count: Int,
    tag: String,
) {
    val colors = LocalGraphColors.current
    Text(
        text = "$label $count",
        style = MaterialTheme.typography.bodySmall,
        color = if (count > 0) colors.onSurface else colors.onSurfaceMuted,
        maxLines = GraphStyle.MAX_TEXT_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(GraphStyle.CHIP_CORNER_RADIUS))
            .background(colors.surfaceRaised)
            .padding(horizontal = GraphStyle.SPACE_SM, vertical = GraphStyle.SPACE_XXS)
            .testTag(tag),
    )
}

private fun DrawScope.drawPullIcon(
    color: Color,
    stroke: Float,
) {
    val cx = size.width / 2f
    val headY = size.height * 0.62f
    drawLine(color, Offset(cx, size.height * 0.08f), Offset(cx, headY), stroke, StrokeCap.Round)
    drawLine(
        color,
        Offset(cx - size.width * 0.24f, headY - size.height * 0.22f),
        Offset(cx, headY),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(cx + size.width * 0.24f, headY - size.height * 0.22f),
        Offset(cx, headY),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(size.width * 0.16f, size.height * 0.88f),
        Offset(size.width * 0.84f, size.height * 0.88f),
        stroke,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawPushIcon(
    color: Color,
    stroke: Float,
) {
    val cx = size.width / 2f
    val headY = size.height * 0.38f
    drawLine(color, Offset(cx, size.height * 0.92f), Offset(cx, headY), stroke, StrokeCap.Round)
    drawLine(
        color,
        Offset(cx - size.width * 0.24f, headY + size.height * 0.22f),
        Offset(cx, headY),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(cx + size.width * 0.24f, headY + size.height * 0.22f),
        Offset(cx, headY),
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(size.width * 0.16f, size.height * 0.12f),
        Offset(size.width * 0.84f, size.height * 0.12f),
        stroke,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawBranchIcon(
    color: Color,
    stroke: Float,
) {
    val trunkX = size.width * 0.24f
    val topY = size.height * 0.16f
    val midY = size.height * 0.46f
    val bottomY = size.height * 0.84f
    val branchX = size.width * 0.76f
    val branchY = size.height * 0.34f
    drawLine(color, Offset(trunkX, topY), Offset(trunkX, bottomY), stroke, StrokeCap.Round)
    val path = Path().apply {
        moveTo(trunkX, midY)
        cubicTo(trunkX + size.width * 0.18f, midY, branchX - size.width * 0.18f, branchY, branchX, branchY)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    val nodeStyle = Stroke(width = stroke)
    drawCircle(color, radius = size.width * 0.12f, center = Offset(trunkX, topY), style = nodeStyle)
    drawCircle(color, radius = size.width * 0.12f, center = Offset(trunkX, bottomY), style = nodeStyle)
    drawCircle(color, radius = size.width * 0.12f, center = Offset(branchX, branchY), style = nodeStyle)
}

private fun DrawScope.drawFiltersIcon(
    color: Color,
    stroke: Float,
) {
    val path = Path().apply {
        moveTo(size.width * 0.12f, size.height * 0.16f)
        lineTo(size.width * 0.88f, size.height * 0.16f)
        lineTo(size.width * 0.60f, size.height * 0.52f)
        lineTo(size.width * 0.60f, size.height * 0.86f)
        lineTo(size.width * 0.40f, size.height * 0.74f)
        lineTo(size.width * 0.40f, size.height * 0.52f)
        close()
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

@Composable
internal fun FilterBar(
    state: RepoUiState,
    session: RepoSession?,
) {
    val colors = LocalGraphColors.current
    var refsExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(state.query) }

    LaunchedEffect(state.query) {
        if (query != state.query) query = state.query
    }
    LaunchedEffect(query, session) {
        if (session == null || query == state.query) return@LaunchedEffect
        delay(GraphStyle.QUERY_DEBOUNCE_MILLIS)
        session.applyQuery(query)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        text = "ref:origin/*  author:  msg:  path:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                    )
                },
                isError = state.queryError != null,
                modifier = Modifier
                    .weight(GraphStyle.FILL_WEIGHT)
                    .testTag(UiTestTags.QUERY_INPUT)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            session?.applyQuery(query)
                            true
                        } else {
                            false
                        }
                    },
            )
            TextButton(
                onClick = { refsExpanded = !refsExpanded },
                modifier = Modifier
                    .height(GraphStyle.CONTROL_HEIGHT)
                    .testTag(UiTestTags.REFS_TOGGLE),
            ) {
                Text(
                    text = if (refsExpanded) "Refs ▴" else "Refs ▾",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val queryError = state.queryError
        if (queryError != null) {
            Text(
                text = queryError,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier
                    .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS)
                    .testTag(UiTestTags.QUERY_ERROR),
            )
        }
        if (refsExpanded) {
            RefsPanel(state = state, session = session)
        }
    }
}

@Composable
private fun RefsPanel(
    state: RepoUiState,
    session: RepoSession?,
) {
    val colors = LocalGraphColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = GraphStyle.REFS_PANEL_MAX_HEIGHT)
            .background(colors.surfaceRaised)
            .testTag(UiTestTags.REFS_PANEL)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_LG),
        ) {
            ToggleLabel("Remotes", state.showRemotes, UiTestTags.SHOW_REMOTES) {
                session?.setShowRemotes(it)
            }
            ToggleLabel("Tags", state.showTags, UiTestTags.SHOW_TAGS) {
                session?.setShowTags(it)
            }
            ToggleLabel("HEAD", state.showHead, UiTestTags.SHOW_HEAD) {
                session?.setShowHead(it)
            }
        }
        RefGroup("Locals", state.refs.filter { it.kind == RefKind.LOCAL_BRANCH }, state, session)
        RefGroup("Remotes", state.refs.filter { it.kind == RefKind.REMOTE_BRANCH }, state, session)
        RefGroup("Tags", state.refs.filter { it.kind == RefKind.TAG }, state, session)
        if (state.refs.isEmpty()) {
            Text(
                text = "No refs",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(vertical = GraphStyle.SPACE_XS),
            )
        }
    }
}

@Composable
private fun ToggleLabel(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalGraphColors.current.onSurface,
        )
    }
}

@Composable
private fun RefGroup(
    title: String,
    refs: List<GitRef>,
    state: RepoUiState,
    session: RepoSession?,
) {
    val colors = LocalGraphColors.current
    if (refs.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceMuted,
        modifier = Modifier.padding(top = GraphStyle.SPACE_XS),
    )
    refs.forEach { ref ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = ref.fullName !in state.hiddenRefs,
                onCheckedChange = { session?.toggleRef(ref.fullName) },
                modifier = Modifier.testTag(UiTestTags.refItem(ref.fullName)),
            )
            Text(
                text = ref.shortName,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (ref.isCurrentBranch) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
