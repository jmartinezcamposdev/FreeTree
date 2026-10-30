package com.jmartinezcampos.freetree.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jmartinezcampos.freetree.app.menu.AboutDialog
import com.jmartinezcampos.freetree.app.menu.AppCommand
import com.jmartinezcampos.freetree.app.menu.AppMenuBar
import com.jmartinezcampos.freetree.app.menu.DiscardUnstagedDialog
import com.jmartinezcampos.freetree.app.menu.RecentRepositories
import com.jmartinezcampos.freetree.app.menu.stagedPaths
import com.jmartinezcampos.freetree.app.menu.unstagedPaths
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.SessionManager
import java.awt.Desktop
import java.awt.Frame
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.flow.MutableStateFlow

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "FreeTree")
    System.setProperty("com.apple.mrj.application.apple.menu.about.name", "FreeTree")
    val aboutRequests = MutableStateFlow(false)
    installMacAboutHandler { aboutRequests.value = true }
    application {
        val startupRepository = startupRepository(args)
        val manager = remember {
            SessionManager(restore = startupRepository == null)
        }
        val recentRepositories = remember { RecentRepositories() }
        val activeSession by manager.activeSession.collectAsState()
        val aboutRequested by aboutRequests.collectAsState()
        val home = remember { Path.of(System.getProperty("user.home")) }
        val openRepository: (Path) -> Unit = remember(manager, recentRepositories) {
            { path ->
                manager.openRepo(path)
                recentRepositories.record(path)
            }
        }
        var showAbout by remember { mutableStateOf(false) }
        var discardRequest by remember { mutableStateOf<DiscardRequest?>(null) }

        LaunchedEffect(aboutRequested) {
            if (aboutRequested) {
                showAbout = true
                aboutRequests.value = false
            }
        }

        fun activateRelativeTab(delta: Int) {
            val tabs = manager.tabs.value
            if (tabs.isEmpty()) return
            val current = tabs.indexOfFirst { it.id == manager.activeId.value }
            val next = if (current < 0) 0 else (current + delta).mod(tabs.size)
            manager.activate(tabs[next].id)
        }

        LaunchedEffect(startupRepository) {
            startupRepository?.let(openRepository)
        }
        DisposableEffect(Unit) { onDispose { manager.close() } }

        Window(
            onCloseRequest = ::exitApplication,
            title = windowTitle(activeSession?.path, home),
            state = rememberWindowState(
                width = GraphStyle.WINDOW_WIDTH,
                height = GraphStyle.WINDOW_HEIGHT,
            ),
        ) {
            fun handleCommand(command: AppCommand) {
                val session = manager.activeSession.value
                when (command) {
                    AppCommand.OpenRepository -> chooseRepository(openRepository)
                    is AppCommand.OpenRecent -> openRepository(command.path)
                    AppCommand.CloseTab -> manager.activeId.value?.let(manager::closeTab)
                    AppCommand.Exit -> exitApplication()
                    AppCommand.Reload -> session?.refresh()
                    AppCommand.Pull -> session?.pull()
                    AppCommand.Push -> session?.push()
                    AppCommand.StageAll -> session?.let { active ->
                        val paths = active.state.value.status.unstagedPaths()
                        if (paths.isNotEmpty()) active.stage(paths)
                    }
                    AppCommand.UnstageAll -> session?.let { active ->
                        val paths = active.state.value.status.stagedPaths()
                        if (paths.isNotEmpty()) active.unstage(paths)
                    }
                    AppCommand.DiscardUnstaged -> session?.let { active ->
                        val paths = active.state.value.status.unstagedPaths()
                        if (paths.isNotEmpty()) discardRequest = DiscardRequest(active, paths)
                    }
                    AppCommand.ShowRemotes -> session?.let { active ->
                        active.setShowRemotes(!active.state.value.showRemotes)
                    }
                    AppCommand.ShowTags -> session?.let { active ->
                        active.setShowTags(!active.state.value.showTags)
                    }
                    AppCommand.NextTab -> activateRelativeTab(1)
                    AppCommand.PreviousTab -> activateRelativeTab(-1)
                    AppCommand.Minimize -> window.extendedState =
                        window.extendedState or Frame.ICONIFIED
                    AppCommand.About -> showAbout = true
                }
            }

            AppMenuBar(
                sessionManager = manager,
                recentRepositories = recentRepositories,
                onCommand = ::handleCommand,
            )
            App(
                sessionManager = manager,
                onOpenRepository = { chooseRepository(openRepository) },
            )
            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }
            discardRequest?.let { request ->
                DiscardUnstagedDialog(
                    fileCount = request.paths.size,
                    onConfirm = {
                        request.session.discardUnstaged(request.paths)
                        discardRequest = null
                    },
                    onDismiss = { discardRequest = null },
                )
            }
        }
    }
}

private data class DiscardRequest(
    val session: RepoSession,
    val paths: List<String>,
)

private fun installMacAboutHandler(onAbout: () -> Unit) {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
    try {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler { onAbout() }
            }
        }
    } catch (e: Exception) {
        System.err.println("FreeTree: cannot install the macOS About handler: ${e.message}")
    }
}

private fun startupRepository(args: Array<String>): Path? {
    val value = args.firstOrNull { it.isNotBlank() } ?: return null
    return try {
        Path.of(value).toAbsolutePath().normalize()
    } catch (e: InvalidPathException) {
        System.err.println("FreeTree: invalid repository path '$value': ${e.message}")
        null
    }
}

private fun chooseRepository(onOpen: (Path) -> Unit) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open Git repository"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.let(onOpen)
    }
}
