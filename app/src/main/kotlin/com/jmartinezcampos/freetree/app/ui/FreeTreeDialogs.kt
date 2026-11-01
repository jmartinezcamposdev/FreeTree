package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppDialog {
    val tag: String

    data class Info(
        override val tag: String,
        val title: String,
        val message: String,
        val confirmLabel: String = "OK",
    ) : AppDialog

    data class Prompt(
        override val tag: String,
        val title: String,
        val description: String,
        val fieldLabel: String,
        val confirmLabel: String,
        val validate: (String) -> String?,
        val onConfirm: (String) -> Unit,
    ) : AppDialog

    data class Confirm(
        override val tag: String,
        val title: String,
        val message: String,
        val warning: String? = null,
        val confirmLabel: String,
        val destructive: Boolean = false,
        val onConfirm: () -> Unit,
    ) : AppDialog

    data class BranchPicker(
        val entries: List<BranchPickerEntry>,
        val busy: Boolean,
        val onSelect: (BranchPickerEntry) -> Unit,
    ) : AppDialog {
        override val tag: String = UiTestTags.BRANCH_PICKER_DIALOG
    }
}

internal class AppDialogController {
    private val _dialog = MutableStateFlow<AppDialog?>(null)
    val dialog: StateFlow<AppDialog?> = _dialog.asStateFlow()

    fun show(dialog: AppDialog) {
        _dialog.value = dialog
    }

    fun dismiss() {
        _dialog.value = null
    }
}

@Composable
internal fun FreeTreeDialogHost(
    dialog: AppDialog?,
    controller: AppDialogController,
) {
    if (dialog == null) return
    val colors = LocalGraphColors.current
    val focusRequester = remember(dialog) { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CORNER_RADIUS)

    LaunchedEffect(dialog) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f))
            .testTag(UiTestTags.DIALOG_SCRIM)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = controller::dismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val isBranchPicker = dialog is AppDialog.BranchPicker
        val dialogWidth = if (isBranchPicker) {
            minOf(
                GraphStyle.BRANCH_PICKER_WIDTH,
                maxWidth * GraphStyle.BRANCH_PICKER_WIDTH_FRACTION,
            )
        } else {
            GraphStyle.DIALOG_WIDTH
        }
        val dialogHeight = if (isBranchPicker) {
            minOf(
                GraphStyle.BRANCH_PICKER_MAX_HEIGHT,
                maxHeight * GraphStyle.BRANCH_PICKER_HEIGHT_FRACTION,
            )
        } else {
            null
        }
        Column(
            modifier = Modifier
                .width(dialogWidth)
                .then(if (dialogHeight != null) Modifier.height(dialogHeight) else Modifier)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(colors.surface)
                .border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        controller.dismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(
                    horizontal = GraphStyle.DIALOG_HORIZONTAL_PADDING,
                    vertical = GraphStyle.DIALOG_VERTICAL_PADDING,
                )
                .testTag(dialog.tag),
        ) {
            when (dialog) {
                is AppDialog.Info -> InfoContent(dialog, controller)
                is AppDialog.Prompt -> PromptContent(dialog, controller)
                is AppDialog.Confirm -> ConfirmContent(dialog, controller)
                is AppDialog.BranchPicker -> BranchPickerContent(dialog, controller)
            }
        }
    }
}

@Composable
private fun InfoContent(dialog: AppDialog.Info, controller: AppDialogController) {
    DialogHeader(title = dialog.title, onDismiss = controller::dismiss)
    Spacer(Modifier.height(GraphStyle.DIALOG_HEADER_BOTTOM_SPACE))
    DialogMessage(text = dialog.message)
    DialogActions {
        DialogButton(
            label = dialog.confirmLabel,
            onClick = controller::dismiss,
            kind = DialogButtonKind.Primary,
            testTag = if (dialog.tag == UiTestTags.FILTERS_INFO_DIALOG) {
                UiTestTags.FILTERS_INFO_OK
            } else {
                null
            },
        )
    }
}

@Composable
private fun ConfirmContent(dialog: AppDialog.Confirm, controller: AppDialogController) {
    DialogHeader(title = dialog.title, onDismiss = controller::dismiss)
    Spacer(Modifier.height(GraphStyle.DIALOG_HEADER_BOTTOM_SPACE))
    DialogMessage(text = dialog.message)
    dialog.warning?.let { warning ->
        Spacer(Modifier.height(GraphStyle.DIALOG_SECTION_GAP))
        DialogWarning(warning)
    }
    DialogActions {
        DialogButton(
            label = "Cancel",
            onClick = controller::dismiss,
            kind = DialogButtonKind.Secondary,
            testTag = cancelTag(dialog.tag),
        )
        DialogButton(
            label = dialog.confirmLabel,
            onClick = {
                dialog.onConfirm()
                controller.dismiss()
            },
            kind = if (dialog.destructive) DialogButtonKind.Destructive else DialogButtonKind.Primary,
            testTag = confirmTag(dialog.tag),
        )
    }
}

@Composable
private fun ColumnScope.BranchPickerContent(
    dialog: AppDialog.BranchPicker,
    controller: AppDialogController,
) {
    val colors = LocalGraphColors.current
    var query by remember(dialog) { mutableStateOf("") }
    var highlighted by remember(dialog) { mutableStateOf(0) }
    val entries = remember(dialog.entries, query) {
        filterBranchPickerEntries(dialog.entries, query)
    }
    val local = entries.filterNot { it.isRemote }
    val remote = entries.filter { it.isRemote }
    val highlightedEntry = entries.getOrNull(highlighted)
    val searchFocus = remember(dialog) { FocusRequester() }

    fun select(entry: BranchPickerEntry) {
        if (dialog.busy) return
        dialog.onSelect(entry)
        controller.dismiss()
    }

    LaunchedEffect(dialog) {
        searchFocus.requestFocus()
    }
    LaunchedEffect(query) {
        highlighted = 0
    }

    DialogHeader(title = "Switch branch", onDismiss = controller::dismiss)
    Spacer(Modifier.height(GraphStyle.SPACE_SM))
    BasicTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = GraphStyle.DIALOG_BODY_SIZE,
            lineHeight = GraphStyle.DIALOG_BODY_LINE_HEIGHT,
            color = colors.onSurface,
        ),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(searchFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (entries.isNotEmpty()) {
                            highlighted = (highlighted + 1).coerceAtMost(entries.lastIndex)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (entries.isNotEmpty()) {
                            highlighted = (highlighted - 1).coerceAtLeast(0)
                        }
                        true
                    }
                    Key.Enter -> {
                        entries.getOrNull(highlighted)?.let(::select)
                        true
                    }
                    Key.Escape -> {
                        controller.dismiss()
                        true
                    }
                    else -> false
                }
            }
            .testTag(UiTestTags.BRANCH_PICKER_SEARCH),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS))
                    .background(colors.surfaceRaised)
                    .border(
                        GraphStyle.DIVIDER_THICKNESS,
                        colors.divider,
                        RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS),
                    )
                    .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_MD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(GraphStyle.FILL_WEIGHT)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search branches…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceMuted,
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(GraphStyle.COMPACT_ACTION_SIZE)
                            .clip(RoundedCornerShape(GraphStyle.COMPACT_ACTION_CORNER_RADIUS))
                            .clickable { query = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
        },
    )
    Spacer(Modifier.height(GraphStyle.SPACE_MD))

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .weight(GraphStyle.FILL_WEIGHT)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No matching branches",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
                modifier = Modifier.testTag(UiTestTags.BRANCH_PICKER_EMPTY),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val hasOverflow by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                false
            } else {
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                first > 0 || last < info.totalItemsCount - 1
            }
        }
    }

    Box(
        modifier = Modifier
            .weight(GraphStyle.FILL_WEIGHT)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = GraphStyle.SCROLLBAR_GUTTER + GraphStyle.SPACE_MD),
            verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XXS),
        ) {
            if (local.isNotEmpty()) {
                item(key = "local-header") {
                    BranchPickerSectionHeader("Local branches")
                }
                items(local, key = { it.key }) { entry ->
                    BranchPickerRow(
                        entry = entry,
                        highlighted = entry == highlightedEntry,
                        busy = dialog.busy,
                        onClick = { select(entry) },
                    )
                }
            }
            if (remote.isNotEmpty()) {
                item(key = "remote-header") {
                    BranchPickerSectionHeader("Remote branches")
                }
                items(remote, key = { it.key }) { entry ->
                    BranchPickerRow(
                        entry = entry,
                        highlighted = entry == highlightedEntry,
                        busy = dialog.busy,
                        onClick = { select(entry) },
                    )
                }
            }
        }
        if (hasOverflow) {
            ModernScrollbar(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
                alwaysVisible = false,
                testTag = UiTestTags.BRANCH_PICKER_SCROLLBAR,
            )
        }
    }
}

@Composable
private fun BranchPickerSectionHeader(title: String) {
    val colors = LocalGraphColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurfaceMuted,
        modifier = Modifier.padding(
            start = GraphStyle.SPACE_SM,
            top = GraphStyle.SPACE_SM,
            bottom = GraphStyle.SPACE_XS,
        ),
    )
}

@Composable
private fun BranchPickerRow(
    entry: BranchPickerEntry,
    highlighted: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember(entry) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = !busy
    val background = when {
        entry.isCurrent -> colors.selectedRow
        highlighted -> colors.primary.copy(alpha = 0.12f)
        hovered && enabled -> colors.hoverRow
        else -> Color.Transparent
    }
    val foreground = if (entry.isCurrent) colors.primary else colors.onSurface
    val iconColor = when {
        entry.isRemote -> colors.chipRemoteForeground
        entry.isCurrent -> colors.primary
        else -> colors.onSurfaceMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.BRANCH_PICKER_ROW_HEIGHT)
            .clip(RoundedCornerShape(GraphStyle.PREFERENCES_NAV_CORNER_RADIUS))
            .background(background)
            .hoverable(interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { selected = entry.isCurrent }
            .padding(horizontal = GraphStyle.SPACE_XL)
            .testTag(UiTestTags.branchPickerItem(entry.displayName)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
    ) {
        Canvas(modifier = Modifier.size(GraphStyle.BRANCH_PICKER_ICON_SIZE)) {
            val stroke = GraphStyle.TOOLBAR_ICON_STROKE.toPx()
            if (entry.isRemote) {
                drawRemoteBranchIcon(iconColor, stroke)
            } else {
                drawBranchIcon(iconColor, stroke)
            }
        }
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (entry.isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = foreground,
            maxLines = GraphStyle.MAX_TEXT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        if (entry.isCurrent) {
            Text(
                text = "current",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRemoteBranchIcon(
    color: Color,
    stroke: Float,
) {
    val radius = size.minDimension * 0.38f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color, radius, center, style = Stroke(stroke))
    drawOval(
        color = color,
        topLeft = Offset(center.x - radius * 0.45f, center.y - radius),
        size = Size(radius * 0.9f, radius * 2f),
        style = Stroke(stroke),
    )
    drawLine(
        color,
        Offset(center.x - radius, center.y),
        Offset(center.x + radius, center.y),
        stroke,
    )
}

@Composable
private fun PromptContent(dialog: AppDialog.Prompt, controller: AppDialogController) {
    val colors = LocalGraphColors.current
    var value by remember(dialog) { mutableStateOf("") }
    var error by remember(dialog) { mutableStateOf<String?>(null) }
    var focused by remember(dialog) { mutableStateOf(false) }
    val fieldFocus = remember(dialog) { FocusRequester() }
    val fieldShape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)

    fun submit() {
        val problem = dialog.validate(value)
        if (problem != null) {
            error = problem
        } else {
            dialog.onConfirm(value.trim())
            controller.dismiss()
        }
    }

    LaunchedEffect(dialog) { fieldFocus.requestFocus() }

    DialogHeader(title = dialog.title, onDismiss = controller::dismiss)
    Spacer(Modifier.height(GraphStyle.SPACE_SM))
    Text(
        text = dialog.description,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = GraphStyle.DIALOG_BODY_SIZE,
            lineHeight = GraphStyle.DIALOG_BODY_LINE_HEIGHT,
        ),
        color = colors.onSurfaceMuted,
    )
    Spacer(Modifier.height(GraphStyle.SPACE_XL))
    Text(
        text = dialog.fieldLabel,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
    )
    Spacer(Modifier.height(GraphStyle.DIALOG_FIELD_LABEL_GAP))
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            error = null
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = GraphStyle.DIALOG_BODY_SIZE,
            lineHeight = GraphStyle.DIALOG_BODY_LINE_HEIGHT,
            color = colors.onSurface,
        ),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fieldFocus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    submit()
                    true
                } else {
                    false
                }
            }
            .testTag(UiTestTags.COMMIT_NAME_INPUT),
        decorationBox = { inner ->
            val borderColor = when {
                error != null -> colors.error
                focused -> colors.primary
                else -> colors.divider
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GraphStyle.DIALOG_INPUT_HEIGHT)
                    .clip(fieldShape)
                    .background(colors.background)
                    .border(GraphStyle.DIVIDER_THICKNESS, borderColor, fieldShape)
                    .padding(horizontal = GraphStyle.SPACE_LG),
                contentAlignment = Alignment.CenterStart,
            ) {
                inner()
            }
        },
    )
    error?.let {
        Spacer(Modifier.height(GraphStyle.SPACE_SM))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.error,
            modifier = Modifier.testTag(UiTestTags.COMMIT_NAME_ERROR),
        )
    }
    DialogActions {
        DialogButton(
            label = "Cancel",
            onClick = controller::dismiss,
            kind = DialogButtonKind.Secondary,
            testTag = UiTestTags.COMMIT_NAME_CANCEL,
        )
        DialogButton(
            label = dialog.confirmLabel,
            onClick = ::submit,
            kind = DialogButtonKind.Primary,
            testTag = UiTestTags.COMMIT_NAME_CONFIRM,
        )
    }
}

@Composable
private fun DialogHeader(
    title: String,
    onDismiss: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val closeInteraction = remember { MutableInteractionSource() }
    val closeHovered by closeInteraction.collectIsHoveredAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = GraphStyle.DIALOG_TITLE_SIZE,
                lineHeight = GraphStyle.DIALOG_TITLE_LINE_HEIGHT,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.onSurface,
            modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        Box(
            modifier = Modifier
                .size(GraphStyle.DIALOG_CLOSE_SIZE)
                .clip(RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS))
                .background(if (closeHovered) colors.hoverRow else Color.Transparent)
                .hoverable(closeInteraction)
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(GraphStyle.DIALOG_CLOSE_ICON_SIZE)) {
                val stroke = GraphStyle.DIALOG_CLOSE_ICON_STROKE.toPx()
                val inset = stroke / 2f
                val maxX = size.width - inset
                val maxY = size.height - inset
                val iconColor = if (closeHovered) colors.onSurface else colors.onSurfaceMuted
                drawLine(
                    color = iconColor,
                    start = Offset(inset, inset),
                    end = Offset(maxX, maxY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
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
private fun DialogMessage(text: String) {
    val colors = LocalGraphColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = GraphStyle.DIALOG_BODY_SIZE,
            lineHeight = GraphStyle.DIALOG_BODY_LINE_HEIGHT,
        ),
        color = colors.onSurface,
    )
}

@Composable
private fun DialogWarning(text: String) {
    val colors = LocalGraphColors.current
    val barShape = RoundedCornerShape(GraphStyle.DIALOG_WARNING_BAR_WIDTH / 2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_ML),
    ) {
        Box(
            modifier = Modifier
                .width(GraphStyle.DIALOG_WARNING_BAR_WIDTH)
                .fillMaxHeight()
                .clip(barShape)
                .background(colors.destructive),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.error,
            modifier = Modifier.padding(vertical = GraphStyle.SPACE_XXS),
        )
    }
}

@Composable
private fun DialogActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(GraphStyle.DIALOG_ACTIONS_TOP_SPACE))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

private enum class DialogButtonKind { Primary, Secondary, Destructive }

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    kind: DialogButtonKind,
    testTag: String? = null,
) {
    val colors = LocalGraphColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)

    val background = when (kind) {
        DialogButtonKind.Primary -> if (hovered) colors.primary.copy(alpha = 0.90f) else colors.primary
        DialogButtonKind.Secondary -> if (hovered) colors.hoverRow else colors.surface
        DialogButtonKind.Destructive -> if (hovered) colors.destructive.copy(alpha = 0.90f) else colors.destructive
    }
    val foreground = when (kind) {
        DialogButtonKind.Primary -> colors.onPrimary
        DialogButtonKind.Secondary -> colors.onSurface
        DialogButtonKind.Destructive -> colors.onDestructive
    }
    val fontWeight = when (kind) {
        DialogButtonKind.Primary, DialogButtonKind.Destructive -> FontWeight.SemiBold
        DialogButtonKind.Secondary -> FontWeight.Medium
    }

    Box(
        modifier = Modifier
            .height(GraphStyle.DIALOG_BUTTON_HEIGHT)
            .widthIn(min = GraphStyle.DIALOG_BUTTON_MIN_WIDTH)
            .clip(shape)
            .background(background)
            .then(
                if (kind == DialogButtonKind.Secondary) {
                    Modifier.border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape)
                } else {
                    Modifier
                },
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = GraphStyle.SPACE_XL)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = foreground,
        )
    }
}

private fun confirmTag(tag: String): String? = when (tag) {
    UiTestTags.FILTERS_INFO_DIALOG -> null
    UiTestTags.COMMIT_NAME_DIALOG -> UiTestTags.COMMIT_NAME_CONFIRM
    UiTestTags.DELETE_BRANCH_DIALOG -> UiTestTags.DELETE_BRANCH_CONFIRM
    UiTestTags.DISCARD_DIALOG -> UiTestTags.DISCARD_CONFIRM
    UiTestTags.DISCARD_ALL_DIALOG -> UiTestTags.DISCARD_ALL_CONFIRM
    else -> null
}

private fun cancelTag(tag: String): String? = when (tag) {
    UiTestTags.COMMIT_NAME_DIALOG -> UiTestTags.COMMIT_NAME_CANCEL
    UiTestTags.DELETE_BRANCH_DIALOG -> UiTestTags.DELETE_BRANCH_CANCEL
    UiTestTags.DISCARD_DIALOG -> UiTestTags.DISCARD_CANCEL
    UiTestTags.DISCARD_ALL_DIALOG -> UiTestTags.DISCARD_ALL_CANCEL
    else -> null
}
