@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.polish

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import com.jmartinezcampos.freetree.app.ui.UiFixture
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.app.ui.uiFixture
import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.RandomDag
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

internal val POLISH_TIP_HASH = "c".repeat(40)
internal val POLISH_ROOT_HASH = "d".repeat(40)

/**
 * Graph-only repository with enough commits (and a viewport small enough) that the history
 * scrollbar has a real travel range.
 */
internal fun polishGraphFixture(
    commitCount: Int = 80,
    seed: Long = 91,
): UiFixture {
    val commits = RandomDag.generate(seed = seed, commitCount = commitCount)
    return uiFixture(
        commits = commits,
        refs = listOf(
            GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
        ),
        head = HeadInfo(commits[0].hash, "main"),
        path = "/repo/polish-graph",
    )
}

internal class PolishCommitScenario(
    val fixture: UiFixture,
    val tip: RawCommit,
    val files: List<FileChange>,
) : AutoCloseable {
    override fun close() = fixture.close()
}

/**
 * Commit with [fileCount] changed files and a [diffLineCount]-line diff for the first file, so the
 * changed-files list and the diff panel both overflow their viewports.
 */
internal fun polishCommitScenario(
    fileCount: Int = 60,
    diffLineCount: Int = 200,
): PolishCommitScenario {
    val root = RawCommit(POLISH_ROOT_HASH, emptyList(), "Ada", 1_700_000_000L, "root commit")
    val tip = RawCommit(POLISH_TIP_HASH, listOf(root.hash), "Grace", 1_700_000_060L, "tip commit")
    val files = (0 until fileCount).map { index -> FileChange('M', "file-$index.txt", null) }
    val diff = DiffText(
        lines = (1..diffLineCount).map { number ->
            DiffLine(DiffLineKind.CONTEXT, number, number, "line $number")
        },
        binary = false,
        truncated = false,
    )
    val target = DiffTarget.InCommit(tip.hash, files.first())
    val fixture = uiFixture(
        commits = listOf(tip, root),
        refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null)),
        head = HeadInfo(tip.hash, "main"),
        commitDetails = mapOf(
            tip.hash to CommitDetail(
                hash = tip.hash,
                parents = tip.parents,
                authorName = tip.authorName,
                authorEmail = "grace@example.com",
                authorTimeSec = tip.authorTimeSec,
                committerName = tip.authorName,
                committerTimeSec = tip.authorTimeSec,
                message = "tip commit\n\nBody line used by the polish acceptance test.",
            ),
        ),
        changedFiles = mapOf(tip.hash to files),
        diffs = mapOf(target to diff),
        path = "/repo/polish-commit",
    )
    return PolishCommitScenario(fixture, tip, files)
}

// ---- waiting helpers -------------------------------------------------------------------------

internal fun ComposeUiTest.awaitGraph(fixture: UiFixture) {
    waitUntil(timeoutMillis = 5_000) {
        fixture.manager.activeSession.value?.state?.value?.graph != null
    }
}

internal fun ComposeUiTest.awaitSelection(fixture: UiFixture, selection: Selection) {
    waitUntil(timeoutMillis = 5_000) {
        fixture.manager.activeSession.value?.state?.value?.selection == selection
    }
}

internal fun ComposeUiTest.awaitCommitDetail(fixture: UiFixture, hash: String) {
    waitUntil(timeoutMillis = 5_000) {
        fixture.manager.activeSession.value?.state?.value?.commitDetail?.hash == hash
    }
}

internal fun ComposeUiTest.awaitChangedFiles(fixture: UiFixture, fileCount: Int) {
    waitUntil(timeoutMillis = 5_000) {
        fixture.manager.activeSession.value?.state?.value?.changedFiles?.size == fileCount
    }
}

// ---- graph helpers ---------------------------------------------------------------------------

/** Row indices (0-based commits) whose rows are currently composed by the lazy graph. */
internal fun ComposeUiTest.visibleCommitRows(commitCount: Int): List<Int> =
    (0 until commitCount).filter {
        onAllNodesWithTag(UiTestTags.commitRow(it)).fetchSemanticsNodes().isNotEmpty()
    }

/**
 * Row indices actually visible in the graph viewport, keyed by row index, with their top edge in
 * root Dp. Lazy lists also compose prefetched rows whose semantic bounds can be clipped at the
 * viewport edge, so callers should pick anchors away from the first pixel row.
 */
internal fun ComposeUiTest.commitRowTops(commitCount: Int): Map<Int, androidx.compose.ui.unit.Dp> =
    (0 until commitCount).mapNotNull { row ->
        runCatching {
            row to onNodeWithTag(UiTestTags.commitRow(row)).getUnclippedBoundsInRoot().top
        }.getOrNull()
    }.toMap()

internal fun ComposeUiTest.rowTop(tag: String): Float =
    onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

/** Drags the scrollbar thumb from [fromFraction] to [toFraction] of its height. */
internal fun ComposeUiTest.dragScrollbar(
    tag: String,
    fromFraction: Float = 0.1f,
    toFraction: Float = 0.9f,
) {
    onNodeWithTag(tag).performMouseInput {
        dragAndDrop(
            start = Offset(centerX, height * fromFraction),
            end = Offset(centerX, height * toFraction),
        )
    }
}

/** Wheel-scrolls the area under [tag]; used to wake transient (fade-out) scrollbars. */
internal fun ComposeUiTest.wheelScroll(tag: String, delta: Float = 2f) {
    onNodeWithTag(tag).performMouseInput {
        moveTo(center)
        scroll(delta)
    }
}

// ---- image helpers ---------------------------------------------------------------------------

internal fun ImageBitmap.colorAt(x: Int, y: Int): Color = toPixelMap()[x, y]

internal fun ImageBitmap.distinctColorCount(): Int = toPixelMap().buffer.toSet().size

internal fun ImageBitmap.samePixelsAs(other: ImageBitmap): Boolean =
    width == other.width &&
        height == other.height &&
        toPixelMap().buffer.contentEquals(other.toPixelMap().buffer)

internal fun ImageBitmap.centerColor(): Color = colorAt(width / 2, height / 2)

/** What the modern scrollbar currently paints: whether it is shown and where its thumb is. */
internal class ScrollbarPaint(val visible: Boolean, val thumb: IntRange?)

/**
 * Reads the scrollbar out of the rendered window.
 *
 * The thumb is [GraphStyle.SCROLLBAR_THUMB_WIDTH] wide and the track is wider, so sampling the
 * centre line and a line 4dp off-centre separates thumb rows from track rows in both themes
 * (the thumb is darker than the track on light backgrounds and brighter on dark ones). Using the
 * whole-window capture avoids the coordinate mismatch of per-node captures.
 */
internal fun DesktopComposeUiTest.scrollbarPaint(tag: String): ScrollbarPaint {
    val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    val pixels = captureToImage().toPixelMap()
    val centerX = ((bounds.left + bounds.right) / 2f).toInt()
    // The thumb is half as wide as the gutter and the track is 5/6 as wide, so one third of the
    // gutter sits between them at any display density.
    val probeOffset = ((bounds.right - bounds.left) / 3f).toInt().coerceAtLeast(1)
    val probeX = (centerX + probeOffset).takeIf { it < bounds.right.toInt() - 1 }
        ?: (centerX - probeOffset).coerceAtLeast(bounds.left.toInt())
    val top = bounds.top.toInt() + 2
    val bottom = bounds.bottom.toInt() - 2
    if (bottom <= top) return ScrollbarPaint(visible = false, thumb = null)

    fun luminance(x: Int, y: Int): Float {
        val color = pixels[x, y]
        return 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    }

    val center = (top until bottom).map { luminance(centerX, it) }
    val probe = (top until bottom).map { luminance(probeX, it) }
    val edgeX = bounds.left.toInt()
    // "Visible" means the track is painted at all; compare with the gutter background at the
    // outer edge, which stays unpainted in both themes.
    val visible = center.indices.count {
        abs(center[it] - luminance(edgeX, it + top)) > 0.02f
    } > center.size / 2
    val thumbRows = center.indices.filter { abs(center[it] - probe[it]) > 0.08f }
    val thumb = if (thumbRows.isEmpty()) {
        null
    } else {
        (thumbRows.first() + top)..(thumbRows.last() + top)
    }
    return ScrollbarPaint(visible, thumb)
}

/** Drags the currently painted thumb of [tag] down by [travel] of the bar height. */
internal fun DesktopComposeUiTest.dragPaintedThumb(tag: String, travel: Float = 0.4f) {
    val bar = onNodeWithTag(tag)
    val bounds = bar.fetchSemanticsNode().boundsInRoot
    val paint = scrollbarPaint(tag)
    val thumb = paint.thumb ?: error("scrollbar $tag has no painted thumb to drag")
    val startLocalY = (thumb.first + thumb.last) / 2f - bounds.top
    bar.performMouseInput {
        dragAndDrop(
            start = Offset(centerX, startLocalY),
            end = Offset(centerX, (startLocalY + height * travel).coerceAtMost(height - 1f)),
        )
    }
}

// ---- screenshots -----------------------------------------------------------------------------

/**
 * Captures the whole test window to `app/build/screenshots/polish/<name>` and returns the file.
 * `captureToImage()` is the Compose test API required by the task; the directory is created here
 * so the suite works on a clean checkout. Build output is intentionally not committed.
 */
internal fun DesktopComposeUiTest.savePolishScreenshot(name: String): Path {
    waitForIdle()
    val directory = polishScreenshotDirectory()
    Files.createDirectories(directory)
    val target = directory.resolve(name)
    ImageIO.write(captureToImage().toAwtImage(), "png", target.toFile())
    println("FreeTree polish screenshot: $target (${Files.size(target)} bytes)")
    return target
}

private fun polishScreenshotDirectory(): Path {
    val working = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    val project = if (working.fileName?.toString() == "app") working else working.resolve("app")
    return project.resolve("build/screenshots/polish")
}
