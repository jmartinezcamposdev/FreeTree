package com.jmartinezcampos.freetree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.jmartinezcampos.freetree.app.ui.AppTheme
import com.jmartinezcampos.freetree.app.ui.ChangesPanel
import com.jmartinezcampos.freetree.app.ui.CommitPanel
import com.jmartinezcampos.freetree.app.ui.FilterBar
import com.jmartinezcampos.freetree.app.ui.GraphList
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.HistoryViewMode
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.app.ui.PreferencesSplitPreferences
import com.jmartinezcampos.freetree.app.ui.SplitPreferences
import com.jmartinezcampos.freetree.app.ui.TabBar
import com.jmartinezcampos.freetree.app.ui.ThemeMode
import com.jmartinezcampos.freetree.app.ui.Toolbar
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.app.ui.VerticalSplitter
import com.jmartinezcampos.freetree.app.ui.resizePaneFraction
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun App(
    sessionManager: SessionManager,
    onOpenRepository: (() -> Unit)? = null,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit = { session, target ->
        session.openDiff(target, unlimited = true)
    },
    splitPreferences: SplitPreferences? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    historyViewMode: HistoryViewMode = HistoryViewMode.COMPACT,
    showHistoryDate: Boolean = true,
) {
    val resolvedSplitPreferences = splitPreferences ?: remember { PreferencesSplitPreferences() }
    AppTheme(themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppContent(
                sessionManager = sessionManager,
                onOpenRepository = onOpenRepository,
                loadFullDiff = loadFullDiff,
                splitPreferences = resolvedSplitPreferences,
                historyViewMode = historyViewMode,
                showHistoryDate = showHistoryDate,
            )
        }
    }
}

@Composable
private fun AppContent(
    sessionManager: SessionManager,
    onOpenRepository: (() -> Unit)?,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
    splitPreferences: SplitPreferences,
    historyViewMode: HistoryViewMode,
    showHistoryDate: Boolean,
) {
    val colors = LocalGraphColors.current
    val tabs by sessionManager.tabs.collectAsState()
    val activeId by sessionManager.activeId.collectAsState()
    val activeSession by sessionManager.activeSession.collectAsState()
    val emptyState = remember { MutableStateFlow(RepoUiState()) }
    val stateFlow = activeSession?.state ?: emptyState
    val state by stateFlow.collectAsState()
    val session = activeSession
    var graphFraction by remember { mutableFloatStateOf(splitPreferences.loadGraphFraction()) }

    fun closeActiveTab() {
        activeId?.let(sessionManager::closeTab)
    }

    fun activateRelativeTab(delta: Int) {
        if (tabs.isEmpty()) return
        val current = tabs.indexOfFirst { it.id == activeId }
        val next = if (current < 0) 0 else (current + delta).mod(tabs.size)
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
                if (!event.isCtrlPressed) return false
                activateRelativeTab(if (event.isShiftPressed) -1 else 1)
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(GraphStyle.FILL_WEIGHT),
            ) {
                val density = LocalDensity.current
                val contentWidthPx = with(density) {
                    (maxWidth - GraphStyle.SPLITTER_HIT_WIDTH).toPx()
                }
                val minGraphPx = with(density) { GraphStyle.MIN_GRAPH_PANE_WIDTH.toPx() }
                val minDetailPx = with(density) { GraphStyle.MIN_DETAIL_PANE_WIDTH.toPx() }
                LaunchedEffect(contentWidthPx) {
                    graphFraction = resizePaneFraction(
                        current = graphFraction,
                        deltaPx = 0f,
                        contentWidthPx = contentWidthPx,
                        minLeftPx = minGraphPx,
                        minRightPx = minDetailPx,
                    )
                }
                Row(Modifier.fillMaxSize()) {
                    GraphList(
                        state = state,
                        session = session,
                        viewMode = historyViewMode,
                        showDate = showHistoryDate,
                        modifier = Modifier.weight(graphFraction),
                    )
                    VerticalSplitter(
                        onDrag = { delta ->
                            graphFraction = resizePaneFraction(
                                current = graphFraction,
                                deltaPx = delta,
                                contentWidthPx = contentWidthPx,
                                minLeftPx = minGraphPx,
                                minRightPx = minDetailPx,
                            )
                        },
                        onDragStopped = { splitPreferences.saveGraphFraction(graphFraction) },
                    )
                    DetailPane(
                        state = state,
                        session = session,
                        loadFullDiff = loadFullDiff,
                        splitPreferences = splitPreferences,
                        modifier = Modifier.weight(1f - graphFraction),
                    )
                }
            }
        }

        val message = state.message as? UserMessage.Error
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
    splitPreferences: SplitPreferences,
    modifier: Modifier = Modifier,
) {
    when (val selection = state.selection) {
        is Selection.Commit -> CommitPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            splitPreferences = splitPreferences,
            modifier = modifier,
        )
        else -> ChangesPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            splitPreferences = splitPreferences,
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
        SelectionContainer(Modifier.weight(GraphStyle.FILL_WEIGHT)) {
            Column(Modifier.fillMaxWidth()) {
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
        Spacer(Modifier.width(GraphStyle.SPACE_XL))
        Box(
            modifier = Modifier
                .padding(top = GraphStyle.SPACE_ML, end = GraphStyle.SPACE_LG)
                .clip(RoundedCornerShape(GraphStyle.MESSAGE_DISMISS_CORNER_RADIUS))
                .clickable(onClick = onDismiss)
                .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_XS)
                .testTag(UiTestTags.DISMISS_MESSAGE),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
