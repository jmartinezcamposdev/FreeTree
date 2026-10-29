package com.jmartinezcampos.freetree.core.query

internal class QuerySyntaxError(message: String) : IllegalArgumentException(message)

/**
 * Parses space-separated terms. A quoted value may contain spaces; `\` escapes the next character
 * inside quotes.
 */
internal class QueryParser(private val text: String) {
    private var position = 0
    private val terms = ArrayList<Term>()

    fun parse(): Result<Query> {
        return try {
            while (true) {
                skipWhitespace()
                if (position == text.length) break
                terms += parseTerm()
                if (position < text.length && !text[position].isWhitespace()) {
                    throw QuerySyntaxError(
                        "Unexpected character '${text[position]}' at position $position",
                    )
                }
            }
            Result.success(buildQuery())
        } catch (error: QuerySyntaxError) {
            Result.failure(error)
        }
    }

    private fun parseTerm(): Term {
        if (text[position] == '"') {
            return Term(key = null, value = readBareQuoted(), negated = false)
        }

        val negated = text[position] == '-'
        if (negated) position++
        if (position >= text.length) throw QuerySyntaxError("Dangling '-' in query")

        if (text[position] == '"') {
            if (negated) throw QuerySyntaxError("Only ref terms may be negated")
            return Term(key = null, value = readBareQuoted(), negated = false)
        }

        val key = readKey()
        if (position < text.length && text[position] == ':') {
            position++
            val value = readValue()
            validateKey(key, negated)
            return Term(key = key, value = value, negated = negated)
        }
        if (negated) throw QuerySyntaxError("Only ref terms may be negated")
        if (key.isEmpty()) throw QuerySyntaxError("Empty query term at position $position")
        return Term(key = null, value = key, negated = false)
    }

    private fun readKey(): String {
        val start = position
        while (
            position < text.length &&
            text[position] != ':' &&
            text[position] != '"' &&
            !text[position].isWhitespace()
        ) {
            position++
        }
        return text.substring(start, position)
    }

    private fun readValue(): String {
        if (position >= text.length) throw QuerySyntaxError("Missing value at position $position")
        val value = if (text[position] == '"') {
            readQuoted()
        } else {
            val start = position
            while (position < text.length && !text[position].isWhitespace()) position++
            text.substring(start, position)
        }
        if (value.isEmpty()) throw QuerySyntaxError("Missing value at position $position")
        return value
    }

    private fun readQuoted(): String {
        position++
        val value = StringBuilder()
        while (position < text.length) {
            when (val char = text[position++]) {
                '"' -> return value.toString()
                '\\' -> {
                    if (position >= text.length) {
                        throw QuerySyntaxError("Dangling escape at position $position")
                    }
                    value.append(text[position++])
                }
                else -> value.append(char)
            }
        }
        throw QuerySyntaxError("Unterminated quoted value")
    }

    private fun readBareQuoted(): String {
        val value = readQuoted()
        if (value.isEmpty()) throw QuerySyntaxError("Empty query term")
        return value
    }

    private fun validateKey(key: String, negated: Boolean) {
        if (key.isEmpty()) throw QuerySyntaxError("Missing term before ':'")
        if (key !in KEYED_TERMS) throw QuerySyntaxError("Unknown query term: $key")
        if (negated && key != REF) throw QuerySyntaxError("Only ref terms may be negated")
    }

    private fun buildQuery(): Query {
        val includes = ArrayList<String>()
        val excludes = ArrayList<String>()
        var author: String? = null
        var grep: String? = null
        var since: String? = null
        var until: String? = null
        var path: String? = null

        terms.forEach { term ->
            when (term.key) {
                null -> grep = term.value
                REF -> if (term.negated) excludes += term.value else includes += term.value
                AUTHOR -> author = term.value
                MSG -> grep = term.value
                SINCE -> since = term.value
                UNTIL -> until = term.value
                PATH -> path = term.value
            }
        }
        return Query(
            refIncludes = includes,
            refExcludes = excludes,
            author = author,
            grep = grep,
            since = since,
            until = until,
            path = path,
        )
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private data class Term(val key: String?, val value: String, val negated: Boolean)

    private companion object {
        const val REF = "ref"
        const val AUTHOR = "author"
        const val MSG = "msg"
        const val SINCE = "since"
        const val UNTIL = "until"
        const val PATH = "path"
        val KEYED_TERMS = setOf(REF, AUTHOR, MSG, SINCE, UNTIL, PATH)
    }
}
