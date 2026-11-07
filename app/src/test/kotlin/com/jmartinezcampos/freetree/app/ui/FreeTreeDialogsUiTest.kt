package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FreeTreeDialogsUiTest {

    @Test
    fun discardAllDialogUsesTheSharedModal() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 130, commitCount = 6)
        val fixture = uiFixture(commits = commits)
        val controller = AppDialogController()
        try {
            setContent { App(fixture.manager, dialogController = controller) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            controller.show(
                AppDialog.Confirm(
                    tag = UiTestTags.DISCARD_ALL_DIALOG,
                    title = "Discard unstaged changes",
                    message = "Discard unstaged changes in 4 file(s)?",
                    warning = "Tracked files will be restored from the index. Untracked files will be deleted from disk.",
                    confirmLabel = "Discard",
                    destructive = true,
                    onConfirm = {},
                ),
            )
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.DISCARD_ALL_DIALOG).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("29-discard-all-dialog.png")
            onNodeWithTag(UiTestTags.DISCARD_ALL_CANCEL).performClick()
            onNodeWithTag(UiTestTags.DISCARD_ALL_DIALOG).assertDoesNotExist()
        } finally {
            fixture.close()
        }
    }
}
