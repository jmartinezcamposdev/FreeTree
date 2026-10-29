package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.model.GitDirs
import java.nio.file.Path

object EventClassifier {
    fun classify(eventPath: Path, dirs: GitDirs): WatchEvent? {
        val path = eventPath.toAbsolutePath().normalize()
        val fileName = path.fileName?.toString().orEmpty()
        if (fileName.endsWith(".lock")) return null

        val commonDir = dirs.commonDir.toAbsolutePath().normalize()
        val gitDir = dirs.gitDir.toAbsolutePath().normalize()
        val workTree = dirs.workTree.toAbsolutePath().normalize()

        if (path.startsWith(commonDir.resolve("refs"))) return WatchEvent.REFS_CHANGED
        if (path == commonDir.resolve("packed-refs")) return WatchEvent.REFS_CHANGED

        if (path.startsWith(gitDir)) {
            return when {
                path == gitDir.resolve("HEAD") -> WatchEvent.REFS_CHANGED
                path == gitDir.resolve("index") -> WatchEvent.STATUS_CHANGED
                OPERATION_FILES.any { path == gitDir.resolve(it) } -> WatchEvent.STATUS_CHANGED
                path.startsWith(gitDir.resolve("rebase-merge")) -> WatchEvent.STATUS_CHANGED
                path.startsWith(gitDir.resolve("rebase-apply")) -> WatchEvent.STATUS_CHANGED
                else -> null
            }
        }

        if (path.startsWith(workTree)) {
            val relative = workTree.relativize(path)
            if (relative.any { it.toString() == ".git" }) return null
            return WatchEvent.STATUS_CHANGED
        }
        return null
    }

    private val OPERATION_FILES = listOf(
        "MERGE_HEAD",
        "REBASE_HEAD",
        "CHERRY_PICK_HEAD",
        "REVERT_HEAD",
    )
}
