@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.smoke

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenario
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal class SmokeApp(
    val manager: SessionManager,
    val firstPath: Path,
    private val backends: Map<Path, FakeGitBackend>,
) : AutoCloseable {
    fun backend(path: Path = firstPath): FakeGitBackend =
        backends.getValue(path.toAbsolutePath().normalize())

    override fun close() = manager.close()
}

internal fun smokeApp(
    vararg repos: Pair<String, FakeRepoScenario>,
    statefulWrites: Boolean = false,
): SmokeApp {
    val paths = repos.associate { (name, scenario) ->
        val path = Path.of("/repo", name).toAbsolutePath().normalize()
        path to scenario.backend(statefulWrites = statefulWrites)
    }
    val watchers = paths.keys.associateWith { FakeWatcher() }
    val manager = SessionManager(
        backendFactory = { path -> paths.getValue(path.toAbsolutePath().normalize()) },
        watcherFactory = { path -> watchers.getValue(path.toAbsolutePath().normalize()) },
        persistence = InMemorySessionPersistence(),
        restore = false,
    )
    val firstPath = paths.keys.first()
    manager.openRepo(firstPath)
    return SmokeApp(manager, firstPath, paths)
}

internal fun DesktopComposeUiTest.saveScreenshot(name: String) {
    waitForIdle()
    val directory = screenshotDirectory()
    Files.createDirectories(directory)
    val target = directory.resolve(name)
    ImageIO.write(captureToImage().toAwtImage(), "png", target.toFile())
    println("FreeTree screenshot: $target (${Files.size(target)} bytes)")
}

private fun screenshotDirectory(): Path {
    val working = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    val project = if (working.fileName?.toString() == "app") working else working.resolve("app")
    return project.resolve("build/screenshots")
}
