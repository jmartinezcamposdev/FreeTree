package com.jmartinezcampos.freetree.app.hardening

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.ui.DiffView
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.LocalGraphColors
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/11: a line longer than 2,000 characters is truncated on screen only.
 */
@OptIn(ExperimentalTestApi::class)
class DiffLineTruncationTest {

    @Test
    fun veryLongDiffLinesAreTruncatedOnScreen() = runDesktopComposeUiTest(width = 800, height = 600) {
        val longLine = "x".repeat(5_000)
        setContent {
            CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                MaterialTheme {
                    DiffView(
                        diff = DiffText(
                            lines = listOf(DiffLine(DiffLineKind.ADD, null, 1, longLine)),
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

        val truncated = "x".repeat(GraphStyle.MAX_DIFF_LINE_CHARS) + "…"
        onNodeWithText(truncated).assertExists()
        onNodeWithText("+").assertExists()
        assertTrue(onAllNodesWithText(longLine).fetchSemanticsNodes().isEmpty())
    }
}
