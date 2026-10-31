package com.jmartinezcampos.freetree.app.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.jmartinezcampos.freetree.app.APP_BUILD_VERSION
import com.jmartinezcampos.freetree.app.APP_COPYRIGHT
import com.jmartinezcampos.freetree.app.APP_NAME
import com.jmartinezcampos.freetree.app.APP_VERSION
import com.jmartinezcampos.freetree.app.ui.AppTheme
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.app.ui.ThemeMode

@Composable
internal fun AboutDialog(
    themeMode: ThemeMode,
    onDismiss: () -> Unit,
) {
    val icon = remember {
        object {}.javaClass.getResourceAsStream("/FreeTreeIcon.png")?.use(::loadImageBitmap)
    }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "About $APP_NAME",
        state = rememberDialogState(
            width = GraphStyle.ABOUT_DIALOG_WIDTH,
            height = GraphStyle.ABOUT_DIALOG_HEIGHT,
        ),
        resizable = false,
    ) {
        AppTheme(themeMode) {
            val colors = LocalGraphColors.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .padding(GraphStyle.SPACE_LG),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = "$APP_NAME icon",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(
                                top = GraphStyle.ABOUT_CONTENT_MARGIN,
                                bottom = GraphStyle.ABOUT_CONTENT_MARGIN,
                            )
                            .size(GraphStyle.ABOUT_ICON_SIZE),
                    )
                }
                Text(
                    APP_NAME,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = GraphStyle.ABOUT_TITLE_SIZE,
                        lineHeight = GraphStyle.ABOUT_TITLE_LINE_HEIGHT,
                    ),
                    color = colors.onSurface,
                )
                Text(
                    "Version $APP_VERSION ($APP_BUILD_VERSION)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                Text(
                    APP_COPYRIGHT,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                )
                Text(
                    "A desktop Git graph client.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                )
                Text(
                    "GPL-2.0-or-later",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = GraphStyle.ABOUT_CONTENT_MARGIN),
                )
            }
        }
    }
}

@Composable
internal fun DiscardUnstagedDialog(
    themeMode: ThemeMode,
    fileCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Discard unstaged changes",
        state = rememberDialogState(
            width = GraphStyle.CONFIRM_DIALOG_WIDTH,
            height = GraphStyle.CONFIRM_DIALOG_HEIGHT,
        ),
        resizable = false,
    ) {
        AppTheme(themeMode) {
            val colors = LocalGraphColors.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .padding(GraphStyle.SPACE_LG),
                verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
            ) {
                Text(
                    "Discard unstaged changes in $fileCount file(s)?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                Text(
                    "Tracked files will be restored from the index. Untracked files will be deleted " +
                        "from disk. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = GraphStyle.SPACE_MD),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = onConfirm) {
                        Text("Discard", color = colors.error)
                    }
                }
            }
        }
    }
}
