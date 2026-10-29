package com.jmartinezcampos.freetree.core.query

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind

data class Query(
    val refIncludes: List<String> = emptyList(),
    val refExcludes: List<String> = emptyList(),
    val author: String? = null,
    val grep: String? = null,
    val since: String? = null,
    val until: String? = null,
    val path: String? = null,
)

fun parseQuery(text: String): Result<Query> = QueryParser(text).parse()

fun resolve(
    q: Query,
    refs: List<GitRef>,
    hidden: Set<String>,
    showRemotes: Boolean,
    showTags: Boolean,
    showHead: Boolean,
): LogSpec {
    val visible = refs.filter { ref ->
        isKindEnabled(ref.kind, showRemotes, showTags) &&
            matchesIncludes(q.refIncludes, ref) &&
            q.refExcludes.none { globMatches(it, ref.shortName) } &&
            ref.fullName !in hidden
    }
    return LogSpec(
        revs = visible.map { it.fullName },
        includeHead = showHead,
        author = q.author,
        grep = q.grep,
        since = q.since,
        until = q.until,
        path = q.path,
    )
}

private fun isKindEnabled(kind: RefKind, showRemotes: Boolean, showTags: Boolean): Boolean =
    when (kind) {
        RefKind.LOCAL_BRANCH -> true
        RefKind.REMOTE_BRANCH -> showRemotes
        RefKind.TAG -> showTags
    }

private fun matchesIncludes(includes: List<String>, ref: GitRef): Boolean =
    includes.isEmpty() || includes.any { globMatches(it, ref.shortName) }
