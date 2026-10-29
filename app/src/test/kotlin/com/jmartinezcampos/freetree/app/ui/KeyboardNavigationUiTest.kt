package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyboardNavigationUiTest {

    @Test
    fun arrowHomeEndAndPageKeysMoveGraphSelection() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 31, commitCount = 30)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null)),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
            waitForIdle()

            pressKey(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[0].hash }
            onNodeWithTag(UiTestTags.commitRowForHash(commits[0].hash)).assertIsSelected()

            pressKey(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[1].hash }
            pressKey(Key.DirectionUp)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[0].hash }

            pressKey(Key.MoveEnd)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits.last().hash }
            pressKey(Key.MoveHome)
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.selection == Selection.WorkingTree
            }

            pressKey(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[0].hash }
            pressKey(Key.PageDown)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[GraphStyle.PAGE_ROWS].hash }
            pressKey(Key.PageUp)
            waitUntil(timeoutMillis = 5_000) { selectedHash(fixture) == commits[0].hash }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ctrlRRefreshesAndCtrlWClosesTheTab() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 33, commitCount = 6)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null)),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
            waitForIdle()
            fixture.backend.clearCalls()

            onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.R)
                keyUp(Key.R)
                keyUp(Key.CtrlLeft)
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.REFS) > 0
            }

            onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.W)
                keyUp(Key.W)
                keyUp(Key.CtrlLeft)
            }
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.isEmpty() }
            assertEquals(null, fixture.manager.activeSession.value)
        } finally {
            fixture.close()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.pressKey(key: Key) {
        onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
            keyDown(key)
            keyUp(key)
        }
    }

    private fun selectedHash(fixture: UiFixture): String? =
        (fixture.manager.activeSession.value?.state?.value?.selection as? Selection.Commit)?.hash
}
