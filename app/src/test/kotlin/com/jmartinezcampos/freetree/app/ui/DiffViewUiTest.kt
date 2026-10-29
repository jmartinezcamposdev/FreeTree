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
