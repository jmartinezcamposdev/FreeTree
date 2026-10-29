package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SmokeInfraTest {
    @Test
    fun changesRowCountIsVisible() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val fixture = uiFixture(
            commits = listOf(RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root")),
            status = WorkingStatus(
                null,
                null,
                0,
                0,
                false,
                listOf(
                    StatusEntry("a.kt", null, 'M', ' ', StatusKind.ORDINARY),
                    StatusEntry("b.kt", null, ' ', 'M', StatusKind.ORDINARY),
                ),
            ),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            onNodeWithTag(UiTestTags.CHANGES_ROW).assertTextContains("Changes (2)")
        } finally {
            fixture.close()
        }
    }
}
