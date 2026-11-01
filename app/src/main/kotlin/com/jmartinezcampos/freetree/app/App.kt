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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.jmartinezcampos.freetree.app.ui.AppDialog
import com.jmartinezcampos.freetree.app.ui.AppDialogController
import com.jmartinezcampos.freetree.app.ui.AppSettingsController
import com.jmartinezcampos.freetree.app.ui.AppTheme
import com.jmartinezcampos.freetree.app.ui.ChangesPanel
import com.jmartinezcampos.freetree.app.ui.CommitMenuAction
import com.jmartinezcampos.freetree.app.ui.CommitPanel
import com.jmartinezcampos.freetree.app.ui.FreeTreeDialogHost
import com.jmartinezcampos.freetree.app.ui.buildBranchPickerEntries
import com.jmartinezcampos.freetree.app.ui.GraphList
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.HistoryViewMode
import com.jmartinezcampos.freetree.app.ui.InMemorySettingsStore
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.app.ui.TabBar
import com.jmartinezcampos.freetree.app.ui.ThemeMode
import com.jmartinezcampos.freetree.app.ui.Toolbar
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.app.ui.VerticalSplitter
import com.jmartinezcampos.freetree.app.ui.validateRefName
import com.jmartinezcampos.freetree.app.ui.resizePaneFraction
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.session.RepoSession
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
internal fun App(
    sessionManager: SessionManager,
    onOpenRepository: (() -> Unit)? = null,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit = { session, target ->
        session.openDiff(target, unlimited = true)
    },
    settingsController: AppSettingsController? = null,
    dialogController: AppDialogController? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    historyViewMode: HistoryViewMode = HistoryViewMode.COMPACT,
    showHistoryDate: Boolean = true,
) {
    val resolvedSettings = settingsController ?: remember {
        AppSettingsController(InMemorySettingsStore())
    }
    val resolvedDialogs = dialogController ?: remember { AppDialogController() }
    AppTheme(themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppContent(
                sessionManager = sessionManager,
                onOpenRepository = onOpenRepository,
                loadFullDiff = loadFullDiff,
                settingsController = resolvedSettings,
                dialogController = resolvedDialogs,
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
    settingsController: AppSettingsController,
    dialogController: AppDialogController,
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
    val activeDialog by dialogController.dialog.collectAsState()
    val session = activeSession
    LaunchedEffect(session?.id) {
        dialogController.dismiss()
    }
    var graphFraction by remember {
        mutableFloatStateOf(settingsController.state.value.graphPaneFraction)
    }
    val latestGraphFraction = rememberUpdatedState(graphFraction)
    DisposableEffect(settingsController) {
        onDispose { settingsController.setGraphPaneFraction(latestGraphFraction.value) }
    }
    val coroutineScope = rememberCoroutineScope()

    fun handleCommitAction(action: CommitMenuAction) {
        val active = session ?: return
        when (action) {
            is CommitMenuAction.CopyText -> Unit
            is CommitMenuAction.CheckoutLocal -> active.checkoutLocal(action.branch)
            is CommitMenuAction.CheckoutDetached -> active.checkoutDetached(action.hash)
            is CommitMenuAction.CheckoutRemote -> active.checkoutRemoteAsLocal(
                action.localBranch,
                action.remoteRef,
            )
            is CommitMenuAction.CreateBranch -> {
                dialogController.show(
                    AppDialog.Prompt(
                        tag = UiTestTags.COMMIT_NAME_DIALOG,
                        title = "Create branch here",
                        description = "Create a branch at this commit and check it out.",
                        fieldLabel = "Branch name",
                        confirmLabel = "Create and checkout",
                        validate = { validateRefName(it, isBranch = true) },
                        onConfirm = { name -> active.createBranchAndCheckout(name, action.hash) },
                    ),
                )
            }
            is CommitMenuAction.CreateTag -> {
                dialogController.show(
                    AppDialog.Prompt(
                        tag = UiTestTags.COMMIT_NAME_DIALOG,
                        title = "Create tag here",
                        description = "Create a lightweight tag at this commit.",
                        fieldLabel = "Tag name",
                        confirmLabel = "Create tag",
                        validate = { validateRefName(it, isBranch = false) },
                        onConfirm = { name -> active.createTag(name, action.hash) },
                    ),
                )
            }
            is CommitMenuAction.DeleteLocal -> {
                coroutineScope.launch {
                    val safe = try {
                        active.isBranchDeletionSafe(action.hash, action.branch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (sessionManager.activeSession.value?.id != active.id) return@launch
                    if (safe) {
                        active.deleteLocalBranch(action.branch)
                    } else {
                        dialogController.show(
                            AppDialog.Confirm(
                                tag = UiTestTags.DELETE_BRANCH_DIALOG,
                                title = "Branch not fully merged",
                                message = "The branch \"${action.branch}\" contains commits that are not " +
                                    "reachable from the current HEAD or another local branch.",
                                warning = "Deleting it may lose those commits.",
                                confirmLabel = "Delete anyway",
                                destructive = true,
                                onConfirm = {
                                    active.deleteLocalBranch(action.branch, force = true)
                                },
                            ),
                        )
                    }
                }
            }
            is CommitMenuAction.DeleteRemote -> {
                coroutineScope.launch {
                    val safe = try {
                        active.isBranchDeletionSafe(action.hash)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (sessionManager.activeSession.value?.id != active.id) return@launch
                    dialogController.show(
                        AppDialog.Confirm(
                            tag = UiTestTags.DELETE_BRANCH_DIALOG,
                            title = "Delete remote branch",
                            message = "Delete remote branch \"${action.remote}/${action.branch}\"? " +
                                "This removes the branch from the remote and cannot be undone.",
                            warning = if (safe) {
                                null
                            } else {
                                "This branch is not merged into the current HEAD or another local branch."
                            },
                            confirmLabel = "Delete remote branch",
                            destructive = true,
                            onConfirm = {
                                active.deleteRemoteBranch(action.remote, action.branch)
                            },
                        ),
                    )
                }
            }
        }
    }

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

    Box(Modifier.fillMaxSize()) {
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
        Toolbar(
            state = state,
            session = session,
            onBranchClick = {
                session?.let { active ->
                    dialogController.show(
                        AppDialog.BranchPicker(
                            entries = buildBranchPickerEntries(
                                refs = state.refs,
                                currentBranch = state.head?.branchShortName,
                            ),
                            busy = state.busy != null,
                            onSelect = { entry ->
                                if (entry.isRemote) {
                                    entry.remoteRef?.let { remote ->
                                        active.checkoutRemoteAsLocal(entry.localName, remote)
                                    }
                                } else if (!entry.isCurrent) {
                                    active.checkoutLocal(entry.localName)
                                }
                            },
                        ),
                    )
                }
            },
            onFiltersClick = {
                dialogController.show(
                    AppDialog.Info(
                        tag = UiTestTags.FILTERS_INFO_DIALOG,
                        title = "Filters",
                        message = "Editing filters will be supported in a future version of FreeTree.",
                    ),
                )
            },
        )
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
                        onCommitAction = ::handleCommitAction,
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
                        onDragStopped = { settingsController.setGraphPaneFraction(graphFraction) },
                    )
                    DetailPane(
                        state = state,
                        session = session,
                        loadFullDiff = loadFullDiff,
                        settingsController = settingsController,
                        onDiscardRequest = { entry ->
                            dialogController.show(
                                AppDialog.Confirm(
                                    tag = UiTestTags.DISCARD_DIALOG,
                                    title = "Discard unstaged changes?",
                                    message = if (entry.kind == StatusKind.UNTRACKED) {
                                        "This permanently deletes \"${entry.path}\" from disk."
                                    } else {
                                        "This restores \"${entry.path}\" from the index and discards its " +
                                            "unstaged changes on disk. The staged content is kept."
                                    },
                                    warning = "This cannot be undone.",
                                    confirmLabel = "Discard changes",
                                    destructive = true,
                                    onConfirm = {
                                        session.discardUnstaged(listOf(entry.path))
                                    },
                                ),
                            )
                        },
                        onDiscardAllRequest = { paths ->
                            if (paths.isNotEmpty()) {
                                dialogController.show(
                                    AppDialog.Confirm(
                                        tag = UiTestTags.DISCARD_ALL_DIALOG,
                                        title = "Discard all unstaged changes?",
                                        message = "Discard unstaged changes in ${paths.size} file(s)?",
                                        warning = "Tracked files will be restored from the index. Untracked " +
                                            "files will be deleted from disk.",
                                        confirmLabel = "Discard all",
                                        destructive = true,
                                        onConfirm = { session.discardUnstaged(paths) },
                                    ),
                                )
                            }
                        },
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

        FreeTreeDialogHost(dialog = activeDialog, controller = dialogController)
    }
}

@Composable
private fun DetailPane(
    state: RepoUiState,
    session: RepoSession,
    loadFullDiff: (RepoSession, DiffTarget) -> Unit,
    settingsController: AppSettingsController,
    onDiscardRequest: (StatusEntry) -> Unit,
    onDiscardAllRequest: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val selection = state.selection) {
        is Selection.Commit -> CommitPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            settingsController = settingsController,
            modifier = modifier,
        )
        else -> ChangesPanel(
            state = state,
            session = session,
            loadFullDiff = loadFullDiff,
            settingsController = settingsController,
            onDiscardRequest = onDiscardRequest,
            onDiscardAllRequest = onDiscardAllRequest,
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
