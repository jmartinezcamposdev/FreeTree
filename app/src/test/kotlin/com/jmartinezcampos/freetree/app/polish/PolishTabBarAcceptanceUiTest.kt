@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.polish

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.app.ui.TabBar
import com.jmartinezcampos.freetree.app.ui.UiFixture
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.app.ui.uiFixture
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.TabState
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Acceptance checks for the polished tab bar: active tab, new-tab affordance and geometry. */
class PolishTabBarAcceptanceUiTest {

    private val iconTag = "tab-new-icon"

    @Test
    fun activeTabIsShownAndNewTabButtonOpensRepository() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val fixture = polishTabsFixture()
            var opened = 0
            try {
                setContent { App(fixture.manager, onOpenRepository = { opened++ }) }
                awaitGraph(fixture)
                fixture.manager.openRepo(Path.of("/repo/polish-tab-two"))
                waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 2 }
                waitForIdle()

                // The newest tab is active; the previous one is not.
                onNodeWithTag(UiTestTags.tab(1)).assertIsSelected()
                onNodeWithTag(UiTestTags.tab(0)).assertIsNotSelected()

                // The new-tab button is present and clickable.
                val newTab = onNodeWithTag(UiTestTags.NEW_TAB)
                newTab.assertExists()
                newTab.assertHasClickAction()

                // Hovering the plus changes the painted icon colour (hover affordance). Park the
                // pointer away from the bar first so the resting capture is not already hovered.
                onNodeWithTag(UiTestTags.GRAPH_LIST).performMouseInput { moveTo(center) }
                waitForIdle()
                val icon = onNodeWithTag(iconTag, useUnmergedTree = true)
                icon.assertExists()
                val restingColor = icon.captureToImage().centerColor()
                newTab.performMouseInput { moveTo(center) }
                waitForIdle()
                val hoveredColor = icon.captureToImage().centerColor()
                assertTrue(
                    colorDistance(restingColor, hoveredColor) > 0.05f,
                    "hovering the new-tab button should change the plus colour " +
                        "($restingColor -> $hoveredColor)",
                )

                // Clicking it asks the host to open a repository.
                newTab.performClick()
                waitForIdle()
                assertEquals(1, opened, "the new-tab plus should request a new repository tab")

                // Geometry complementing the existing tests: tabs are left-to-right, vertically
                // centred in the bar, and the plus sits after the last tab.
                val barBounds = onNodeWithTag(UiTestTags.TAB_BAR).getUnclippedBoundsInRoot()
                val first = onNodeWithTag(UiTestTags.tab(0)).getUnclippedBoundsInRoot()
                val second = onNodeWithTag(UiTestTags.tab(1)).getUnclippedBoundsInRoot()
                val plus = newTab.getUnclippedBoundsInRoot()
                assertTrue(first.right.value <= second.left.value + 0.5f, "tabs should not overlap")
                assertTrue(second.right.value <= plus.left.value + 0.5f, "the plus follows the tabs")
                val barCenterY = (barBounds.top.value + barBounds.bottom.value) / 2
                assertEquals(barCenterY, (first.top.value + first.bottom.value) / 2, 0.5f)
                assertEquals(barCenterY, (plus.top.value + plus.bottom.value) / 2, 0.5f)
                assertEquals(GraphStyle.TAB_HEIGHT.value, (first.bottom - first.top).value, 0.5f)
                assertEquals(GraphStyle.NEW_TAB_SIZE.value, (plus.right - plus.left).value, 0.5f)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun activeTabHighlightIsRoundedAndInsetInTheBar() =
        runDesktopComposeUiTest(width = 600, height = 200) {
            val colors = GraphStyle.dark
            setContent {
                CompositionLocalProvider(LocalGraphColors provides colors) {
                    MaterialTheme(
                        colorScheme = GraphStyle.materialColors(colors, darkTheme = true),
                        typography = GraphStyle.typography,
                    ) {
                        TabBar(
                            tabs = listOf(
                                TabState(id = "a", path = "/repo/alpha"),
                                TabState(id = "b", path = "/repo/beta"),
                            ),
                            activeId = "a",
                            onActivate = {},
                            onClose = {},
                            onOpen = {},
                        )
                    }
                }
            }

            onNodeWithTag(UiTestTags.tab(0)).assertIsSelected()
            onNodeWithTag(UiTestTags.tab(1)).assertIsNotSelected()

            val highlighted = onNodeWithTag(UiTestTags.tab(0)).captureToImage()
            val topCenter = highlighted.colorAt(highlighted.width / 2, 1)
            val topLeftCorner = highlighted.colorAt(0, 0)
            val raised = colors.surfaceRaised

            assertTrue(
                colorDistance(topCenter, raised) < 0.03f,
                "the active tab should paint its rounded highlight ($topCenter vs $raised)",
            )
            assertTrue(
                colorDistance(topLeftCorner, raised) > 0.05f,
                "the highlight corner should be clipped away by the rounded shape",
            )
            assertTrue(
                colorDistance(topLeftCorner, colors.surface) < colorDistance(topLeftCorner, raised),
                "outside the rounded highlight the tab bar background should show through",
            )
        }

    private fun polishTabsFixture(): UiFixture {
        val commits = RandomDag.generate(seed = 307, commitCount = 10)
        return uiFixture(
            commits = commits,
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
            ),
            head = HeadInfo(commits[0].hash, "main"),
            path = "/repo/polish-tab-one",
        )
    }

    private fun colorDistance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return abs(dr) + abs(dg) + abs(db)
    }
}
