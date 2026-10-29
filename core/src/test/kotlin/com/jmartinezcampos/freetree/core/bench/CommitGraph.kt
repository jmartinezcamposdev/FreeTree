package com.jmartinezcampos.freetree.core.bench

import java.nio.file.Files
import java.nio.file.Path

/**
 * `git commit-graph` is a documented recommendation, not something the app may run automatically.
 * The bench measures the canonical cache without it, then checks the same paths with one present so
 * the effect on the topo-order first-row latency is recorded. The graph file is removed afterwards.
 */
internal object CommitGraph {
    fun path(repo: Path): Path {
        val result = RawGit.run(repo, listOf("rev-parse", "--git-path", "objects/info/commit-graph"))
        check(result.succeeded) { "git rev-parse --git-path failed: ${result.stderr}" }
        return repo.resolve(result.stdoutText().trim()).normalize()
    }

    fun isPresent(repo: Path): Boolean = Files.isRegularFile(path(repo))

    fun remove(repo: Path) {
        val graphFile = path(repo)
        Files.deleteIfExists(graphFile)
        Files.deleteIfExists(graphFile.parent.resolve("commit-graphs"))
    }

    fun write(repo: Path) {
        val result = RawGit.run(repo, listOf("commit-graph", "write", "--reachable"))
        check(result.succeeded) { "git commit-graph write failed: ${result.stderr}" }
    }
}
