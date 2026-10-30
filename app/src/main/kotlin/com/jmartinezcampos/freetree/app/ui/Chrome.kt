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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.TOOLBAR_HEIGHT)
            .background(colors.surface)
            .testTag(UiTestTags.TOOLBAR)
            .padding(horizontal = GraphStyle.SPACE_MD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
    ) {
        ToolbarButton(
            text = "⟳",
            tag = UiTestTags.REFRESH,
            enabled = session != null && !busy,
            onClick = { session?.refresh() },
        )
        ToolbarButton(
            text = "Pull",
            tag = UiTestTags.PULL,
            enabled = session != null && !busy,
            onClick = { session?.pull() },
        )
        ToolbarButton(
            text = "Push",
            tag = UiTestTags.PUSH,
            enabled = session != null && !busy,
            onClick = { session?.push() },
        )
        Spacer(Modifier.width(GraphStyle.SPACE_MD))
        val status = state.status
        val branch = state.head?.branchShortName?.takeIf { it.isNotBlank() }
            ?: status?.branchHead?.let(::abbreviateHash)
            ?: "detached HEAD"
        Text(
            text = branch,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(UiTestTags.BRANCH_LABEL),
        )
        if (status != null) {
            Spacer(Modifier.width(GraphStyle.SPACE_SM))
            Text(
                text = "↑${status.ahead} ↓${status.behind}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
        val operation = state.operationInProgress
        if (operation != null) {
            Spacer(Modifier.width(GraphStyle.SPACE_MD))
            Text(
                text = operation,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
        Spacer(Modifier.weight(GraphStyle.FILL_WEIGHT))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(GraphStyle.BUSY_INDICATOR_SIZE)
                    .testTag(UiTestTags.BUSY_INDICATOR),
                color = colors.primary,
                strokeWidth = GraphStyle.STROKE_WIDTH,
            )
        }
        if (state.busy != null) {
            Spacer(Modifier.width(GraphStyle.SPACE_SM))
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
}

private fun busyLabel(op: BusyOp): String = when (op) {
    BusyOp.STAGE -> "Updating index…"
    BusyOp.DISCARD -> "Discarding…"
    BusyOp.COMMIT -> "Committing…"
    BusyOp.PUSH -> "Pushing…"
    BusyOp.PULL -> "Pulling…"
}

@Composable
private fun ToolbarButton(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(GraphStyle.CONTROL_HEIGHT)
            .testTag(tag),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
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
