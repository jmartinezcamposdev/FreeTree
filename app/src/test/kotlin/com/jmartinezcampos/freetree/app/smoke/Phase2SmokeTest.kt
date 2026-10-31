package com.jmartinezcampos.freetree.app.smoke

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.MessageLevel
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionPhase
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenario
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenarios
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Phase2SmokeTest {

    private val scenario = FakeRepoScenarios.standard()
    private val mergeHash = FakeRepoScenarios.mainHash

    @Test
    fun graphRendersMergeRefChipsAndHead() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val app = smokeApp("graph" to scenario)
        try {
            setContent { App(app.manager) }
            awaitGraph(app)

            onNodeWithTag(UiTestTags.CHANGES_ROW).assertTextContains("Changed files (4)")
            onNodeWithTag(UiTestTags.commitRow(0)).assertExists()
            onNodeWithTag(UiTestTags.commitRowForHash(mergeHash))
                .assertTextContains("Merge topic into main", substring = true)
            onNodeWithText("HEAD").assertDoesNotExist()
            onNodeWithText("origin/main").assertExists()
            onNodeWithText("+1").assertDoesNotExist()
            assertTrue(
                app.manager.activeSession.value!!.state.value.labelsByCommit.getValue(mergeHash)
                    .any { it.shortName == "v2.0" },
            )
            assertEquals(9, app.manager.activeSession.value!!.state.value.graph!!.rowCount)
            assertEquals(2, app.manager.activeSession.value!!.state.value.graph!!.parentCount(0))

            saveScreenshot("01-main-graph.png")
        } finally {
            app.close()
        }
    }

    @Test
    fun selectingCommitShowsFilesAndDiff() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val app = smokeApp("commit" to scenario)
        try {
            setContent { App(app.manager) }
            awaitGraph(app)

            onNodeWithTag(UiTestTags.commitRowForHash(mergeHash)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                val state = app.manager.activeSession.value?.state?.value
                state?.selection == Selection.Commit(mergeHash) && state.commitDetail != null
            }

            onNodeWithTag(UiTestTags.COMMIT_PANEL).assertExists()
            onNodeWithText("Merge commit with 2 parents").assertExists()
            onNodeWithTag(UiTestTags.changedFile("src/App.kt")).assertExists()
            onNodeWithTag(UiTestTags.changedFile("src/Graph.kt")).assertExists()
            onNodeWithTag(UiTestTags.changedFile("src/Names.kt")).assertExists()

            onNodeWithTag(UiTestTags.changedFile("src/App.kt")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                app.manager.activeSession.value?.state?.value?.openDiff != null
            }
            onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
            onNodeWithText("+    graphPanel()").assertExists()
            saveScreenshot("03-commit-panel-diff.png")
        } finally {
            app.close()
        }
    }

    @Test
    fun stageAndUnstageMoveAFileBetweenSections() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val changes = scenario.copy(status = workingStatus(scenario, "src/Staged.kt", "README.md"))
        val app = smokeApp("stage" to changes, statefulWrites = true)
        try {
            setContent { App(app.manager) }
            awaitGraph(app)
            onNodeWithTag(UiTestTags.STAGED_SECTION).assertExists()
            onNodeWithTag(UiTestTags.UNSTAGED_SECTION).assertExists()
            onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).assertExists()
            onNodeWithTag(UiTestTags.unstagedFile("README.md")).assertExists()
            saveScreenshot("02-changes-staged-unstaged.png")

            onNodeWithTag(UiTestTags.stageFile("README.md")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                val entry = statusEntry(app, "README.md")
                entry?.index == 'M' && entry.worktree == ' '
            }
            onNodeWithTag(UiTestTags.stagedFile("README.md")).assertExists()
            onNodeWithTag(UiTestTags.unstagedFile("README.md")).assertDoesNotExist()
            assertEquals(
                listOf("README.md"),
                app.backend().callsOf(FakeMethod.STAGE).single().paths,
            )
            saveScreenshot("02b-changes-after-stage.png")

            onNodeWithTag(UiTestTags.UNSTAGE_ALL).performClick()
            waitUntil(timeoutMillis = 5_000) {
                statusEntries(app).all { it.index == ' ' }
            }
            onNodeWithTag(UiTestTags.stagedFile("README.md")).assertDoesNotExist()
            onNodeWithTag(UiTestTags.unstagedFile("README.md")).assertExists()
            val readme = statusEntry(app, "README.md")!!
            assertEquals(' ', readme.index)
            assertEquals('M', readme.worktree)
            assertTrue(app.backend().callsOf(FakeMethod.UNSTAGE).isNotEmpty())
        } finally {
            app.close()
        }
    }

    @Test
    fun discardStagedIsIndexOnlyAndDiscardUnstagedRequiresConfirmation() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val changes = scenario.copy(
                status = workingStatus(scenario, "src/App.kt", "README.md", "notes.txt"),
            )
            val app = smokeApp("discard" to changes, statefulWrites = true)
            try {
                setContent { App(app.manager) }
                awaitGraph(app)

                onNodeWithTag(UiTestTags.discardStaged("src/App.kt")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    val entry = statusEntry(app, "src/App.kt")
                    entry?.index == ' ' && entry.worktree == 'M'
                }
                onNodeWithTag(UiTestTags.stagedFile("src/App.kt")).assertDoesNotExist()
                onNodeWithTag(UiTestTags.unstagedFile("src/App.kt")).assertExists()
                assertEquals(1, app.backend().callCount(FakeMethod.DISCARD_STAGED))
                saveScreenshot("02c-discard-staged-index-only.png")

                onNodeWithTag(UiTestTags.discardUnstaged("README.md")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertExists()
                onNodeWithText("restores \"README.md\" from the index", substring = true).assertExists()
                onNodeWithText("The staged content is kept.", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CANCEL).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertDoesNotExist()
                assertEquals(0, app.backend().callCount(FakeMethod.DISCARD_UNSTAGED))

                onNodeWithTag(UiTestTags.discardUnstaged("README.md")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                waitUntil(timeoutMillis = 5_000) { statusEntry(app, "README.md") == null }
                onNodeWithTag(UiTestTags.unstagedFile("README.md")).assertDoesNotExist()

                onNodeWithTag(UiTestTags.discardUnstaged("notes.txt")).performClick()
                onNodeWithText("permanently deletes \"notes.txt\" from disk", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                waitUntil(timeoutMillis = 5_000) { statusEntry(app, "notes.txt") == null }
                onNodeWithTag(UiTestTags.unstagedFile("notes.txt")).assertDoesNotExist()
                assertEquals(2, app.backend().callCount(FakeMethod.DISCARD_UNSTAGED))
            } finally {
                app.close()
            }
        }

    @Test
    fun commitStagedFilesUsesMessageAndRefreshesTheWorkingTree() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val stagedOnly = scenario.copy(status = workingStatus(scenario, "src/Staged.kt"))
            val app = smokeApp("commit-action" to stagedOnly, statefulWrites = true)
            val backend = app.backend()
            try {
                setContent { App(app.manager) }
                awaitGraph(app)
                onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).assertExists()

                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("Commit from smoke")
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 5_000) {
                    backend.callCount(FakeMethod.COMMIT) == 1 &&
                        app.manager.activeSession.value?.state?.value?.busy == null
                }

                val state = app.manager.activeSession.value!!.state.value
                assertEquals("Commit from smoke", backend.callsOf(FakeMethod.COMMIT).single().message)
                assertNull(state.message)
                assertTrue(app.manager.messageLog.entries.value.any { it.text == "Commit created." })
                assertTrue(state.status!!.entries.isEmpty())
                assertTrue(state.head!!.commit != mergeHash)
                assertEquals(state.head!!.commit, backend.head.commit)
                onNodeWithText("Working tree clean").assertExists()
                saveScreenshot("07-commit-success.png")
            } finally {
                app.close()
            }
        }

    @Test
    fun pushAndPullDispatchAndFailureStderrIsVisible() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val app = smokeApp("remote" to scenario)
        val backend = app.backend()
        backend.setResult(FakeMethod.PUSH, GitResult.Ok("Everything up-to-date"))
        backend.setResult(FakeMethod.PULL, GitResult.Failure(1, "fatal: no tracking information"))
        try {
            setContent { App(app.manager) }
            awaitGraph(app)

            onNodeWithTag(UiTestTags.PUSH).performClick()
            waitUntil(timeoutMillis = 5_000) {
                backend.callCount(FakeMethod.PUSH) == 1 &&
                    app.manager.activeSession.value?.state?.value?.busy == null
            }
            assertNull(app.manager.activeSession.value?.state?.value?.message)
            assertTrue(
                app.manager.messageLog.entries.value.any { it.text == "Everything up-to-date" },
            )
            onNodeWithTag(UiTestTags.MESSAGE_BAR).assertDoesNotExist()

            onNodeWithTag(UiTestTags.PULL).performClick()
            waitUntil(timeoutMillis = 5_000) {
                backend.callCount(FakeMethod.PULL) == 1 &&
                    app.manager.activeSession.value?.state?.value?.busy == null
            }
            val message = app.manager.activeSession.value!!.state.value.message
            assertTrue(message is UserMessage.Error)
            assertTrue(
                app.manager.messageLog.entries.value.any {
                    it.level == MessageLevel.ERROR &&
                        it.stderr == "fatal: no tracking information"
                },
            )
            onNodeWithTag(UiTestTags.MESSAGE_BAR).assertExists()
            onNodeWithTag(UiTestTags.MESSAGE_STDERR)
                .assertTextContains("fatal: no tracking information")
            saveScreenshot("06-push-pull-stderr.png")
        } finally {
            app.close()
        }
    }

    @Test
    fun refsHideQueryFilterFlatModeAndQueryErrorsKeepTheGraph() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val app = smokeApp("query" to scenario)
            try {
                setContent { App(app.manager) }
                awaitGraph(app)

                onNodeWithTag(UiTestTags.REFS_TOGGLE).performClick()
                onNodeWithTag(UiTestTags.REFS_PANEL).assertExists()
                onNodeWithTag(UiTestTags.refItem("refs/heads/topic")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    "refs/heads/topic" in app.manager.activeSession.value!!.state.value.hiddenRefs
                }

                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextInput("author:Ada")
                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
                waitUntil(timeoutMillis = 5_000) {
                    val state = app.manager.activeSession.value?.state?.value
                    state?.query == "author:Ada" && state.graph?.flat == true
                }
                onNodeWithText("Flat view (content filters active)").assertExists()
                onNodeWithTag(UiTestTags.QUERY_ERROR).assertDoesNotExist()
                saveScreenshot("04-refs-query.png")

                val stableGraph = app.manager.activeSession.value!!.state.value.graph
                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextClearance()
                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextInput("bogus:value")
                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
                waitUntil(timeoutMillis = 5_000) {
                    app.manager.activeSession.value?.state?.value?.queryError != null
                }
                onNodeWithTag(UiTestTags.QUERY_ERROR).assertExists()
                assertSame(stableGraph, app.manager.activeSession.value!!.state.value.graph)
                saveScreenshot("04b-query-error.png")
            } finally {
                app.close()
            }
        }

    @Test
    fun tabSwitchPreservesPerTabGraphSelectionAndQuery() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val app = smokeApp("alpha" to scenario, "beta" to scenario)
            try {
                setContent { App(app.manager) }
                awaitGraph(app)
                val alphaId = app.manager.activeId.value!!
                val alpha = app.manager.session(alphaId)!!
                val alphaSelection = scenario.commits[4].hash

                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextInput("author:Ada")
                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
                waitUntil(timeoutMillis = 5_000) { alpha.state.value.query == "author:Ada" }
                onNodeWithTag(UiTestTags.commitRowForHash(alphaSelection)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    alpha.state.value.selection == Selection.Commit(alphaSelection)
                }
                val alphaGraph = alpha.state.value.graph

                app.manager.openRepo(Path.of("/repo/beta"))
                waitUntil(timeoutMillis = 5_000) {
                    app.manager.tabs.value.size == 2 && app.manager.activeId.value != alphaId
                }
                val beta = app.manager.activeSession.value!!
                waitUntil(timeoutMillis = 5_000) { beta.state.value.graph != null }
                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextClearance()
                onNodeWithTag(UiTestTags.QUERY_INPUT).performTextInput("msg:Merge")
                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
                waitUntil(timeoutMillis = 5_000) { beta.state.value.query == "msg:Merge" }
                onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    beta.state.value.selection == Selection.WorkingTree
                }
                assertExclusiveActive(app)

                onNodeWithTag(UiTestTags.tab(0)).performClick()
                waitUntil(timeoutMillis = 5_000) { app.manager.activeId.value == alphaId }
                assertEquals("author:Ada", alpha.state.value.query)
                assertEquals(Selection.Commit(alphaSelection), alpha.state.value.selection)
                assertSame(alphaGraph, alpha.state.value.graph)
                assertExclusiveActive(app)
                waitUntil(timeoutMillis = 5_000) { alpha.state.value.commitDetail != null }
                saveScreenshot("05-tab-switch.png")

                val betaId = app.manager.tabs.value.first { it.id != alphaId }.id
                onNodeWithTag(UiTestTags.tab(1)).performClick()
                waitUntil(timeoutMillis = 5_000) { app.manager.activeId.value == betaId }
                assertEquals("msg:Merge", beta.state.value.query)
                assertEquals(Selection.WorkingTree, beta.state.value.selection)
                assertExclusiveActive(app)
            } finally {
                app.close()
            }
        }

    @Test
    fun keyboardNavigationSelectsRowsAndCtrlTabSwitchesTabs() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val app = smokeApp("keyboard-a" to scenario, "keyboard-b" to scenario)
            try {
                setContent { App(app.manager) }
                awaitGraph(app)
                val firstId = app.manager.activeId.value!!
                onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
                waitForIdle()

                onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                    keyDown(Key.DirectionDown)
                    keyUp(Key.DirectionDown)
                }
                waitUntil(timeoutMillis = 5_000) {
                    app.manager.activeSession.value?.state?.value?.selection ==
                        Selection.Commit(mergeHash)
                }
                onNodeWithTag(UiTestTags.commitRowForHash(mergeHash)).assertIsSelected()

                app.manager.openRepo(Path.of("/repo/keyboard-b"))
                waitUntil(timeoutMillis = 5_000) {
                    app.manager.tabs.value.size == 2 && app.manager.activeId.value != firstId
                }
                onNodeWithTag(UiTestTags.QUERY_INPUT).performClick()
                waitForIdle()
                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.CtrlLeft)
                    keyDown(Key.Tab)
                    keyUp(Key.Tab)
                    keyUp(Key.CtrlLeft)
                }
                waitUntil(timeoutMillis = 5_000) { app.manager.activeId.value == firstId }
                assertExclusiveActive(app)
                assertEquals(Selection.Commit(mergeHash), app.manager.activeSession.value!!.state.value.selection)

                onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                    keyDown(Key.CtrlLeft)
                    keyDown(Key.Tab)
                    keyUp(Key.Tab)
                    keyUp(Key.CtrlLeft)
                }
                waitUntil(timeoutMillis = 5_000) { app.manager.activeId.value != firstId }
                assertEquals(2, app.manager.tabs.value.size)
                assertExclusiveActive(app)
            } finally {
                app.close()
            }
        }

    private suspend fun DesktopComposeUiTest.awaitGraph(app: SmokeApp) {
        waitUntil(timeoutMillis = 5_000) {
            val state = app.manager.activeSession.value?.state?.value
            state?.graph != null && state.graph!!.complete && state.status != null
        }
    }

    private fun workingStatus(
        scenario: FakeRepoScenario,
        vararg paths: String,
    ): WorkingStatus = scenario.status.copy(
        entries = scenario.status.entries.filter { it.path in paths },
    )

    private fun statusEntry(app: SmokeApp, path: String): StatusEntry? =
        app.manager.activeSession.value?.state?.value?.status?.entries?.firstOrNull { it.path == path }

    private fun statusEntries(app: SmokeApp): List<StatusEntry> =
        app.manager.activeSession.value?.state?.value?.status?.entries ?: emptyList()

    private fun assertExclusiveActive(app: SmokeApp) {
        val sessions = app.manager.tabs.value.mapNotNull { app.manager.session(it.id) }
        assertEquals(1, sessions.count { it.phase.value == SessionPhase.ACTIVE })
        assertSame(
            sessions.single { it.phase.value == SessionPhase.ACTIVE },
            app.manager.activeSession.value,
        )
        assertNull(sessions.firstOrNull { it.id != app.manager.activeId.value && it.phase.value != SessionPhase.INACTIVE })
    }
}
