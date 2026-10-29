package com.jmartinezcampos.freetree.core.hardening

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitDiscovery
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator

internal val hardeningGit by lazy { GitDiscovery.discover() }

internal suspend fun GitTestRepo.backend(): CliGitBackend =
    CliGitBackend.open(workTree, hardeningGit)

internal fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")

/**
 * Permission tests only make sense for an unprivileged user; root ignores mode bits.
 */
internal fun isPrivilegedUser(): Boolean =
    System.getProperty("user.name") == "root" ||
        System.getenv("USER") == "root"

internal fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

internal fun makeExecutable(path: Path) {
    Files.setPosixFilePermissions(
        path,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
    )
}

/**
 * Creates a POSIX shell wrapper around the real Git. The supplied [body] sees the original
 * arguments and can delegate with [realGit], abort, or corrupt output; the wrapper receives the
 * documented `--no-pager -c core.quotepath=off` prefix as its first arguments.
 */
internal fun fakeGitScript(directory: Path, body: String): Path {
    val script = directory.resolve("fake-git")
    Files.writeString(
        script,
        buildString {
            appendLine("#!/bin/sh")
            appendLine("REAL_GIT=\"${hardeningGit.path}\"")
            appendLine("real_git() { \"\$REAL_GIT\" \"\$@\"; }")
            appendLine(body)
        },
    )
    makeExecutable(script)
    return script
}
