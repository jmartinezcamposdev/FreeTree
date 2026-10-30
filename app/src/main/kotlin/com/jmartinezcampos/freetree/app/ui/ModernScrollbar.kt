package com.jmartinezcampos.freetree.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

/**
 * Shared modern vertical scrollbar.
 *
 * The visual language is a flat rounded track with a brighter rounded thumb. Both are drawn inside
 * a reserved gutter ([GraphStyle.SCROLLBAR_GUTTER]) so content never sits underneath the bar.
 *
 * [alwaysVisible] selects between a permanent scrollbar (history graph) and a transient one that
 * shows up while scrolling or dragging and fades out after [GraphStyle.SCROLLBAR_HIDE_DELAY_MILLIS].
 */
@Composable
internal fun ModernScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    alwaysVisible: Boolean = true,
    testTag: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val adapter = rememberScrollbarAdapter(scrollState)
    val visible = rememberScrollbarVisible(
        alwaysVisible = alwaysVisible,
        scrollInProgress = { scrollState.isScrollInProgress },
        scrollPosition = { scrollState.value.toLong() },
        interactionSource = interactionSource,
    )
    ModernScrollbar(
        adapter = adapter,
        visible = visible,
        interactionSource = interactionSource,
        testTag = testTag,
        modifier = modifier,
    )
}

@Composable
internal fun ModernScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    alwaysVisible: Boolean = true,
    testTag: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val adapter = rememberScrollbarAdapter(listState)
    val visible = rememberScrollbarVisible(
        alwaysVisible = alwaysVisible,
        scrollInProgress = { listState.isScrollInProgress },
        scrollPosition = {
            listState.firstVisibleItemIndex.toLong() * POSITION_SCALE +
                listState.firstVisibleItemScrollOffset
        },
        interactionSource = interactionSource,
    )
    ModernScrollbar(
        adapter = adapter,
        visible = visible,
        interactionSource = interactionSource,
        testTag = testTag,
        modifier = modifier,
    )
}

@Composable
private fun ModernScrollbar(
    adapter: ScrollbarAdapter,
    visible: Boolean,
    interactionSource: MutableInteractionSource,
    testTag: String?,
    modifier: Modifier,
) {
    val colors = LocalGraphColors.current
    val trackShape = RoundedCornerShape(GraphStyle.SCROLLBAR_TRACK_RADIUS)
    val style = ScrollbarStyle(
        minimalHeight = GraphStyle.SCROLLBAR_MIN_THUMB_HEIGHT,
        thickness = GraphStyle.SCROLLBAR_THUMB_WIDTH,
        shape = RoundedCornerShape(GraphStyle.SCROLLBAR_THUMB_RADIUS),
        hoverDurationMillis = GraphStyle.SCROLLBAR_FADE_MILLIS,
        unhoverColor = colors.scrollbarThumb,
        hoverColor = colors.scrollbarThumbHover,
    )
    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(GraphStyle.SCROLLBAR_GUTTER)
            .then(tagModifier),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(GraphStyle.SCROLLBAR_FADE_MILLIS)),
            exit = fadeOut(tween(GraphStyle.SCROLLBAR_FADE_MILLIS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .width(GraphStyle.SCROLLBAR_TRACK_WIDTH)
                        .padding(vertical = GraphStyle.SCROLLBAR_TRACK_MARGIN)
                        .background(colors.scrollbarTrack, trackShape),
                )
                VerticalScrollbar(
                    adapter = adapter,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .padding(vertical = GraphStyle.SCROLLBAR_TRACK_MARGIN),
                    style = style,
                    interactionSource = interactionSource,
                )
            }
        }
    }
}

@Composable
private fun rememberScrollbarVisible(
    alwaysVisible: Boolean,
    scrollInProgress: () -> Boolean,
    scrollPosition: () -> Long,
    interactionSource: MutableInteractionSource,
): Boolean {
    if (alwaysVisible) return true

    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragging = true
                    activity++
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    activity++
                }
            }
        }
    }

    // Wheel scrolling does not always toggle isScrollInProgress on every platform, so the
    // position itself is also treated as activity.
    LaunchedEffect(Unit) {
        snapshotFlow { scrollPosition() }
            .drop(1)
            .collect { activity++ }
    }

    val scrolling by produceState(false) {
        snapshotFlow { scrollInProgress() }.collect { value = it }
    }

    LaunchedEffect(activity, dragging, scrolling) {
        if (!scrolling && !dragging && activity == 0) return@LaunchedEffect
        visible = true
        if (!scrolling && !dragging) {
            delay(GraphStyle.SCROLLBAR_HIDE_DELAY_MILLIS)
            visible = false
        }
    }

    return visible
}

private const val POSITION_SCALE = 100_000L
