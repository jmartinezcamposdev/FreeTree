package com.jmartinezcampos.freetree.app.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuComposable
import androidx.compose.ui.window.MenuScope
import com.jmartinezcampos.freetree.app.ui.isStatusBlank
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.SessionManager
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun FrameWindowScope.AppMenuBar(
    sessionManager: SessionManager,
    recentRepositories: RecentRepositories,
    onCommand: (AppCommand) -> Unit,
) {
    val tabs by sessionManager.tabs.collectAsState()
    val activeSession by sessionManager.activeSession.collectAsState()
    val emptyState = remember { MutableStateFlow(RepoUiState()) }
    val state by (activeSession?.state ?: emptyState).collectAsState()
    val recent by recentRepositories.paths.collectAsState()
    val home = remember { Path.of(System.getProperty("user.home")) }
    val menu = buildAppMenu(
        platform = currentMenuPlatform(),
        state = AppMenuState(
            hasActiveSession = activeSession != null,
            tabCount = tabs.size,
            busy = state.busy != null,
            showRemotes = state.showRemotes,
            showTags = state.showTags,
            stagedCount = state.status.stagedPaths().size,
            unstagedCount = state.status.unstagedPaths().size,
            recentRepositories = recent,
        ),
        home = home,
    )

    MenuBar {
        menu.forEach { node -> RenderMenu(node, onCommand) }
    }
}

@Composable
@MenuComposable
private fun MenuBarScope.RenderMenu(node: MenuNode, onCommand: (AppCommand) -> Unit) {
    if (node is MenuNode.Submenu) {
        Menu(node.label, enabled = node.enabled) {
            node.children.forEach { child -> RenderChild(child, onCommand) }
        }
    }
}

@Composable
@MenuComposable
private fun MenuScope.RenderChild(node: MenuNode, onCommand: (AppCommand) -> Unit) {
    when (node) {
        is MenuNode.Submenu -> Menu(node.label, enabled = node.enabled) {
            node.children.forEach { child -> RenderChild(child, onCommand) }
        }
        is MenuNode.Item -> RenderItem(node.model, onCommand)
        MenuNode.Separator -> Separator()
    }
}

@Composable
@MenuComposable
private fun MenuScope.RenderItem(model: MenuItemModel, onCommand: (AppCommand) -> Unit) {
    val command = model.command
    if (model.checked != null) {
        CheckboxItem(
            text = model.label,
            checked = model.checked,
            enabled = model.enabled,
            shortcut = model.shortcut,
            onCheckedChange = { command?.let(onCommand) },
        )
    } else {
        Item(
            text = model.label,
            enabled = model.enabled,
            shortcut = model.shortcut,
            onClick = { command?.let(onCommand) },
        )
    }
}

internal fun WorkingStatus?.stagedPaths(): List<String> =
    this?.entries.orEmpty()
        .filter { it.kind != StatusKind.UNMERGED && it.index != '?' && !isStatusBlank(it.index) }
        .map { it.path }

internal fun WorkingStatus?.unstagedPaths(): List<String> =
    this?.entries.orEmpty()
        .filter {
            it.kind != StatusKind.UNMERGED &&
                (it.kind == StatusKind.UNTRACKED || !isStatusBlank(it.worktree))
        }
        .map { it.path }
