package com.jmartinezcampos.freetree.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.core.session.SessionManager
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JFileChooser

fun main(args: Array<String>) = application {
    val startupRepository = startupRepository(args)
    val manager = remember {
        SessionManager(restore = startupRepository == null)
    }
    val activeSession by manager.activeSession.collectAsState()
    val home = remember { Path.of(System.getProperty("user.home")) }

    LaunchedEffect(startupRepository) {
        startupRepository?.let(manager::openRepo)
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
        App(
            sessionManager = manager,
            onOpenRepository = { chooseRepository(manager) },
        )
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

private fun chooseRepository(manager: SessionManager) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open Git repository"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.let(manager::openRepo)
    }
}
