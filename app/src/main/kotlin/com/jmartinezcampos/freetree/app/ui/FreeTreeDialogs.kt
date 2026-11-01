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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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

    Box(
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
        Column(
            modifier = Modifier
                .width(GraphStyle.DIALOG_WIDTH)
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
