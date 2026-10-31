package com.jmartinezcampos.freetree.app.menu

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMenuModelTest {
    private val home = Path.of(System.getProperty("java.io.tmpdir")).resolve("freetree-home")
    private val state = AppMenuState(
        hasActiveSession = true,
        tabCount = 2,
        busy = false,
        showRemotes = true,
        showTags = false,
        stagedCount = 1,
        unstagedCount = 2,
        recentRepositories = emptyList(),
    )

    private fun List<MenuNode>.menu(label: String): MenuNode.Submenu =
        filterIsInstance<MenuNode.Submenu>().first { it.label == label }

    private fun MenuNode.Submenu.item(label: String): MenuItemModel =
        children.filterIsInstance<MenuNode.Item>().first { it.model.label == label }.model

    private fun MenuNode.Submenu.submenu(label: String): MenuNode.Submenu =
        children.filterIsInstance<MenuNode.Submenu>().first { it.label == label }

    @Test
    fun macFileItemsUseCommandShortcuts() {
        val file = buildAppMenu(MenuPlatform.MACOS, state, home).menu("File")
        val open = file.item("Open Repository…")
        val close = file.item("Close Tab")

        assertEquals(KeyShortcut(Key.O, meta = true), open.shortcut)
        assertEquals(KeyShortcut(Key.W, meta = true), close.shortcut)
        assertTrue(file.children.filterIsInstance<MenuNode.Item>().none { it.model.label == "Quit FreeTree" })
    }

    @Test
    fun linuxFileItemsUseControlShortcuts() {
        val file = buildAppMenu(MenuPlatform.LINUX, state, home).menu("File")

        assertEquals(KeyShortcut(Key.O, ctrl = true), file.item("Open Repository…").shortcut)
        assertEquals(KeyShortcut(Key.W, ctrl = true), file.item("Close Tab").shortcut)
        assertEquals("Exit", file.item("Exit").label)
    }

    @Test
    fun onlyRequestedItemsHaveShortcuts() {
        val menu = buildAppMenu(MenuPlatform.MACOS, state, home)
        val file = menu.menu("File")
        val repository = menu.menu("Repository")
        val window = menu.menu("Window")
        val recent = file.submenu("Open Recent Repository")

        assertNull(recent.children.first().let { (it as MenuNode.Item).model.shortcut })
        assertNull(repository.item("Reload repository from disk").shortcut)
        assertNull(repository.item("Pull").shortcut)
        assertNull(repository.item("Push").shortcut)
        assertNull(repository.item("Stage All").shortcut)
        assertNull(repository.item("Unstage All").shortcut)
        assertNull(repository.item("Discard Unstaged Changes").shortcut)

        assertEquals(KeyShortcut(Key.Tab, ctrl = true), window.item("Next Tab").shortcut)
        assertEquals(
            KeyShortcut(Key.Tab, ctrl = true, shift = true),
            window.item("Previous Tab").shortcut,
        )
    }

    @Test
    fun togglesReflectCurrentState() {
        val menu = buildAppMenu(MenuPlatform.MACOS, state, home)
        val view = menu.menu("View")

        assertEquals(true, view.item("Show Remotes").checked)
        assertEquals(false, view.item("Show Tags").checked)
    }

    @Test
    fun recentMenuIsCappedAtTenAndUsesShortPaths() {
        val recent = (12 downTo 1).map { home.resolve("repo-$it") }
        val file = buildAppMenu(
            MenuPlatform.MACOS,
            state.copy(recentRepositories = recent),
            home,
        ).menu("File")
        val recentMenu = file.submenu("Open Recent Repository")

        assertEquals(10, recentMenu.children.size)
        assertEquals("~${java.io.File.separator}repo-12", (recentMenu.children.first() as MenuNode.Item).model.label)
    }

    @Test
    fun emptyRecentMenuShowsDisabledPlaceholder() {
        val file = buildAppMenu(MenuPlatform.MACOS, state, home).menu("File")
        val recentMenu = file.submenu("Open Recent Repository")
        val placeholder = recentMenu.children.single() as MenuNode.Item

        assertEquals("No Recent Repositories", placeholder.model.label)
        assertFalse(placeholder.model.enabled)
        assertNull(placeholder.model.command)
    }

    @Test
    fun repositoryActionsDisableWhenBusyOrMissingASession() {
        val noSession = buildAppMenu(
            MenuPlatform.MACOS,
            state.copy(hasActiveSession = false),
            home,
        ).menu("Repository")
        assertFalse(noSession.item("Pull").enabled)
        assertFalse(noSession.item("Stage All").enabled)

        val busy = buildAppMenu(
            MenuPlatform.MACOS,
            state.copy(busy = true),
            home,
        ).menu("Repository")
        assertFalse(busy.item("Reload repository from disk").enabled)
        assertFalse(busy.item("Discard Unstaged Changes").enabled)
    }

    @Test
    fun stageAndDiscardActionsRequireChanges() {
        val noChanges = buildAppMenu(
            MenuPlatform.MACOS,
            state.copy(stagedCount = 0, unstagedCount = 0),
            home,
        ).menu("Repository")

        assertFalse(noChanges.item("Stage All").enabled)
        assertFalse(noChanges.item("Unstage All").enabled)
        assertFalse(noChanges.item("Discard Unstaged Changes").enabled)
        assertTrue(buildAppMenu(MenuPlatform.MACOS, state, home).menu("Repository").item("Stage All").enabled)
    }

    @Test
    fun helpContainsMessagesLogEverywhereAndPreferencesOnlyOutsideMacOs() {
        val macHelp = buildAppMenu(MenuPlatform.MACOS, state, home).menu("Help")
        val linuxHelp = buildAppMenu(MenuPlatform.LINUX, state, home).menu("Help")

        assertEquals("Messages Log", macHelp.item("Messages Log").label)
        assertTrue(macHelp.children.filterIsInstance<MenuNode.Item>().none { it.model.label == "About FreeTree" })
        assertTrue(macHelp.children.filterIsInstance<MenuNode.Item>().none { it.model.label == "Preferences" })

        assertEquals("About FreeTree", linuxHelp.item("About FreeTree").label)
        assertEquals("Messages Log", linuxHelp.item("Messages Log").label)
        assertEquals("Preferences", linuxHelp.item("Preferences").label)
    }

    @Test
    fun tabActionsRequireMoreThanOneTab() {
        val window = buildAppMenu(
            MenuPlatform.MACOS,
            state.copy(tabCount = 1),
            home,
        ).menu("Window")

        assertFalse(window.item("Next Tab").enabled)
        assertFalse(window.item("Previous Tab").enabled)
        assertTrue(window.children.filterIsInstance<MenuNode.Item>().none { it.model.label == "Minimize" })
    }

    @Test
    fun minimizeIsOnlyInTheWindowMenuOutsideMacOs() {
        val macWindow = buildAppMenu(MenuPlatform.MACOS, state, home).menu("Window")
        val linuxWindow = buildAppMenu(MenuPlatform.LINUX, state, home).menu("Window")

        assertTrue(macWindow.children.filterIsInstance<MenuNode.Item>().none { it.model.label == "Minimize" })
        assertTrue(linuxWindow.item("Minimize").enabled)
    }
}
