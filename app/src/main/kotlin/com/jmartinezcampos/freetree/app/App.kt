package com.jmartinezcampos.freetree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.jmartinezcampos.freetree.app.ui.ChangesPanel
import com.jmartinezcampos.freetree.app.ui.CommitPanel
import com.jmartinezcampos.freetree.app.ui.FilterBar
import com.jmartinezcampos.freetree.app.ui.GraphList
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.app.ui.TabBar
import com.jmartinezcampos.freetree.app.ui.Toolbar
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun App(
    sessionManager: SessionManager,
    onOpenRepository: (() -> Unit)? = null,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit = { session, target ->
        session.openDiff(target, unlimited = true)
    },
) {
    val dark = isSystemInDarkTheme()
    val colors = GraphStyle.colors(dark)
    CompositionLocalProvider(LocalGraphColors provides colors) {
        MaterialTheme(
            colorScheme = GraphStyle.materialColors(colors, dark),
            typography = GraphStyle.typography,
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppContent(
                    sessionManager = sessionManager,
                    onOpenRepository = onOpenRepository,
                    loadFullDiff = loadFullDiff,
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    sessionManager: SessionManager,
    onOpenRepository: (() -> Unit)?,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
) {
    val colors = LocalGraphColors.current
    val tabs by sessionManager.tabs.collectAsState()
    val activeId by sessionManager.activeId.collectAsState()
    val activeSession by sessionManager.activeSession.collectAsState()
    val emptyState = remember { MutableStateFlow(RepoUiState()) }
    val stateFlow = activeSession?.state ?: emptyState
    val state by stateFlow.collectAsState()
    val session = activeSession

    fun closeActiveTab() {
        activeId?.let(sessionManager::closeTab)
    }

    fun activateNextTab() {
        if (tabs.isEmpty()) return
        val current = tabs.indexOfFirst { it.id == activeId }
        val next = if (current < 0) 0 else (current + 1) % tabs.size
        sessionManager.activate(tabs[next].id)
    }

    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val command = event.isCtrlPressed || event.isMetaPressed
        if (!command) return false
        return when (event.key) {
            Key.R -> {
                session?.refresh()
                true
            }
            Key.W -> {
                closeActiveTab()
                true
            }
            Key.Tab -> {
                activateNextTab()
                true
            }
            else -> false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(UiTestTags.APP)
            .onPreviewKeyEvent(::handleShortcut),
    ) {
        TabBar(
            tabs = tabs,
            activeId = activeId,
            onActivate = sessionManager::activate,
            onClose = sessionManager::closeTab,
            onOpen = { onOpenRepository?.invoke() },
        )
        Divider()
        Toolbar(state = state, session = session)
        Divider()
        FilterBar(state = state, session = session)
        Divider()

        if (session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyRepositoryState(onOpenRepository)
            }
        } else {
            Row(Modifier.weight(GraphStyle.FILL_WEIGHT)) {
                GraphList(
                    state = state,
                    session = session,
                    modifier = Modifier.weight(GraphStyle.GRAPH_PANE_WEIGHT),
                )
                Box(
                    Modifier
                        .width(GraphStyle.DIVIDER_THICKNESS)
                        .fillMaxSize()
                        .background(colors.divider),
                )
                DetailPane(
                    state = state,
                    session = session,
                    loadFullDiff = loadFullDiff,
                    modifier = Modifier.weight(GraphStyle.DETAIL_PANE_WEIGHT),
                )
            }
        }

        val message = state.message
        if (message != null) {
            Divider()
            MessageBar(message = message, onDismiss = { session?.clearMessage() })
        }
    }
}

@Composable
private fun DetailPane(
    state: RepoUiState,
    session: RepoSession,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val selection = state.selection) {
        is Selection.Commit -> CommitPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            modifier = modifier,
        )
        else -> ChangesPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            modifier = modifier,
        )
    }
}

@Composable
private fun EmptyRepositoryState(onOpenRepository: (() -> Unit)?) {
    val colors = LocalGraphColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
        modifier = Modifier.testTag(UiTestTags.EMPTY_STATE),
    ) {
        Text(
            text = "Open repository…",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Button(
            onClick = { onOpenRepository?.invoke() },
            enabled = onOpenRepository != null,
            modifier = Modifier.testTag(UiTestTags.OPEN_REPOSITORY),
        ) {
            Text("Open repository")
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(GraphStyle.DIVIDER_THICKNESS)
            .background(LocalGraphColors.current.divider),
    )
}

@Composable
private fun MessageBar(
    message: UserMessage,
    onDismiss: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val isError = message is UserMessage.Error
    val background = if (isError) colors.errorContainer else colors.surfaceRaised
    val foreground = if (isError) colors.onErrorContainer else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .testTag(UiTestTags.MESSAGE_BAR)
            .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer {
            Column(Modifier.weight(GraphStyle.FILL_WEIGHT)) {
                Text(
                    text = when (message) {
                        is UserMessage.Info -> message.text
                        is UserMessage.Error -> message.text
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
                val stderr = (message as? UserMessage.Error)?.stderr
                if (!stderr.isNullOrBlank()) {
                    Text(
                        text = stderr,
                        style = GraphStyle.monoTextStyle,
                        color = foreground,
                        modifier = Modifier.testTag(UiTestTags.MESSAGE_STDERR),
                    )
                }
            }
        }
        Spacer(Modifier.width(GraphStyle.SPACE_SM))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(UiTestTags.DISMISS_MESSAGE),
        ) {
            Text("Dismiss", style = MaterialTheme.typography.bodySmall)
        }
    }
}
