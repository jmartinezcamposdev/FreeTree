package com.jmartinezcampos.freetree.core.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class GitDiscoveryTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach(::deleteRecursively)
    }

    @Test
    fun parsesCommonVersionStrings() {
        assertEquals(GitVersion(2, 39), GitDiscovery.parseVersion("git version 2.39.3 (Apple Git-145)\n"))
        assertEquals(GitVersion(2, 30), GitDiscovery.parseVersion("git version 2.30.0.windows.1\n"))
        assertEquals(GitVersion(3, 1), GitDiscovery.parseVersion("git version 3.1.0"))
        assertEquals(null, GitDiscovery.parseVersion("not git"))
    }

    @Test
    fun rejectsGitOlderThanMinimum() {
        assumeUnix()
        val old = fakeGit("2.29.4")

        val error = assertFailsWith<GitDiscoveryException> {
            GitDiscovery.discover(
                env = mapOf("PATH" to old.parent.toString()),
                commonLocations = emptyList(),
                pathSeparator = ":",
            )
        }

        assertTrue(error.message.orEmpty().contains("2.30"), error.message)
        assertTrue(error.message.orEmpty().contains("2.29"), error.message)
    }

    @Test
    fun searchesPathBeforeCommonLocationsAndFreetreeGit() {
        assumeUnix()
        val pathGit = fakeGit("2.31.0")
        val commonGit = fakeGit("2.40.0")
        val configuredGit = fakeGit("2.50.0")

        val found = GitDiscovery.discover(
            env = mapOf("PATH" to pathGit.parent.toString(), "FREETREE_GIT" to configuredGit.toString()),
            commonLocations = listOf(commonGit),
            pathSeparator = ":",
        )

        assertEquals(pathGit.toAbsolutePath().normalize(), found.path)
        assertEquals(GitVersion(2, 31), found.version)
    }

    @Test
    fun fallsThroughAnOldPathCandidateToANewerFreetreeGit() {
        assumeUnix()
        val old = fakeGit("2.28.0")
        val newer = fakeGit("2.41.0")

        val found = GitDiscovery.discover(
            env = mapOf("PATH" to old.parent.toString(), "FREETREE_GIT" to newer.toString()),
            commonLocations = emptyList(),
            pathSeparator = ":",
        )

        assertEquals(newer.toAbsolutePath().normalize(), found.path)
        assertEquals(GitVersion(2, 41), found.version)
    }

    @Test
    fun acceptsAFreetreeGitDirectory() {
        assumeUnix()
        val git = fakeGit("2.35.1")

        val found = GitDiscovery.discover(
            env = mapOf("PATH" to "", "FREETREE_GIT" to git.parent.toString()),
            commonLocations = emptyList(),
            pathSeparator = ":",
        )

        assertEquals(git.toAbsolutePath().normalize(), found.path)
    }

    @Test
    fun missingGitProducesAClearError() {
        assumeUnix()

        val error = assertFailsWith<GitDiscoveryException> {
            GitDiscovery.discover(
                env = mapOf("PATH" to ""),
                commonLocations = emptyList(),
                pathSeparator = ":",
            )
        }

        assertTrue(error.message.orEmpty().contains("2.30"), error.message)
    }

    @Test
    fun discoversTheSystemGit() {
        val git = GitDiscovery.discover()

        assertTrue(Files.isExecutable(git.path), git.path.toString())
        assertTrue(git.version >= GitDiscovery.minimumVersion, git.version.toString())
    }

    private fun fakeGit(version: String): Path {
        val directory = Files.createTempDirectory("freetree-discovery-")
        temporaryDirectories.add(directory)
        val file = directory.resolve("git")
        Files.writeString(file, "#!/bin/sh\necho 'git version $version'\n")
        Files.setPosixFilePermissions(
            file,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return file
    }

    private fun assumeUnix() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
