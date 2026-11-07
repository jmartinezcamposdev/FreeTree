package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

internal class ContextMenuSeparator : ContextMenuItem("", false, {})

/**
 * Compact, theme-aware context menu used by the history list. Compose's default representation is
 * light-only, so FreeTree supplies its own surface and text colors here.
 */
internal object FreeTreeContextMenuRepresentation : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val entries = items()
        if (entries.isEmpty()) {
            SideEffect { state.status = ContextMenuState.Status.Closed }
            return
        }

        val colors = LocalGraphColors.current
        val shape = RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS)
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(colors.surfaceRaised)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 160.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = GraphStyle.SPACE_XS)
                    .testTag(UiTestTags.CONTEXT_MENU),
            ) {
                entries.forEach { item ->
                    if (item is ContextMenuSeparator) {
                        Box(
                            Modifier
                                .padding(
                                    horizontal = GraphStyle.SPACE_SM,
                                    vertical = GraphStyle.SPACE_XS,
                                )
                                .fillMaxWidth()
                                .height(GraphStyle.DIVIDER_THICKNESS)
                                .background(colors.divider),
                        )
                    } else {
                        ContextMenuEntry(
                            item = item,
                            onClick = {
                                state.status = ContextMenuState.Status.Closed
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuEntry(item: ContextMenuItem, onClick: () -> Unit) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val contentColor = if (item.enabled) colors.onSurface else colors.onSurfaceMuted
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.CONTEXT_MENU_ITEM_HEIGHT)
            .background(if (hovered && item.enabled) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                enabled = item.enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = GraphStyle.SPACE_LG)
            .testTag(UiTestTags.contextMenuItem(item.label)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
