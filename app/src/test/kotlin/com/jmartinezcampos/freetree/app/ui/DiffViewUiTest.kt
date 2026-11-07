package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.session.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION")
class DiffViewUiTest {

    @Test
    fun binaryDiffShowsBinaryNotice() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = DiffText(emptyList(), binary = true, truncated = false),
                        loading = false,
                        error = null,
                        onLoadFull = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        onNodeWithTag(UiTestTags.DIFF_BINARY).assertExists()
    }

    @Test
    fun truncatedDiffOffersLoadFull() = runDesktopComposeUiTest(width = 800, height = 600) {
        var loadFullCalls = 0
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = DiffText(
                            lines = listOf(DiffLine(DiffLineKind.ADD, null, 1, "new line")),
                            binary = false,
                            truncated = true,
                        ),
                        loading = false,
                        error = null,
                        onLoadFull = { loadFullCalls++ },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        onNodeWithTag(UiTestTags.DIFF_LOAD_FULL).assertExists()
        onNodeWithTag(UiTestTags.DIFF_LOAD_FULL).performClick()
        assertEquals(1, loadFullCalls)
        onNodeWithText("new line").assertExists()
        onNodeWithText("+").assertExists()
    }

    @Test
    fun fileHeaderLinesAreHiddenButHunksRemain() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = DiffText(
                            lines = listOf(
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "diff --git a/README.md b/README.md"),
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "index 1111111..2222222 100644"),
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "--- a/README.md"),
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "+++ b/README.md"),
                                DiffLine(DiffLineKind.HUNK_HEADER, null, null, "@@ -1 +1 @@"),
                                DiffLine(DiffLineKind.ADD, null, 1, "new line"),
                            ),
                            binary = false,
                            truncated = false,
                        ),
                        loading = false,
                        error = null,
                        onLoadFull = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        onNodeWithText("diff --git", substring = true).assertDoesNotExist()
        onNodeWithText("index 1111111", substring = true).assertDoesNotExist()
        onNodeWithText("--- a/README.md").assertDoesNotExist()
        onNodeWithText("+++ b/README.md").assertDoesNotExist()
        onNodeWithText("@@ -1 +1 @@").assertExists()
        onNodeWithText("new line").assertExists()
        saveScreenshot("13-diff-without-file-header.png")
    }

    @Test
    fun headerOnlyDiffShowsNoTextualChanges() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = DiffText(
                            lines = listOf(
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "diff --git a/a b/a"),
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "old mode 100644"),
                                DiffLine(DiffLineKind.FILE_HEADER, null, null, "new mode 100755"),
                            ),
                            binary = false,
                            truncated = false,
                        ),
                        loading = false,
                        error = null,
                        onLoadFull = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        onNodeWithTag(UiTestTags.DIFF_EMPTY).assertExists()
        onNodeWithText("No textual changes").assertExists()
    }

    @Test
    fun diffSelectionCopiesRawContentWithoutGutterOrMarkers() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val selectionState = SelectionState()
            setContent {
                CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                    MaterialTheme {
                        DiffView(
                            diff = DiffText(
                                lines = listOf(
                                    DiffLine(DiffLineKind.HUNK_HEADER, null, null, "@@ -1,2 +1,2 @@"),
                                    DiffLine(DiffLineKind.DEL, 1, null, "old line"),
                                    DiffLine(DiffLineKind.ADD, null, 1, "new line"),
                                ),
                                binary = false,
                                truncated = false,
                            ),
                            loading = false,
                            error = null,
                            onLoadFull = {},
                            selectionState = selectionState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            waitForIdle()
            runOnIdle { selectionState.selectAll() }
            waitForIdle()

            runOnIdle {
                assertEquals(
                    listOf("@@ -1,2 +1,2 @@", "old line", "new line"),
                    selectionState.getSelectableTexts().map { it.text },
                )
                assertEquals(
                    "@@ -1,2 +1,2 @@\nold line\nnew line",
                    selectionState.selectedTexts.joinToString("\n") { it.text },
                )
            }
            saveScreenshot("32-diff-selection.png")
        }

    @Test
    fun diffContextMenuCopyAllCopiesEveryLoadedLineIncludingOffscreen() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            val lines = buildList {
                add(DiffLine(DiffLineKind.HUNK_HEADER, null, null, "@@ -1,200 +1,200 @@"))
                repeat(200) { index ->
                    add(DiffLine(DiffLineKind.ADD, null, index + 1, "line ${index + 1}"))
                }
            }
            val clipboard = RecordingClipboard()
            var copyLabel = ""
            setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    AppTheme(themeMode = ThemeMode.DARK) {
                        copyLabel = LocalLocalization.current.copy
                        DiffView(
                            diff = DiffText(lines = lines, binary = false, truncated = false),
                            loading = false,
                            error = null,
                            onLoadFull = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag(UiTestTags.diffLine(null, 1)).performMouseInput { rightClick(center) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.CONTEXT_MENU).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(UiTestTags.contextMenuItem(copyLabel)).assertExists()
            onNodeWithTag(UiTestTags.contextMenuItem("Copy all")).assertExists()
            saveScreenshot("33-diff-context-menu.png")

            onNodeWithTag(UiTestTags.contextMenuItem("Copy all")).performClick()
            waitForIdle()

            val copiedLines = checkNotNull(clipboard.text).split("\n")
            assertEquals(201, copiedLines.size)
            assertEquals("@@ -1,200 +1,200 @@", copiedLines.first())
            assertEquals("line 1", copiedLines[1])
            assertEquals("line 200", copiedLines.last())
            assertTrue(copiedLines.none { it.startsWith("+") })
        }

    @Test
    fun diffErrorKeepsGitStderrSelectable() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = null,
                        loading = false,
                        error = UserMessage.Error(
                            "Diff failed.",
                            "fatal: bad revision 'missing'",
                        ),
                        onLoadFull = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        onNodeWithTag(UiTestTags.DIFF_ERROR).assertExists()
        onNodeWithTag(UiTestTags.ERROR_STDERR).assertTextContains("fatal: bad revision 'missing'")
    }

    private class RecordingClipboard : ClipboardManager {
        var text: String? = null

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }

        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }
}
