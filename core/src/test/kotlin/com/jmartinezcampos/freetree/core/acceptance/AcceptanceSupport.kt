package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.git.GitDiscovery
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object AcceptanceGit {
    val executable by lazy { GitDiscovery.discover() }
}

internal suspend fun GitTestRepo.openBackend(): CliGitBackend =
    CliGitBackend.open(workTree, AcceptanceGit.executable)

internal data class StatusEntryV1(
    val code: String,
    val path: String,
    val origPath: String?,
)

internal fun GitTestRepo.statusV1(): List<StatusEntryV1> = parseStatusV1(
    git("status", "--porcelain=v1", "-z", "--untracked-files=all").requireSuccess().stdout,
)

internal fun parseStatusV1(raw: String): List<StatusEntryV1> {
    val tokens = raw.split('\u0000')
    val entries = mutableListOf<StatusEntryV1>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.isEmpty()) {
            index++
            continue
        }
        check(token.length >= 3) { "Malformed porcelain v1 entry: <$token>" }
        val code = token.substring(0, 2)
        val path = token.substring(3)
        val origPath = if (code[0] == 'R' || code[0] == 'C') {
            tokens.getOrNull(index + 1) ?: error("Missing original path for <$token>")
        } else {
            null
        }
        entries += StatusEntryV1(code, path, origPath)
        index += if (origPath == null) 1 else 2
    }
    return entries
}

internal fun parseRevListParents(raw: String): List<Pair<String, List<String>>> =
    raw.lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val parts = line.split(' ')
            parts.first() to parts.drop(1)
        }
        .toList()

internal fun parseNameStatus(raw: String): List<FileChange> {
    val tokens = raw.split('\u0000')
    val changes = mutableListOf<FileChange>()
    var index = 0
    while (index < tokens.size) {
        val statusToken = tokens[index]
        if (statusToken.isEmpty()) {
            index++
            continue
        }
        val status = statusToken.first()
        if (status == 'R' || status == 'C') {
            val oldPath = tokens.getOrNull(index + 1) ?: break
            val newPath = tokens.getOrNull(index + 2) ?: break
            changes += FileChange(status, newPath, oldPath)
            index += 3
        } else {
            val path = tokens.getOrNull(index + 1) ?: break
            changes += FileChange(status, path, null)
            index += 2
        }
    }
    return changes
}

internal fun GitTestRepo.showNameStatus(hash: String): List<FileChange> = parseNameStatus(
    git("show", "--name-status", "--format=", "-M", "--first-parent", "-z", hash)
        .requireSuccess()
        .stdout,
)

internal fun GitTestRepo.stagedPaths(): Set<String> = git("diff", "--cached", "--name-only", "-z")
    .requireSuccess()
    .stdout
    .split('\u0000')
    .filter { it.isNotEmpty() }
    .toSet()

internal fun GitTestRepo.refsFromShowRef(): List<GitRef> {
    val symrefs = git(
        "for-each-ref",
        "--format=%(refname) %(symref)",
        "refs/heads",
        "refs/remotes",
        "refs/tags",
    )
        .requireSuccess()
        .stdoutLines()
        .mapNotNull { line ->
            val split = line.indexOf(' ')
            if (split < 0) null else line.substring(0, split).takeIf { line.substring(split + 1).isNotBlank() }
        }
        .toSet()
    val current = currentBranch()

    return git("show-ref")
        .stdoutLines()
        .mapNotNull { line ->
            val split = line.indexOf(' ')
            if (split < 0) return@mapNotNull null
            val name = line.substring(split + 1)
            if (name in symrefs) return@mapNotNull null
            if (!name.startsWith("refs/heads/") &&
                !name.startsWith("refs/remotes/") &&
                !name.startsWith("refs/tags/")
            ) {
                return@mapNotNull null
            }

            val commit = git("rev-parse", "--verify", "$name^{commit}")
            if (!commit.succeeded) return@mapNotNull null
            val shortName = when (kindOf(name)) {
                RefKind.LOCAL_BRANCH -> name.removePrefix("refs/heads/")
                RefKind.REMOTE_BRANCH -> name.removePrefix("refs/remotes/")
                RefKind.TAG -> name.removePrefix("refs/tags/")
            }
            val upstream = git("for-each-ref", "--format=%(upstream:short)", name)
                .requireSuccess()
                .stdout
                .trim()
                .ifBlank { null }
            GitRef(
                fullName = name,
                shortName = shortName,
                kind = kindOf(name),
                commit = commit.stdout.trim(),
                isCurrentBranch = kindOf(name) == RefKind.LOCAL_BRANCH && shortName == current,
                upstream = upstream,
            )
        }
        .sortedBy { it.fullName }
}

private fun kindOf(fullName: String): RefKind = when {
    fullName.startsWith("refs/heads/") -> RefKind.LOCAL_BRANCH
    fullName.startsWith("refs/remotes/") -> RefKind.REMOTE_BRANCH
    else -> RefKind.TAG
}

internal fun GitTestRepo.indexMatchesHead(): Boolean =
    git("diff", "--cached", "--quiet").exitCode == 0

internal fun assertOk(result: GitResult) {
    assertTrue(result is GitResult.Ok, (result as? GitResult.Failure)?.stderr)
}

internal fun assertFailure(result: GitResult) {
    assertTrue(result is GitResult.Failure, "expected GitResult.Failure, got $result")
    assertTrue(result.stderr.isNotBlank(), "failure must preserve stderr")
}

internal suspend fun assertStatusMatches(
    repo: GitTestRepo,
    backend: CliGitBackend,
): WorkingStatus {
    val status = backend.status()
    val expected = repo.statusV1()
    val expectedByPath = expected.associateBy { it.path }
    val actualByPath = status.entries.associateBy { it.path }

    assertEquals(expectedByPath.keys, actualByPath.keys, "status paths")
    expected.forEach { expectedEntry ->
        val path = expectedEntry.path
        val actual = actualByPath.getValue(path)
        assertEquals(
            expectedEntry.code[0].normalizeStatusChar(),
            actual.index.normalizeStatusChar(),
            "index status for $path",
        )
        assertEquals(
            expectedEntry.code[1].normalizeStatusChar(),
            actual.worktree.normalizeStatusChar(),
            "worktree status for $path",
        )
        assertEquals(expectedKind(expectedEntry.code), actual.kind, "status kind for $path")
        assertEquals(expectedEntry.origPath, actual.origPath, "original path for $path")
    }

    if (repo.hasHead()) {
        assertEquals(repo.headHash(), status.branchHead, "branch head")
        assertTrue(!status.unborn, "repository with HEAD must not be unborn")
    } else {
        assertEquals(null, status.branchHead, "unborn branch head")
        assertTrue(status.unborn, "repository without HEAD must be unborn")
    }
    return status
}

internal suspend fun assertStagedMatchesStatus(repo: GitTestRepo, backend: CliGitBackend) {
    val actual = backend.status().entries
        .filter { it.index != '.' && it.index != '?' && it.index != 'U' }
        .map { it.path }
        .toSet()
    assertEquals(repo.stagedPaths(), actual, "staged path set")
}

private fun Char.normalizeStatusChar(): Char = if (this == '.') ' ' else this

private fun expectedKind(code: String): StatusKind = when {
    code == "??" -> StatusKind.UNTRACKED
    code[0] == 'R' || code[1] == 'R' -> StatusKind.RENAMED
    code in UNMERGED_CODES -> StatusKind.UNMERGED
    else -> StatusKind.ORDINARY
}

private val UNMERGED_CODES = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")
