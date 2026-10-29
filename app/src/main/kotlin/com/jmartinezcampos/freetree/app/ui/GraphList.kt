package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import com.jmartinezcampos.freetree.core.graph.GraphSnapshot
import com.jmartinezcampos.freetree.core.graph.SegKind
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.LoadState
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.Selection

@Composable
internal fun GraphList(
    state: RepoUiState,
    session: RepoSession?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val graph = state.graph
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val selectedCommitRow = when (val selection = state.selection) {
        is Selection.Commit -> graph?.indexOfHash(selection.hash)?.takeIf { it >= 0 } ?: -1
        else -> -1
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(selectedCommitRow, graph) {
        if (graph == null || selectedCommitRow < 0) return@LaunchedEffect
        listState.scrollToItem(selectedCommitRow + 1)
    }

    LaunchedEffect(listState, session, graph) {
        if (session == null || graph == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val commitIndex = index - 1
            session.setScrollAnchor(
                if (commitIndex in 0 until graph.rowCount) graph.hash(commitIndex) else null,
                commitIndex.coerceAtLeast(0),
            )
        }
    }

    fun selectRow(row: Int) {
        val target = graph ?: return
        if (row in 0 until target.rowCount) {
            session?.selectCommit(target.hash(row))
        } else {
            session?.selectWorkingTree()
        }
    }

    fun moveSelection(delta: Int) {
        if (graph == null) return
        if (graph.rowCount == 0) {
            session?.selectWorkingTree()
            return
        }
        val current = selectedCommitRow
        val target = when {
            current < 0 && delta > 0 -> 0
            current < 0 -> 0
            else -> current + delta
        }
        if (target < 0) {
            session?.selectWorkingTree()
        } else {
            selectRow(target.coerceAtMost(graph.rowCount - 1))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .testTag(UiTestTags.GRAPH_LIST)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || graph == null) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        moveSelection(1)
                        true
                    }
                    Key.DirectionUp -> {
                        moveSelection(-1)
                        true
                    }
                    Key.PageDown -> {
                        moveSelection(GraphStyle.PAGE_ROWS)
                        true
                    }
                    Key.PageUp -> {
                        moveSelection(-GraphStyle.PAGE_ROWS)
                        true
                    }
                    Key.MoveHome -> {
                        session?.selectWorkingTree()
                        true
                    }
                    Key.MoveEnd -> {
                        if (graph.rowCount == 0) {
                            session?.selectWorkingTree()
                        } else {
                            selectRow(graph.rowCount - 1)
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (state.load is LoadState.Loading || (graph != null && !graph.complete)) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GraphStyle.PROGRESS_HEIGHT)
                    .testTag(UiTestTags.GRAPH_PROGRESS),
                color = colors.primary,
            )
        }

        if (graph == null) {
            GraphPlaceholder(
                state = state,
                onRetry = { session?.refresh() },
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        if (graph.flat) {
            Text(
                text = "Flat view (content filters active)",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS),
            )
        }

        val visibleLanes = graph.maxLanes.coerceIn(1, GraphStyle.MAX_VISIBLE_LANES)
        val laneOffset = if (selectedCommitRow >= 0) {
            (graph.nodeLane(selectedCommitRow) - (visibleLanes - 1)).coerceAtLeast(0)
        } else {
            0
        }
        val headHash = state.head?.commit?.takeIf { state.showHead }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "changes") {
                ChangesGraphRow(
                    changeCount = state.status?.entries?.size ?: 0,
                    selected = state.selection is Selection.WorkingTree,
                    onClick = { session?.selectWorkingTree() },
                )
            }
            if (graph.rowCount == 0) {
                item(key = "empty") {
                    GraphEmptyState(
                        message = if (state.refs.isEmpty()) "Repository is empty" else "No visible refs",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(GraphStyle.SPACE_LG),
                    )
                }
            }
            items(
                count = graph.rowCount,
                key = { index -> graph.hash(index) },
            ) { row ->
                val hash = graph.hash(row)
                CommitGraphRow(
                    graph = graph,
                    row = row,
                    refs = state.labelsByCommit[hash].orEmpty(),
                    isHead = hash == headHash,
                    isSelected = state.selection is Selection.Commit &&
                        (state.selection as Selection.Commit).hash == hash,
                    visibleLanes = visibleLanes,
                    laneOffset = laneOffset,
                    onClick = { session?.selectCommit(hash) },
                )
            }
        }
    }
}

@Composable
private fun ChangesGraphRow(
    changeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.ROW_HEIGHT)
            .background(if (selected) colors.selectedRow else colors.background)
            .semantics { this.selected = selected }
            .testTag(UiTestTags.CHANGES_ROW)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.GRAPH_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "○",
            color = colors.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(GraphStyle.LANE_WIDTH),
        )
        Text(
            text = "Changes ($changeCount)",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun CommitGraphRow(
    graph: GraphSnapshot,
    row: Int,
    refs: List<GitRef>,
    isHead: Boolean,
    isSelected: Boolean,
    visibleLanes: Int,
    laneOffset: Int,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val hash = graph.hash(row)
    val laneWidth = GraphStyle.LANE_WIDTH * visibleLanes.toFloat()
    Box(Modifier.testTag(UiTestTags.commitRow(row)).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GraphStyle.ROW_HEIGHT)
                .background(if (isSelected) colors.selectedRow else colors.background)
                .semantics { selected = isSelected }
                .testTag(UiTestTags.commitRowForHash(hash))
                .clickable(onClick = onClick)
                .padding(horizontal = GraphStyle.GRAPH_ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaneCanvas(
                graph = graph,
                row = row,
                laneWidth = laneWidth,
                laneOffset = laneOffset,
                isHead = isHead,
            )
            Text(
                text = graph.subject(row),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(GraphStyle.FILL_WEIGHT)
                    .padding(horizontal = GraphStyle.SPACE_SM),
            )
            RefChips(refs = refs, isHead = isHead)
            Text(
                text = graph.author(row),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(GraphStyle.AUTHOR_COLUMN_WIDTH)
                    .padding(start = GraphStyle.SPACE_MD),
            )
            Text(
                text = formatDate(graph.timeSec(row)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                maxLines = GraphStyle.MAX_TEXT_LINES,
                modifier = Modifier.width(GraphStyle.DATE_COLUMN_WIDTH),
            )
        }
    }
}

@Composable
private fun LaneCanvas(
    graph: GraphSnapshot,
    row: Int,
    laneWidth: androidx.compose.ui.unit.Dp,
    laneOffset: Int,
    isHead: Boolean,
) {
    val colors = LocalGraphColors.current
    val density = LocalDensity.current
    val path = remember { Path() }
    val stroke = remember(density) {
        Stroke(width = with(density) { GraphStyle.STROKE_WIDTH.toPx() })
    }
    val haloStroke = remember(density) {
        Stroke(width = with(density) { GraphStyle.STROKE_WIDTH.toPx() })
    }
    val nodeBorderStroke = remember(density) {
        Stroke(width = with(density) { GraphStyle.STROKE_WIDTH.toPx() })
    }
    val segments = graph.segments(row)
    val nodeLane = graph.nodeLane(row)
    val nodeColor = colors.lanePalette[Math.floorMod(graph.nodeColor(row), colors.lanePalette.size)]
    val merge = graph.parentCount(row) > 1

    Canvas(
        modifier = Modifier
            .width(laneWidth)
            .fillMaxHeight(),
    ) {
        val lane = GraphStyle.LANE_WIDTH.toPx()
        val halfHeight = size.height / GraphStyle.CENTER_FACTOR
        val arrow = GraphStyle.ARROW_SIZE.toPx()
        fun x(laneIndex: Int): Float = (laneIndex - laneOffset) * lane + lane / GraphStyle.CENTER_FACTOR

        for (index in 0 until segments.size) {
            val segment = segments[index]
            val color = colors.lanePalette[Math.floorMod(segment.color, colors.lanePalette.size)]
            val fromX = x(segment.from)
            val toX = x(segment.to)
            when (segment.kind) {
                SegKind.PASS -> drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(fromX, 0f),
                    end = androidx.compose.ui.geometry.Offset(toX, size.height),
                    strokeWidth = GraphStyle.STROKE_WIDTH.toPx(),
                )
                SegKind.IN -> {
                    path.reset()
                    path.moveTo(fromX, 0f)
                    if (fromX == toX) {
                        path.lineTo(toX, halfHeight)
                    } else {
                        path.cubicTo(fromX, halfHeight * GraphStyle.CURVE_CONTROL_FRACTION, toX, halfHeight * GraphStyle.CURVE_CONTROL_FRACTION, toX, halfHeight)
                    }
                    drawPath(path, color, style = stroke)
                }
                SegKind.OUT -> {
                    path.reset()
                    path.moveTo(fromX, halfHeight)
                    if (fromX == toX) {
                        path.lineTo(toX, size.height)
                    } else {
                        val curveY = halfHeight + (size.height - halfHeight) * GraphStyle.CURVE_CONTROL_FRACTION
                        path.cubicTo(fromX, curveY, toX, curveY, toX, size.height)
                    }
                    drawPath(path, color, style = stroke)
                    if (segment.merge) {
                        val tipY = if (GraphStyle.ARROW_TOWARD_PARENT) size.height else halfHeight
                        val baseY = if (GraphStyle.ARROW_TOWARD_PARENT) tipY - arrow else tipY + arrow
                        drawLine(color, androidx.compose.ui.geometry.Offset(toX, tipY), androidx.compose.ui.geometry.Offset(toX - arrow, baseY), GraphStyle.STROKE_WIDTH.toPx())
                        drawLine(color, androidx.compose.ui.geometry.Offset(toX, tipY), androidx.compose.ui.geometry.Offset(toX + arrow, baseY), GraphStyle.STROKE_WIDTH.toPx())
                    }
                }
            }
        }

        val nodeX = x(nodeLane)
        val nodeY = halfHeight
        val radius = GraphStyle.NODE_RADIUS.toPx()
        if (isHead) {
            drawCircle(
                color = colors.headHalo,
                radius = radius * GraphStyle.HEAD_HALO_RADIUS_FACTOR,
                center = androidx.compose.ui.geometry.Offset(nodeX, nodeY),
                style = haloStroke,
            )
        }
        drawCircle(color = nodeColor, radius = radius, center = androidx.compose.ui.geometry.Offset(nodeX, nodeY))
        if (merge) {
            drawCircle(
                color = colors.nodeBorder,
                radius = radius * GraphStyle.MERGE_RING_RADIUS_FACTOR,
                center = androidx.compose.ui.geometry.Offset(nodeX, nodeY),
                style = nodeBorderStroke,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefChips(refs: List<GitRef>, isHead: Boolean) {
    val colors = LocalGraphColors.current
    val allChips = buildList {
        if (isHead) add(ChipLabel("HEAD", RefKind.LOCAL_BRANCH, head = true))
        refs.forEach { add(ChipLabel(it.shortName, it.kind, head = false)) }
    }
    if (allChips.isEmpty()) return
    val visible = allChips.take(GraphStyle.CHIP_MAX)
    val hidden = allChips.drop(GraphStyle.CHIP_MAX)
    Row(
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { chip ->
            val (background, foreground) = chipAppearance(chip.kind, chip.head, colors)
            Chip(text = chip.text, background = background, foreground = foreground)
        }
        if (hidden.isNotEmpty()) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(colors.surfaceRaised, RoundedCornerShape(GraphStyle.CHIP_CORNER_RADIUS))
                            .padding(GraphStyle.SPACE_SM),
                    ) {
                        Text(
                            text = hidden.joinToString("\n") { it.text },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurface,
                        )
                    }
                },
                delayMillis = GraphStyle.TOOLTIP_DELAY_MILLIS,
            ) {
                Chip(
                    text = "+${hidden.size}",
                    background = colors.surfaceRaised,
                    foreground = colors.onSurfaceMuted,
                )
            }
        }
    }
}

private data class ChipLabel(val text: String, val kind: RefKind, val head: Boolean)

@Composable
private fun Chip(text: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(GraphStyle.CHIP_CORNER_RADIUS))
            .padding(horizontal = GraphStyle.CHIP_PADDING_H, vertical = GraphStyle.CHIP_PADDING_V),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            maxLines = GraphStyle.MAX_TEXT_LINES,
        )
    }
}

@Composable
private fun GraphPlaceholder(
    state: RepoUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGraphColors.current
    val load = state.load
    Box(modifier, contentAlignment = Alignment.Center) {
        when (load) {
            is LoadState.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
            ) {
                Text(
                    text = load.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.testTag(UiTestTags.GRAPH_ERROR),
                )
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
            is LoadState.Loading -> CircularProgressIndicator(color = colors.primary)
            LoadState.Idle -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
            ) {
                CircularProgressIndicator(color = colors.primary)
                Text(
                    text = "Loading repository…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
internal fun GraphEmptyState(message: String, modifier: Modifier = Modifier) {
    val colors = LocalGraphColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
            modifier = Modifier.testTag(UiTestTags.GRAPH_EMPTY),
        )
    }
}
