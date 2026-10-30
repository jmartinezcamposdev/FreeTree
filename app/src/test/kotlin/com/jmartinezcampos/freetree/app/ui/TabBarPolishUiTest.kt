package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NEW_TAB_ICON_TAG = "tab-new-icon"

@OptIn(ExperimentalTestApi::class)
class TabBarPolishUiTest {

    private fun fixture(): UiFixture {
        val commits = RandomDag.generate(seed = 73, commitCount = 8)
        return uiFixture(
            commits = commits,
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
    }

    @Test
    fun newTabButtonIsPresentAndOpensRepository() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = fixture()
        var opened = 0
        try {
            setContent { App(fixture.manager, onOpenRepository = { opened++ }) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            val newTab = onNodeWithTag(UiTestTags.NEW_TAB)
            newTab.assertExists()
            newTab.assertWidthIsEqualTo(GraphStyle.NEW_TAB_SIZE)
            newTab.assertHeightIsEqualTo(GraphStyle.NEW_TAB_SIZE)

            newTab.performClick()
            waitForIdle()
            assertEquals(1, opened)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun newTabPlusIconIsGeometricallyCenteredInButton() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            val button = onNodeWithTag(UiTestTags.NEW_TAB).getUnclippedBoundsInRoot()
            val icon = onNodeWithTag(NEW_TAB_ICON_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

            onNodeWithTag(NEW_TAB_ICON_TAG, useUnmergedTree = true)
                .assertWidthIsEqualTo(GraphStyle.NEW_TAB_ICON_SIZE)
                .assertHeightIsEqualTo(GraphStyle.NEW_TAB_ICON_SIZE)
            val buttonCenterX = (button.left + button.right) / 2
            val buttonCenterY = (button.top + button.bottom) / 2
            val iconCenterX = (icon.left + icon.right) / 2
            val iconCenterY = (icon.top + icon.bottom) / 2
            assertEquals(buttonCenterX.value, iconCenterX.value, 0.5f)
            assertEquals(buttonCenterY.value, iconCenterY.value, 0.5f)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun tabsRespectStartPaddingAndExposeActiveSelection() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            fixture.manager.openRepo(Path.of("/repo/ui-two"))
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 2 }
            waitForIdle()

            val firstTab = onNodeWithTag(UiTestTags.tab(0))
            val secondTab = onNodeWithTag(UiTestTags.tab(1))
            firstTab.assertExists()
            secondTab.assertExists()
            firstTab.assertHeightIsEqualTo(GraphStyle.TAB_HEIGHT)
            secondTab.assertHeightIsEqualTo(GraphStyle.TAB_HEIGHT)

            val tabBar = onNodeWithTag(UiTestTags.TAB_BAR).getUnclippedBoundsInRoot()
            val firstTabBounds = firstTab.getUnclippedBoundsInRoot()
            assertEquals(
                GraphStyle.TAB_BAR_START_PADDING.value,
                (firstTabBounds.left - tabBar.left).value,
                0.5f,
            )

            secondTab.assertIsSelected()
            firstTab.assertIsNotSelected()

            onNodeWithTag(UiTestTags.tab(0)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeId.value == fixture.manager.tabs.value.first().id
            }
            firstTab.assertIsSelected()
            secondTab.assertIsNotSelected()
        } finally {
            fixture.close()
        }
    }
}
