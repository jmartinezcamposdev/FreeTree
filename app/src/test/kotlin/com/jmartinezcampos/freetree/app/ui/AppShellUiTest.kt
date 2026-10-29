package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun toolbarShowsBranchAndRefsPanelTogglesRefs() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.TOOLBAR).assertExists()
            onNodeWithTag(UiTestTags.BRANCH_LABEL).assertExists()
            onNodeWithText("↑1 ↓2").assertExists()

            fixture.backend.clearCalls()
            onNodeWithTag(UiTestTags.PULL).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.backend.callCount(FakeMethod.PULL) == 1 }

            onNodeWithTag(UiTestTags.REFS_TOGGLE).performClick()
            onNodeWithTag(UiTestTags.REFS_PANEL).assertExists()
            onNodeWithTag(UiTestTags.refItem("refs/heads/main")).assertIsOn()
            onNodeWithTag(UiTestTags.refItem("refs/remotes/origin/main")).assertIsOn()
            onNodeWithTag(UiTestTags.refItem("refs/tags/v1.0")).assertIsOn()

            onNodeWithTag(UiTestTags.SHOW_REMOTES).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.showRemotes == false
            }
            onNodeWithTag(UiTestTags.SHOW_REMOTES).assertIsOff()
            assertFalse(fixture.manager.activeSession.value!!.state.value.showRemotes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun queryAppliesOnEnterAndTabsActivateAndClose() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            val firstId = fixture.manager.activeId.value!!

            onNodeWithTag(UiTestTags.QUERY_INPUT).performTextInput("msg:fix")
            onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.query == "msg:fix"
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callsOf(FakeMethod.LOG).any { it.spec?.grep == "fix" }
            }
            onNodeWithTag(UiTestTags.QUERY_ERROR).assertDoesNotExist()

            fixture.manager.openRepo(Path.of("/repo/ui-two"))
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 2 }
            val secondId = fixture.manager.activeId.value!!
            onNodeWithTag(UiTestTags.tab(0)).assertExists()
            onNodeWithTag(UiTestTags.tab(1)).assertExists()

            onNodeWithTag(UiTestTags.tab(0)).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.manager.activeId.value == firstId }
            onNodeWithTag(UiTestTags.tab(1)).performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.manager.activeId.value == secondId }

            onNodeWithTag(UiTestTags.QUERY_INPUT).performKeyInput {
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
}
