@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.polish

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.UiFixture
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.app.ui.uiFixture
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Produces the visual evidence for the polish workstream:
 * `app/build/screenshots/polish/01-tab-bar.png` … `04-diff-scrollbar.png`.
 *
 * The PNGs are build artifacts and are never committed; the tests only assert that each capture is
 * a non-blank image of the expected size and that the relevant widgets are on screen.
 */
class PolishScreenshotsTest {

    @Test
    fun tabBarScreenshot() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val commits = RandomDag.generate(seed = 211, commitCount = 12)
        val fixture = uiFixtureForTabs(commits)
        try {
            setContent { App(fixture.manager) }
            awaitGraph(fixture)
            // A second repository gives the bar two tabs; the first one stays active.
            fixture.manager.openRepo(Path.of("/repo/polish-second"))
            waitUntil(timeoutMillis = 5_000) { fixture.manager.tabs.value.size == 2 }
            fixture.manager.activate(fixture.manager.tabs.value.first().id)
            waitForIdle()

            onNodeWithTag(UiTestTags.TAB_BAR).assertExists()
            onNodeWithTag(UiTestTags.tab(0)).assertExists()
            onNodeWithTag(UiTestTags.tab(1)).assertExists()
            val newTab = onNodeWithTag(UiTestTags.NEW_TAB)
            newTab.assertExists()
            // Hover the plus button so the capture shows its hover affordance.
            newTab.performMouseInput { moveTo(center) }
            waitForIdle()

            val path = savePolishScreenshot("01-tab-bar.png")
            assertScreenshot(path, width = 1500, height = 950)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun historyScrollbarScreenshot() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val fixture = polishGraphFixture(commitCount = 80, seed = 223)
        try {
            setContent { App(fixture.manager) }
            awaitGraph(fixture)
            // Scroll to a later position, then hover the scrollbar so its thumb is highlighted.
            dragScrollbar(UiTestTags.GRAPH_SCROLLBAR, fromFraction = 0.05f, toFraction = 0.6f)
            waitForIdle()
            onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR).performMouseInput { moveTo(center) }
            waitForIdle()
            assertTrue(
                visibleCommitRows(80).isNotEmpty(),
                "the scrolled graph should still show commits",
            )

            val path = savePolishScreenshot("02-history-scrollbar.png")
            assertScreenshot(path, width = 1500, height = 950)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun commitChangedFilesScreenshot() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val scenario = polishCommitScenario(fileCount = 60, diffLineCount = 200)
        try {
            setContent { App(scenario.fixture.manager) }
            awaitGraph(scenario.fixture)
            onNodeWithTag(UiTestTags.commitRowForHash(scenario.tip.hash)).performClick()
            awaitSelection(scenario.fixture, Selection.Commit(scenario.tip.hash))
            awaitCommitDetail(scenario.fixture, scenario.tip.hash)
            awaitChangedFiles(scenario.fixture, 60)
            waitForIdle()

            // Wake the transient changed-files scrollbar and capture it while it is visible.
            wheelScroll(UiTestTags.changedFile("file-0.txt"), delta = 2f)
            waitForIdle()
            onNodeWithTag(UiTestTags.COMMIT_FILES_SCROLLBAR).performMouseInput { moveTo(center) }

            val path = savePolishScreenshot("03-commit-changed-files.png")
            assertScreenshot(path, width = 1500, height = 950)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun diffScrollbarScreenshot() = runDesktopComposeUiTest(width = 1500, height = 950) {
        val scenario = polishCommitScenario(fileCount = 60, diffLineCount = 200)
        try {
            setContent { App(scenario.fixture.manager) }
            awaitGraph(scenario.fixture)
            onNodeWithTag(UiTestTags.commitRowForHash(scenario.tip.hash)).performClick()
            awaitSelection(scenario.fixture, Selection.Commit(scenario.tip.hash))
            awaitCommitDetail(scenario.fixture, scenario.tip.hash)
            awaitChangedFiles(scenario.fixture, 60)
            onNodeWithTag(UiTestTags.changedFile(scenario.files.first().path)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                scenario.fixture.manager.activeSession.value?.state?.value?.openDiff != null
            }
            waitForIdle()

            wheelScroll(UiTestTags.DIFF_VIEW, delta = 2f)
            waitForIdle()
            onNodeWithTag(UiTestTags.DIFF_SCROLLBAR).performMouseInput { moveTo(center) }

            val path = savePolishScreenshot("04-diff-scrollbar.png")
            assertScreenshot(path, width = 1500, height = 950)
        } finally {
            scenario.close()
        }
    }

    private fun assertScreenshot(path: Path, width: Int, height: Int) {
        assertTrue(Files.exists(path), "missing screenshot $path")
        assertTrue(Files.size(path) > 10_000, "suspiciously small screenshot $path")
        val image = ImageIO.read(path.toFile())
        assertEquals(width, image.width, "screenshot width for $path")
        assertEquals(height, image.height, "screenshot height for $path")
        val colors = buildSet {
            for (x in 0 until image.width step 7) {
                for (y in 0 until image.height step 7) {
                    add(image.getRGB(x, y))
                }
            }
        }
        assertTrue(colors.size > 20, "screenshot $path looks blank (${colors.size} colours)")
    }
}

private fun uiFixtureForTabs(commits: List<RawCommit>): UiFixture =
    uiFixture(
        commits = commits,
        refs = listOf(
            GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
        ),
        head = HeadInfo(commits[0].hash, "main"),
        path = "/repo/polish-tabs",
    )
