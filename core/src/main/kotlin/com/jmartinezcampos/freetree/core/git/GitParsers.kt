package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus

internal object GitParsers {
    fun parseRefs(output: String): List<GitRef> =
        output.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseRefLine)
            .toList()

    private fun parseRefLine(line: String): GitRef? {
        val fields = line.split('\u0000')
        if (fields.size < 8) return null

        val objectName = fields[0]
        val objectType = fields[1]
        val peeledName = fields[2]
        val peeledType = fields[3]
        val fullName = fields[4]
        val headMarker = fields[5]
        val upstream = fields[6].trim()
        val symref = fields[7].trim()

        if (symref.isNotEmpty()) return null

        val kind = when {
            fullName.startsWith("refs/heads/") -> RefKind.LOCAL_BRANCH
            fullName.startsWith("refs/remotes/") -> RefKind.REMOTE_BRANCH
            fullName.startsWith("refs/tags/") -> RefKind.TAG
            else -> return null
        }
        val commit = when {
            objectType == "commit" -> objectName
            objectType == "tag" && peeledType == "commit" -> peeledName
            else -> return null
        }
        if (commit.isEmpty()) return null

        val shortName = when (kind) {
            RefKind.LOCAL_BRANCH -> fullName.removePrefix("refs/heads/")
            RefKind.REMOTE_BRANCH -> fullName.removePrefix("refs/remotes/")
            RefKind.TAG -> fullName.removePrefix("refs/tags/")
        }
        return GitRef(
            fullName = fullName,
            shortName = shortName,
            kind = kind,
            commit = commit,
            isCurrentBranch = headMarker == "*",
            upstream = upstream.ifBlank { null },
        )
    }

    fun parseLogRecord(record: String): RawCommit? {
        val fields = record.split('\u001f', limit = 5)
        if (fields.size != 5) return null

        val hash = fields[0].trim()
        if (!isObjectId(hash)) return null

        val parents = fields[1].trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        if (!parents.all(::isObjectId)) return null

        val time = fields[3].trim().toLongOrNull() ?: return null
        return RawCommit(
            hash = hash,
            parents = parents,
            authorName = fields[2],
            authorTimeSec = time,
            subject = fields[4],
        )
    }

    fun parseStatus(output: String): WorkingStatus {
        var branchHead: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var unborn = false
        val entries = mutableListOf<StatusEntry>()

        val tokens = output.split('\u0000')
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.isEmpty() -> Unit
                token.startsWith("# branch.oid ") -> {
                    val value = token.removePrefix("# branch.oid ").trim()
                    if (value == "(initial)") {
                        unborn = true
                        branchHead = null
                    } else {
                        branchHead = value.ifBlank { null }
                    }
                }
                token.startsWith("# branch.head ") -> Unit
                token.startsWith("# branch.upstream ") -> {
                    upstream = token.removePrefix("# branch.upstream ").trim().ifBlank { null }
                }
                token.startsWith("# branch.ab ") -> {
                    val match = Regex("""\+(\d+) -(\d+)""").find(token)
                    if (match != null) {
                        ahead = match.groupValues[1].toIntOrNull() ?: 0
                        behind = match.groupValues[2].toIntOrNull() ?: 0
                    }
                }
                token.startsWith("1 ") -> {
                    val fields = token.split(' ', limit = 9)
                    if (fields.size == 9) {
                        entries += StatusEntry(
                            path = fields[8],
                            origPath = null,
                            index = fields[1].getOrElse(0) { ' ' },
                            worktree = fields[1].getOrElse(1) { ' ' },
                            kind = StatusKind.ORDINARY,
                        )
                    }
                }
                token.startsWith("2 ") -> {
                    val fields = token.split(' ', limit = 10)
                    val originalPath = tokens.getOrNull(index + 1)
                    if (fields.size == 10 && originalPath != null) {
                        index += 1
                        entries += StatusEntry(
                            path = fields[9],
                            origPath = originalPath,
                            index = fields[1].getOrElse(0) { ' ' },
                            worktree = fields[1].getOrElse(1) { ' ' },
                            kind = StatusKind.RENAMED,
                        )
                    }
                }
                token.startsWith("u ") -> {
                    val fields = token.split(' ', limit = 11)
                    if (fields.size == 11) {
                        entries += StatusEntry(
                            path = fields[10],
                            origPath = null,
                            index = fields[1].getOrElse(0) { ' ' },
                            worktree = fields[1].getOrElse(1) { ' ' },
                            kind = StatusKind.UNMERGED,
                        )
                    }
                }
                token.startsWith("? ") -> {
                    entries += StatusEntry(
                        path = token.removePrefix("? "),
                        origPath = null,
                        index = '?',
                        worktree = '?',
                        kind = StatusKind.UNTRACKED,
                    )
                }
                token.startsWith("! ") -> Unit
            }
            index += 1
        }
        return WorkingStatus(
            branchHead = branchHead,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            unborn = unborn,
            entries = entries,
        )
    }

    fun parseNameStatus(output: String): List<FileChange> {
        val tokens = output.split('\u0000')
        val changes = mutableListOf<FileChange>()
        var index = 0
        while (index < tokens.size) {
            val statusToken = tokens[index]
            if (statusToken.isEmpty()) {
                index += 1
                continue
            }
            val status = statusToken.first()
            if (status == 'R' || status == 'C') {
                val oldPath = tokens.getOrNull(index + 1) ?: break
                val newPath = tokens.getOrNull(index + 2) ?: break
                changes += FileChange(status = status, path = newPath, oldPath = oldPath)
                index += 3
            } else {
                val path = tokens.getOrNull(index + 1) ?: break
                changes += FileChange(status = status, path = path, oldPath = null)
                index += 2
            }
        }
        return changes
    }

    fun parseCommitDetail(output: String): CommitDetail? {
        val record = output.trimEnd('\u0000')
        val fields = record.split('\u001f', limit = 8)
        if (fields.size != 8) return null

        val hash = fields[0].trim()
        if (!isObjectId(hash)) return null
        val parents = fields[1].trim().split(' ').filter { it.isNotEmpty() }
        if (!parents.all(::isObjectId)) return null

        return CommitDetail(
            hash = hash,
            parents = parents,
            authorName = fields[2],
            authorEmail = fields[3],
            authorTimeSec = fields[4].trim().toLongOrNull() ?: return null,
            committerName = fields[5],
            committerTimeSec = fields[6].trim().toLongOrNull() ?: return null,
            message = fields[7],
        )
    }

    fun parseDiff(text: String): DiffText {
        val lines = mutableListOf<DiffLine>()
        var binary = false
        var inHunk = false
        var oldLine = 0
        var newLine = 0

        for (line in text.split('\n')) {
            if (line.isEmpty()) continue

            if (line.startsWith("@@")) {
                val match = HUNK_PATTERN.find(line)
                if (match != null) {
                    inHunk = true
                    oldLine = match.groupValues[1].toIntOrNull() ?: 0
                    newLine = match.groupValues[2].toIntOrNull() ?: 0
                    lines += DiffLine(DiffLineKind.HUNK_HEADER, null, null, line)
                    continue
                }
            }

            if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                binary = true
                lines += DiffLine(DiffLineKind.NOTE, null, null, line)
                continue
            }
            if (line.startsWith("\\")) {
                lines += DiffLine(DiffLineKind.NOTE, null, null, line)
                continue
            }

            if (inHunk) {
                when (line[0]) {
                    '+' -> {
                        lines += DiffLine(DiffLineKind.ADD, null, newLine, line.substring(1))
                        newLine += 1
                    }
                    '-' -> {
                        lines += DiffLine(DiffLineKind.DEL, oldLine, null, line.substring(1))
                        oldLine += 1
                    }
                    ' ' -> {
                        lines += DiffLine(DiffLineKind.CONTEXT, oldLine, newLine, line.substring(1))
                        oldLine += 1
                        newLine += 1
                    }
                    else -> {
                        inHunk = false
                        lines += DiffLine(DiffLineKind.FILE_HEADER, null, null, line)
                    }
                }
            } else {
                lines += DiffLine(DiffLineKind.FILE_HEADER, null, null, line)
            }
        }
        return DiffText(lines = lines, binary = binary, truncated = false)
    }

    fun addedTextDiff(text: String): List<DiffLine> {
        if (text.isEmpty()) return emptyList()
        val content = if (text.endsWith('\n')) text.dropLast(1) else text
        return content.split('\n').mapIndexed { index, line ->
            DiffLine(
                kind = DiffLineKind.ADD,
                oldNo = null,
                newNo = index + 1,
                text = line.removeSuffix("\r"),
            )
        }
    }

    private fun isObjectId(value: String): Boolean =
        (value.length == 40 || value.length == 64) && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private val HUNK_PATTERN = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
}
