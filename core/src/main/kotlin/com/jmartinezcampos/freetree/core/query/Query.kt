package com.jmartinezcampos.freetree.core.query

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.LogSpec

data class Query(
    val refIncludes: List<String> = emptyList(),
    val refExcludes: List<String> = emptyList(),
    val author: String? = null,
    val grep: String? = null,
    val since: String? = null,
    val until: String? = null,
    val path: String? = null,
)

fun parseQuery(text: String): Result<Query> = TODO("Implemented in Phase 1")

fun resolve(
    q: Query,
    refs: List<GitRef>,
    hidden: Set<String>,
    showRemotes: Boolean,
    showTags: Boolean,
    showHead: Boolean,
): LogSpec = TODO("Implemented in Phase 1")
