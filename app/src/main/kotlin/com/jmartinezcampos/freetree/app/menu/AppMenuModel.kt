package com.jmartinezcampos.freetree.app.menu

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import com.jmartinezcampos.freetree.app.shortRepositoryPath
import java.nio.file.Path

internal enum class MenuPlatform { MACOS, LINUX, WINDOWS }

internal fun currentMenuPlatform(): MenuPlatform {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.startsWith("mac") -> MenuPlatform.MACOS
        os.startsWith("windows") -> MenuPlatform.WINDOWS
        else -> MenuPlatform.LINUX
    }
}

internal data class MenuItemModel(
    val command: AppCommand?,
    val label: String,
    val shortcut: KeyShortcut? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
)

internal sealed interface MenuNode {
    data class Item(val model: MenuItemModel) : MenuNode
    data class Submenu(
        val label: String,
        val enabled: Boolean,
        val children: List<MenuNode>,
    ) : MenuNode

    data object Separator : MenuNode
}

internal data class AppMenuState(
    val hasActiveSession: Boolean,
    val tabCount: Int,
    val busy: Boolean,
    val showRemotes: Boolean,
    val showTags: Boolean,
    val stagedCount: Int,
    val unstagedCount: Int,
    val recentRepositories: List<Path>,
)

internal fun buildAppMenu(
    platform: MenuPlatform,
    state: AppMenuState,
    home: Path,
): List<MenuNode> {
    val primary = platform == MenuPlatform.MACOS
    val openShortcut = KeyShortcut(Key.O, ctrl = !primary, meta = primary)
    val closeShortcut = KeyShortcut(Key.W, ctrl = !primary, meta = primary)
    val nextTabShortcut = KeyShortcut(Key.Tab, ctrl = true)
    val previousTabShortcut = KeyShortcut(Key.Tab, ctrl = true, shift = true)
    val canUseSession = state.hasActiveSession && !state.busy
    val fileChildren = buildList {
        add(
            MenuNode.Item(
                MenuItemModel(
                    command = AppCommand.OpenRepository,
                    label = "Open Repository…",
                    shortcut = openShortcut,
                ),
            ),
        )
        add(
            MenuNode.Submenu(
                label = "Open Recent Repository",
                enabled = true,
                children = recentNodes(state.recentRepositories, home),
            ),
        )
        add(MenuNode.Separator)
        add(
            MenuNode.Item(
                MenuItemModel(
                    command = AppCommand.CloseTab,
                    label = "Close Tab",
                    shortcut = closeShortcut,
                    enabled = state.tabCount > 0,
                ),
            ),
        )
        if (platform != MenuPlatform.MACOS) {
            add(MenuNode.Separator)
            add(
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.Exit,
                        label = "Exit",
                    ),
                ),
            )
        }
    }

    return listOf(
        MenuNode.Submenu(
            label = "File",
            enabled = true,
            children = fileChildren,
        ),
        MenuNode.Submenu(
            label = "Repository",
            enabled = true,
            children = listOf(
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.Reload,
                        label = "Reload repository from disk",
                        enabled = canUseSession,
                    ),
                ),
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.Pull,
                        label = "Pull",
                        enabled = canUseSession,
                    ),
                ),
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.Push,
                        label = "Push",
                        enabled = canUseSession,
                    ),
                ),
                MenuNode.Separator,
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.StageAll,
                        label = "Stage All",
                        enabled = canUseSession && state.unstagedCount > 0,
                    ),
                ),
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.UnstageAll,
                        label = "Unstage All",
                        enabled = canUseSession && state.stagedCount > 0,
                    ),
                ),
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.DiscardUnstaged,
                        label = "Discard Unstaged Changes",
                        enabled = canUseSession && state.unstagedCount > 0,
                    ),
                ),
            ),
        ),
        MenuNode.Submenu(
            label = "View",
            enabled = true,
            children = listOf(
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.ShowRemotes,
                        label = "Show Remotes",
                        enabled = state.hasActiveSession,
                        checked = state.showRemotes,
                    ),
                ),
                MenuNode.Item(
                    MenuItemModel(
                        command = AppCommand.ShowTags,
                        label = "Show Tags",
                        enabled = state.hasActiveSession,
                        checked = state.showTags,
                    ),
                ),
            ),
        ),
        MenuNode.Submenu(
            label = "Window",
            enabled = true,
            children = buildList {
                add(
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.NextTab,
                            label = "Next Tab",
                            shortcut = nextTabShortcut,
                            enabled = state.tabCount > 1,
                        ),
                    ),
                )
                add(
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.PreviousTab,
                            label = "Previous Tab",
                            shortcut = previousTabShortcut,
                            enabled = state.tabCount > 1,
                        ),
                    ),
                )
                if (platform != MenuPlatform.MACOS) {
                    add(MenuNode.Separator)
                    add(
                        MenuNode.Item(
                            MenuItemModel(
                                command = AppCommand.Minimize,
                                label = "Minimize",
                            ),
                        ),
                    )
                }
            },
        ),
        MenuNode.Submenu(
            label = "Help",
            enabled = true,
            children = if (platform == MenuPlatform.MACOS) {
                listOf(
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.MessagesLog,
                            label = "Messages Log",
                        ),
                    ),
                )
            } else {
                listOf(
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.About,
                            label = "About FreeTree",
                        ),
                    ),
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.MessagesLog,
                            label = "Messages Log",
                        ),
                    ),
                    MenuNode.Separator,
                    MenuNode.Item(
                        MenuItemModel(
                            command = AppCommand.Preferences,
                            label = "Preferences",
                        ),
                    ),
                )
            },
        ),
    )
}

private fun recentNodes(recent: List<Path>, home: Path): List<MenuNode> =
    if (recent.isEmpty()) {
        listOf(
            MenuNode.Item(
                MenuItemModel(
                    command = null,
                    label = "No Recent Repositories",
                    enabled = false,
                ),
            ),
        )
    } else {
        recent.take(10).map { path ->
            MenuNode.Item(
                MenuItemModel(
                    command = AppCommand.OpenRecent(path),
                    label = shortRepositoryPath(path, home),
                ),
            )
        }
    }
