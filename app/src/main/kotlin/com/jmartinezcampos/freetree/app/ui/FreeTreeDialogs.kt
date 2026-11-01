package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jmartinezcampos.freetree.app.APP_BUILD_VERSION
import com.jmartinezcampos.freetree.app.APP_COPYRIGHT
import com.jmartinezcampos.freetree.app.APP_NAME
import com.jmartinezcampos.freetree.app.APP_VERSION
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

    data object About : AppDialog {
        override val tag = UiTestTags.ABOUT_DIALOG
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
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
                .shadow(18.dp, shape)
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
                .padding(GraphStyle.DIALOG_PADDING)
                .testTag(dialog.tag),
            verticalArrangement = Arrangement.spacedBy(GraphStyle.DIALOG_CONTENT_GAP),
        ) {
            when (dialog) {
                is AppDialog.Info -> InfoContent(dialog, controller)
                is AppDialog.Prompt -> PromptContent(dialog, controller)
                is AppDialog.Confirm -> ConfirmContent(dialog, controller)
                AppDialog.About -> AboutContent(controller)
            }
        }
    }
}

@Composable
private fun InfoContent(dialog: AppDialog.Info, controller: AppDialogController) {
    DialogHeader(title = dialog.title, onDismiss = controller::dismiss)
    Text(
        text = dialog.message,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalGraphColors.current.onSurface,
    )
    DialogActions {
        DialogButton(
            label = dialog.confirmLabel,
            onClick = {
                controller.dismiss()
            },
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
    val colors = LocalGraphColors.current
    DialogHeader(title = dialog.title, onDismiss = controller::dismiss)
    Text(
        text = dialog.message,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurface,
    )
    dialog.warning?.let { warning ->
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
    val fieldFocus = remember(dialog) { FocusRequester() }

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
    Text(
        text = dialog.description,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceMuted,
    )
    Text(
        text = dialog.fieldLabel,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
    )
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            error = null
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fieldFocus)
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS))
                    .background(colors.surfaceRaised)
                    .border(
                        GraphStyle.DIVIDER_THICKNESS,
                        if (error != null) colors.error else colors.divider,
                        RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS),
                    )
                    .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_SM),
            ) {
                inner()
            }
        },
    )
    error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
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
private fun AboutContent(controller: AppDialogController) {
    val colors = LocalGraphColors.current
    val icon = remember {
        object {}.javaClass.getResourceAsStream("/FreeTreeIcon.png")?.use(::loadImageBitmap)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        DialogHeader(title = "About $APP_NAME", onDismiss = controller::dismiss, centered = true)
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = "$APP_NAME icon",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(GraphStyle.ABOUT_ICON_SIZE),
            )
        }
        Text(
            text = "Version $APP_VERSION ($APP_BUILD_VERSION)",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        Text(
            text = "A desktop Git graph client.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
        Text(
            text = APP_COPYRIGHT,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
        Text(
            text = "GPL-2.0-or-later",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
        DialogActions {
            DialogButton(
                label = "Close",
                onClick = controller::dismiss,
                kind = DialogButtonKind.Primary,
            )
        }
    }
}

@Composable
private fun DialogHeader(
    title: String,
    onDismiss: () -> Unit,
    centered: Boolean = false,
) {
    val colors = LocalGraphColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = if (centered) Modifier else Modifier.weight(GraphStyle.FILL_WEIGHT),
        )
        if (!centered) {
            Box(
                modifier = Modifier
                    .size(GraphStyle.COMPACT_ACTION_SIZE)
                    .clip(RoundedCornerShape(GraphStyle.COMPACT_ACTION_CORNER_RADIUS))
                    .clickable(onClick = onDismiss),
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
}

@Composable
private fun DialogWarning(text: String) {
    val colors = LocalGraphColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS))
            .background(colors.errorContainer)
            .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_MD),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onErrorContainer,
        )
    }
}

@Composable
private fun DialogActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(GraphStyle.SPACE_XS))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM, Alignment.End),
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
    val background = when (kind) {
        DialogButtonKind.Primary -> colors.primary
        DialogButtonKind.Secondary -> colors.surfaceRaised
        DialogButtonKind.Destructive -> colors.errorContainer
    }
    val foreground = when (kind) {
        DialogButtonKind.Primary -> colors.onPrimary
        DialogButtonKind.Secondary -> colors.onSurface
        DialogButtonKind.Destructive -> colors.onErrorContainer
    }
    Box(
        modifier = Modifier
            .height(GraphStyle.DIALOG_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(GraphStyle.TOOLBAR_BUTTON_CORNER_RADIUS))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.SPACE_XL)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
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

