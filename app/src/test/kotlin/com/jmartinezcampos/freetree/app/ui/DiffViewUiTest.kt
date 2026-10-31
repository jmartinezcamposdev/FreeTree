package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.session.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
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
        onNodeWithText("+new line").assertExists()
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
        onNodeWithText("+new line").assertExists()
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
}
