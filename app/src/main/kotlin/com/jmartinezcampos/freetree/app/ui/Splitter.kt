package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import java.awt.Cursor

@Composable
internal fun VerticalSplitter(
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit = {},
    modifier: Modifier = Modifier,
    testTag: String = UiTestTags.SPLITTER,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(GraphStyle.SPLITTER_HIT_WIDTH)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                state = rememberDraggableState { delta -> onDrag(delta) },
                orientation = Orientation.Horizontal,
                interactionSource = interactionSource,
                onDragStopped = { onDragStopped() },
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(GraphStyle.DIVIDER_THICKNESS)
                .background(if (hovered) colors.primary else colors.divider),
        )
    }
}

@Composable
internal fun HorizontalSplitter(
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit = {},
    modifier: Modifier = Modifier,
    testTag: String = UiTestTags.SPLITTER,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GraphStyle.SPLITTER_HIT_HEIGHT)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                state = rememberDraggableState { delta -> onDrag(delta) },
                orientation = Orientation.Vertical,
                interactionSource = interactionSource,
                onDragStopped = { onDragStopped() },
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(GraphStyle.DIVIDER_THICKNESS)
                .background(if (hovered) colors.primary else colors.divider),
        )
    }
}

internal fun resizePaneFraction(
    current: Float,
    deltaPx: Float,
    contentWidthPx: Float,
    minLeftPx: Float,
    minRightPx: Float,
): Float {
    if (contentWidthPx <= 0f) return current
    val minFraction = (minLeftPx / contentWidthPx).coerceIn(0f, 1f)
    val maxFraction = (1f - minRightPx / contentWidthPx).coerceIn(0f, 1f)
    if (minFraction > maxFraction) return 0.5f
    return (current + deltaPx / contentWidthPx).coerceIn(minFraction, maxFraction)
}
