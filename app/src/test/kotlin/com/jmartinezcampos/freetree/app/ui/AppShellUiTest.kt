package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.RepoUiState
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AppShellUiTest {

    private fun fixture(): UiFixture {
        val commits = RandomDag.generate(seed = 41, commitCount = 10)
        return uiFixture(
            commits = commits,
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
                GitRef("refs/remotes/origin/main", "origin/main", RefKind.REMOTE_BRANCH, commits[0].hash, false, null),
                GitRef("refs/tags/v1.0", "v1.0", RefKind.TAG, commits[4].hash, false, null),
            ),
            head = HeadInfo(commits[0].hash, "main"),
            status = WorkingStatus(
                commits[0].hash,
                "origin/main",
                1,
                2,
                false,
                listOf(StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY)),
            ),
        )
    }

    @Test
    fun toolbarShowsBranchAheadBehindAndPullAndPushActions() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph != null
                }

                onNodeWithTag(UiTestTags.TOOLBAR).assertExists()
                onNodeWithTag(UiTestTags.REFRESH).assertDoesNotExist()
                onNodeWithTag(UiTestTags.QUERY_INPUT).assertDoesNotExist()
                onNodeWithTag(UiTestTags.REFS_TOGGLE).assertDoesNotExist()
                onNodeWithTag(UiTestTags.BRANCH_BUTTON).assertExists()
                onNodeWithTag(UiTestTags.BRANCH_BUTTON).assertHasClickAction()
                onNodeWithTag(UiTestTags.BRANCH_ICON, useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.BRANCH_LABEL, useUnmergedTree = true).assertTextContains("main")
                onNodeWithTag(UiTestTags.PULL_ICON, useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.PUSH_ICON, useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.FILTERS_BUTTON).assertExists()
                onNodeWithTag(UiTestTags.FILTERS_BUTTON).assertHasClickAction()
                onNodeWithTag(UiTestTags.FILTERS_ICON, useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.FILTERS_BRANCH_COUNT).assertTextContains("Branches 0")
                onNodeWithTag(UiTestTags.FILTERS_OTHER_COUNT).assertTextContains("Other 0")
                onNodeWithText("↑1 ↓2").assertExists()

                fixture.backend.clearCalls()
                onNodeWithTag(UiTestTags.PULL).performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.backend.callCount(FakeMethod.PULL) == 1 }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun filterCountsSeparateBranchAndContentTerms() {
        assertEquals(FilterCounts(0, 0), filterCounts(RepoUiState()))

        val counts = filterCounts(
            RepoUiState(
                query = "ref:origin/* -ref:topic author:Ada msg:fix path:src",
                hiddenRefs = setOf("refs/heads/main"),
            ),
        )
        assertEquals(FilterCounts(branch = 3, other = 3), counts)
    }

    @Test
    fun filterIndicatorsTrackBranchAndContentFilters() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph != null
                }

                val session = fixture.manager.activeSession.value!!
                session.toggleRef("refs/heads/main")
                waitUntil(timeoutMillis = 5_000) {
                    "refs/heads/main" in session.state.value.hiddenRefs
                }
                onNodeWithTag(UiTestTags.FILTERS_BRANCH_COUNT).assertTextContains("Branches 1")

                session.applyQuery("author:Ada")
                waitUntil(timeoutMillis = 5_000) {
                    session.state.value.query == "author:Ada" && session.state.value.queryError == null
                }
                onNodeWithTag(UiTestTags.FILTERS_OTHER_COUNT).assertTextContains("Other 1")
                saveScreenshot("19-toolbar.png")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun queryAppliesAndTabsActivateCloseAndCycle() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            val firstId = fixture.manager.activeId.value!!
            val session = fixture.manager.activeSession.value!!

            session.applyQuery("msg:fix")
            waitUntil(timeoutMillis = 5_000) {
                session.state.value.query == "msg:fix"
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callsOf(FakeMethod.LOG).any { it.spec?.grep == "fix" }
            }
            assertNull(session.state.value.queryError)

            fixture.manager.openRepo(Path.of("/repo/ui-two"))
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 2 }
            val secondId = fixture.manager.activeId.value!!
            onNodeWithTag(UiTestTags.tab(0)).assertExists()
            onNodeWithTag(UiTestTags.tab(1)).assertExists()

            onNodeWithTag(UiTestTags.tab(0)).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.manager.activeId.value == firstId }
            onNodeWithTag(UiTestTags.tab(1)).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.manager.activeId.value == secondId }

            onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
            onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.Tab)
                keyUp(Key.Tab)
                keyUp(Key.CtrlLeft)
            }
            waitUntil(timeoutMillis = 5_000) { fixture.manager.activeId.value == firstId }

            onNodeWithTag(UiTestTags.tabClose(0)).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 1 }
            assertEquals(secondId, fixture.manager.activeId.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun newTabButtonInvokesTheOpenRepositoryAction() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = fixture()
        var opened = 0
        try {
            setContent { App(fixture.manager, onOpenRepository = { opened++ }) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            onNodeWithTag(UiTestTags.NEW_TAB).performClick()
            waitForIdle()
            assertEquals(1, opened)
        } finally {
            fixture.close()
        }
    }
}
