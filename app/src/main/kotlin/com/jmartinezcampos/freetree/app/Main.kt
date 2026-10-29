package com.jmartinezcampos.freetree.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import javax.swing.JFileChooser

fun main() = application {
    val manager = remember {
        SessionManager(
            backendFactory = { path -> DemoGitBackend(path) },
            watcherFactory = { DemoWatcher() },
            persistence = InMemorySessionPersistence(),
            restore = false,
        )
    }

    LaunchedEffect(Unit) { manager.openRepo(DemoRepository.path) }
    DisposableEffect(Unit) { onDispose { manager.close() } }

    Window(
        onCloseRequest = ::exitApplication,
        title = "FreeTree",
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

private fun chooseRepository(manager: SessionManager) {
    val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.let(manager::openRepo)
    }
}
