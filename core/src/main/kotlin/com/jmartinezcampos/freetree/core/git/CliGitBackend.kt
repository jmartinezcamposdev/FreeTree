package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitException
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class CliGitBackend private constructor(
    private val runner: GitRunner,
    private val dirs: GitDirs,
) : GitBackend {
    private val emptyTree = AtomicReference<String?>(null)

    override suspend fun refs(): List<GitRef> = GitParsers.parseRefs(requireText(REFS_COMMAND))

    override suspend fun head(): HeadInfo {
        val commit = runner.run(HEAD_COMMIT_COMMAND)
        val branch = runner.run(HEAD_BRANCH_COMMAND)
        return HeadInfo(
            commit = headValue(commit),
            branchShortName = headValue(branch),
        )
    }

    private fun headValue(result: GitProcessResult): String? = when (result.exitCode) {
        0 -> result.stdoutText().trim().ifBlank { null }
        1 -> null
        else -> throw GitException(result.exitCode, result.stderr)
    }

    override fun logStream(spec: LogSpec): Flow<RawCommit> = channelFlow {
        val revs = mutableListOf<String>()
        if (spec.includeHead && head().commit != null) revs += "HEAD"
        revs += spec.revs
        val distinctRevs = revs.distinct()
        if (distinctRevs.isEmpty()) return@channelFlow

        val args = mutableListOf("log", "--stdin", "-z", "--topo-order", "--format=$LOG_FORMAT")
        spec.author?.let { args += "--author=$it" }
        spec.grep?.let { args += "--grep=$it" }
        spec.since?.let { args += "--since=$it" }
        spec.until?.let { args += "--until=$it" }
        spec.path?.let {
            args += "--"
            args += it
        }

        val stdin = (distinctRevs.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        val result = runner.streamRecords(args, stdin = stdin) { record ->
            GitParsers.parseLogRecord(record)?.let { send(it) }
        }
        if (!result.succeeded) throw GitException(result.exitCode, result.stderr)
    }

    override suspend fun commitDetail(hash: String): CommitDetail {
        val output = requireText(listOf("log", "-1", "-z", "--format=$DETAIL_FORMAT", hash, "--"))
        return GitParsers.parseCommitDetail(output)
            ?: throw GitException(0, "Malformed commit detail for $hash")
    }

    override suspend fun changedFiles(hash: String): List<FileChange> {
        val base = firstParent(hash) ?: emptyTreeHash()
        val output = requireText(
            listOf("diff-tree", "-r", "-z", "--name-status", "-M", base, hash, "--"),
        )
        return GitParsers.parseNameStatus(output)
    }

    override suspend fun diff(target: DiffTarget, unlimited: Boolean): DiffText {
        val maxBytes = if (unlimited) Long.MAX_VALUE else MAX_DIFF_BYTES
        return when (target) {
            is DiffTarget.InCommit -> {
                val base = firstParent(target.hash) ?: emptyTreeHash()
                val args = mutableListOf(
                    "diff",
                    "--no-color",
                    "--no-ext-diff",
                    "-M",
                    base,
                    target.hash,
                    "--",
                    target.change.path,
                )
                target.change.oldPath?.let(args::add)
                runDiff(args, maxBytes)
            }
            is DiffTarget.Unstaged -> {
                val entry = statusEntry(target.path)
                if (entry?.kind == StatusKind.UNTRACKED) {
                    readUntracked(target.path, maxBytes)
                } else {
                    val args = mutableListOf("diff", "--no-color", "--no-ext-diff", "--", target.path)
                    entry?.origPath?.let(args::add)
                    runDiff(args, maxBytes)
                }
            }
            is DiffTarget.Staged -> {
                val entry = statusEntry(target.path)
                val args = mutableListOf("diff", "--no-color", "--no-ext-diff", "--cached", "--", target.path)
                entry?.origPath?.let(args::add)
                runDiff(args, maxBytes)
            }
        }
    }

    override suspend fun status(): WorkingStatus = GitParsers.parseStatus(requireText(STATUS_COMMAND))

    override suspend fun stage(paths: List<String>): GitResult {
        if (paths.isEmpty()) return noPathsFailure()
        val status = try {
            status()
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        val expanded = expandStagePaths(paths, status.entries)
        return runWrite(listOf("add", "--") + expanded, GitCommandType.LOCAL_WRITE)
    }

    override suspend fun unstage(paths: List<String>): GitResult {
        if (paths.isEmpty()) return noPathsFailure()
        val status = try {
            status()
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        val expanded = expandRenamePaths(paths, status.entries)
        val unborn = try {
            head().commit == null
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        val args = if (unborn) {
            rmCachedArgs(expanded)
        } else {
            listOf("restore", "--staged", "--") + expanded
        }
        return runWrite(args, GitCommandType.LOCAL_WRITE)
    }

    override suspend fun discardStaged(paths: List<String>): GitResult {
        if (paths.isEmpty()) return noPathsFailure()
        val status = try {
            status()
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        conflictFailure(paths, status.entries)?.let { return it }

        val candidates = expandRenamePaths(paths, status.entries)
        val inHead = try {
            if (head().commit == null) emptySet() else headPaths(candidates)
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        val restorePaths = candidates.filter { it in inHead }
        val removePaths = candidates.filter { it !in inHead }

        if (restorePaths.isNotEmpty()) {
            val restore = runWrite(
                listOf("restore", "--staged", "--source=HEAD", "--") + restorePaths,
                GitCommandType.LOCAL_WRITE,
            )
            if (restore is GitResult.Failure) return restore
        }
        if (removePaths.isNotEmpty()) {
            val remove = runWrite(rmCachedArgs(removePaths), GitCommandType.LOCAL_WRITE)
            if (remove is GitResult.Failure) return remove
        }
        return GitResult.Ok("")
    }

    override suspend fun discardUnstaged(paths: List<String>): GitResult {
        if (paths.isEmpty()) return noPathsFailure()
        val status = try {
            status()
        } catch (e: GitException) {
            return GitResult.Failure(e.exitCode, e.stderr)
        }
        conflictFailure(paths, status.entries)?.let { return it }

        val entriesByPath = status.entries.associateBy { it.path }
        val untracked = paths.filter { entriesByPath[it]?.kind == StatusKind.UNTRACKED }.toSet()
        val tracked = paths.filterNot { it in untracked }

        if (tracked.isNotEmpty()) {
            val restore = runWrite(
                listOf("restore", "--worktree", "--") + tracked,
                GitCommandType.LOCAL_WRITE,
            )
            if (restore is GitResult.Failure) return restore
        }
        for (path in untracked) {
            val file = resolveWorkTreePath(path) ?: return GitResult.Failure(1, "Path outside work tree: $path")
            try {
                Files.deleteIfExists(file)
            } catch (e: IOException) {
                return GitResult.Failure(1, "Could not delete $path: ${e.message}")
            }
        }
        return GitResult.Ok("")
    }

    override suspend fun commit(message: String): GitResult {
        if (message.isBlank()) return GitResult.Failure(1, "Commit message is empty.")
        val staged = runner.run(listOf("diff", "--cached", "--quiet"))
        when {
            staged.timedOut -> return GitResult.Failure(-1, staged.stderr)
            staged.exitCode == 0 -> return GitResult.Failure(1, "Nothing staged to commit.")
            staged.exitCode != 1 -> return GitResult.Failure(staged.exitCode, staged.stderr)
        }
        return runWrite(
            listOf("commit", "-F", "-"),
            GitCommandType.LOCAL_WRITE,
            stdin = message.toByteArray(StandardCharsets.UTF_8),
        )
    }

    override suspend fun push(): GitResult = runWrite(listOf("push"), GitCommandType.NETWORK)

    override suspend fun pull(): GitResult = runWrite(
        listOf("pull", "--no-edit"),
        GitCommandType.NETWORK,
        environment = mapOf("GIT_MERGE_AUTOEDIT" to "no", "GIT_EDITOR" to "true"),
    )

    override suspend fun gitDirs(): GitDirs = readGitDirs(runner, dirs.workTree)

    private suspend fun firstParent(hash: String): String? =
        requireText(listOf("log", "-1", "--format=%P", hash, "--"))
            .trim()
            .split(' ')
            .firstOrNull { it.isNotEmpty() }

    private suspend fun emptyTreeHash(): String {
        emptyTree.get()?.let { return it }
        val hash = requireText(
            listOf("hash-object", "-t", "tree", "--stdin"),
            stdin = ByteArray(0),
        ).trim()
        emptyTree.compareAndSet(null, hash)
        return emptyTree.get() ?: hash
    }

    private suspend fun statusEntry(path: String): StatusEntry? =
        status().entries.firstOrNull { it.path == path }

    private suspend fun headPaths(candidates: List<String>): Set<String> {
        val output = requireText(
            listOf("ls-tree", "-r", "-z", "--name-only", "HEAD", "--") + candidates,
        )
        return output.split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    private fun rmCachedArgs(paths: List<String>): List<String> {
        // --cached is index-only; -f bypasses git rm's work-tree safety check.
        return listOf("rm", "--cached", "-f", "--") + paths
    }

    private fun expandRenamePaths(paths: List<String>, entries: List<StatusEntry>): List<String> {
        val expanded = LinkedHashSet<String>()
        for (path in paths) {
            expanded += path
            entries.firstOrNull { it.kind == StatusKind.RENAMED && it.path == path }
                ?.origPath
                ?.let(expanded::add)
        }
        return expanded.toList()
    }

    private fun expandStagePaths(paths: List<String>, entries: List<StatusEntry>): List<String> {
        val expanded = LinkedHashSet<String>()
        for (path in paths) {
            expanded += path
            val original = entries.firstOrNull { it.kind == StatusKind.RENAMED && it.path == path }?.origPath
            if (original != null && Files.exists(dirs.workTree.resolve(original), LinkOption.NOFOLLOW_LINKS)) {
                expanded += original
            }
        }
        return expanded.toList()
    }

    private fun conflictFailure(paths: List<String>, entries: List<StatusEntry>): GitResult.Failure? {
        val unmerged = entries.filter { it.kind == StatusKind.UNMERGED && it.path in paths }
        if (unmerged.isEmpty()) return null
        return GitResult.Failure(1, "Cannot discard unmerged path: ${unmerged.joinToString { it.path }}")
    }

    private suspend fun runDiff(args: List<String>, maxBytes: Long): DiffText {
        val result = runner.run(args, GitCommandType.READ, maxStdoutBytes = maxBytes)
        if (result.truncated) {
            return GitParsers.parseDiff(result.stdoutText()).copy(truncated = true)
        }
        if (!result.succeeded) throw GitException(result.exitCode, result.stderr)
        return GitParsers.parseDiff(result.stdoutText())
    }

    private fun readUntracked(path: String, maxBytes: Long): DiffText {
        val file = resolveWorkTreePath(path) ?: throw GitException(1, "Path outside work tree: $path")
        if (Files.isSymbolicLink(file)) {
            val target = try {
                Files.readSymbolicLink(file)
            } catch (e: IOException) {
                throw GitException(1, "Could not read symbolic link $path: ${e.message}")
            }
            return DiffText(
                lines = listOf(DiffLine(DiffLineKind.NOTE, null, null, "Symbolic link to $target")),
                binary = false,
                truncated = false,
            )
        }
        val size = try {
            Files.size(file)
        } catch (e: IOException) {
            throw GitException(1, "Could not read $path: ${e.message}")
        }
        val truncated = maxBytes != Long.MAX_VALUE && size > maxBytes
        val limit = if (truncated) maxBytes else size
        if (limit > Int.MAX_VALUE) {
            throw GitException(1, "File is too large to display: $path")
        }
        val bytes = try {
            Files.newInputStream(file).use { it.readNBytes(limit.toInt()) }
        } catch (e: IOException) {
            throw GitException(1, "Could not read $path: ${e.message}")
        }
        val binary = bytes.asSequence().take(8000).any { it == 0.toByte() }
        if (binary) {
            return DiffText(
                lines = listOf(DiffLine(DiffLineKind.NOTE, null, null, "Binary file")),
                binary = true,
                truncated = truncated,
            )
        }
        return DiffText(
            lines = GitParsers.addedTextDiff(bytes.toString(StandardCharsets.UTF_8)),
            binary = false,
            truncated = truncated,
        )
    }

    private fun resolveWorkTreePath(path: String): Path? {
        val resolved = dirs.workTree.resolve(path).normalize()
        return if (resolved.startsWith(dirs.workTree)) resolved else null
    }

    private suspend fun requireText(
        args: List<String>,
        type: GitCommandType = GitCommandType.READ,
        stdin: ByteArray? = null,
    ): String {
        val result = runner.run(args, type, stdin)
        if (!result.succeeded) throw GitException(result.exitCode, result.stderr)
        return result.stdoutText()
    }

    private suspend fun runWrite(
        args: List<String>,
        type: GitCommandType,
        stdin: ByteArray? = null,
        environment: Map<String, String> = emptyMap(),
    ): GitResult {
        val result = runner.run(args, type, stdin, environment = environment)
        return if (result.succeeded) {
            GitResult.Ok(result.stdoutText())
        } else {
            GitResult.Failure(result.exitCode, result.stderr)
        }
    }

    private fun noPathsFailure(): GitResult.Failure =
        GitResult.Failure(1, "No paths specified.")

    companion object {
        internal const val MAX_DIFF_BYTES: Long = 2L * 1024 * 1024

        private const val LOG_FORMAT = "%H%x1f%P%x1f%an%x1f%at%x1f%s"
        private const val DETAIL_FORMAT = "%H%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ct%x1f%B"

        private val REFS_COMMAND = listOf(
            "for-each-ref",
            "--format=%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00" +
                "%(refname)%00%(HEAD)%00%(upstream:short)%00%(symref)",
            "refs/heads",
            "refs/remotes",
            "refs/tags",
        )
        private val HEAD_COMMIT_COMMAND = listOf("rev-parse", "--verify", "-q", "HEAD")
        private val HEAD_BRANCH_COMMAND = listOf("symbolic-ref", "-q", "--short", "HEAD")
        private val STATUS_COMMAND = listOf(
            "status",
            "--porcelain=v2",
            "-z",
            "--branch",
            "--untracked-files=all",
        )

        suspend fun open(path: Path, executable: GitExecutable = GitDiscovery.discover()): CliGitBackend {
            val requested = path.toAbsolutePath().normalize()
            val probe = GitRunner(requested, executable)
            val dirs = readGitDirs(probe, requested)
            return CliGitBackend(GitRunner(dirs.workTree, executable), dirs)
        }

        private suspend fun readGitDirs(runner: GitRunner, workingDirectory: Path): GitDirs {
            val result = runner.run(
                listOf("rev-parse", "--show-toplevel", "--absolute-git-dir", "--git-common-dir"),
            )
            if (!result.succeeded) throw GitException(result.exitCode, result.stderr)
            val lines = result.stdoutText().lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size < 3) {
                throw GitException(0, "Unexpected git rev-parse output: ${result.stdoutText()}")
            }
            val workTree = Path.of(lines[0]).toAbsolutePath().normalize()
            val gitDir = Path.of(lines[1]).toAbsolutePath().normalize()
            val commonValue = Path.of(lines[2])
            val commonDir = if (commonValue.isAbsolute) {
                commonValue.normalize()
            } else {
                workingDirectory.resolve(commonValue).normalize()
            }
            return GitDirs(workTree = workTree, gitDir = gitDir, commonDir = commonDir)
        }
    }
}
