package com.jmartinezcampos.freetree.core.git

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class GitVersion(val major: Int, val minor: Int) : Comparable<GitVersion> {
    override fun compareTo(other: GitVersion): Int =
        compareValuesBy(this, other, GitVersion::major, GitVersion::minor)

    override fun toString(): String = "$major.$minor"
}

data class GitExecutable(val path: Path, val version: GitVersion)

class GitDiscoveryException(message: String) : Exception(message)

object GitDiscovery {
    val minimumVersion: GitVersion = GitVersion(2, 30)

    fun discover(env: Map<String, String> = System.getenv()): GitExecutable =
        discover(env, commonLocations())

    internal fun discover(
        env: Map<String, String>,
        commonLocations: List<Path>,
        pathSeparator: String = File.pathSeparator,
    ): GitExecutable {
        val failures = mutableListOf<String>()
        for (candidate in candidates(env, commonLocations, pathSeparator)) {
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) continue
            val version = readVersion(candidate)
            if (version == null) {
                failures += "$candidate did not report a version"
                continue
            }
            if (version < minimumVersion) {
                failures += "$candidate is Git $version (requires $minimumVersion or newer)"
                continue
            }
            return GitExecutable(candidate.toAbsolutePath().normalize(), version)
        }
        val details = if (failures.isEmpty()) "" else ": " + failures.joinToString("; ")
        throw GitDiscoveryException(
            "Git $minimumVersion or newer was not found on PATH, in common locations, or via FREETREE_GIT$details",
        )
    }

    fun parseVersion(output: String): GitVersion? {
        val match = Regex("""git version (\d+)\.(\d+)""").find(output.trim()) ?: return null
        return GitVersion(match.groupValues[1].toIntOrNull() ?: return null, match.groupValues[2].toIntOrNull() ?: return null)
    }

    private fun candidates(
        env: Map<String, String>,
        commonLocations: List<Path>,
        pathSeparator: String,
    ): List<Path> {
        val executableName = if (isWindows()) "git.exe" else "git"
        val result = mutableListOf<Path>()

        env["PATH"].orEmpty()
            .split(pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { result.add(Path.of(it).resolve(executableName)) }

        result.addAll(commonLocations)
        env["FREETREE_GIT"]?.takeIf { it.isNotBlank() }?.let { configured ->
            val path = Path.of(configured)
            result.add(if (Files.isDirectory(path)) path.resolve(executableName) else path)
        }
        return result.distinct()
    }

    private fun commonLocations(): List<Path> {
        val locations = mutableListOf<Path>()
        if (isWindows()) {
            locations.add(Path.of("C:\\Program Files\\Git\\cmd\\git.exe"))
            locations.add(Path.of("C:\\Program Files (x86)\\Git\\cmd\\git.exe"))
            System.getenv("LOCALAPPDATA")?.let {
                locations.add(Path.of(it, "Programs", "Git", "cmd", "git.exe"))
            }
        }
        locations.add(Path.of("/usr/bin/git"))
        locations.add(Path.of("/usr/local/bin/git"))
        locations.add(Path.of("/opt/homebrew/bin/git"))
        locations.add(Path.of("/opt/local/bin/git"))
        locations.add(Path.of("/snap/bin/git"))
        return locations
    }

    private fun readVersion(path: Path): GitVersion? {
        val process = try {
            ProcessBuilder(path.toString(), "--version")
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            return null
        }
        return try {
            process.outputStream.close()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                parseVersion(process.inputStream.readBytes().toString(StandardCharsets.UTF_8))
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")
}
