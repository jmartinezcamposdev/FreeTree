package com.jmartinezcampos.freetree.core.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryParsingTest {

    @Test
    fun parsesSupportedTerms() {
        val cases = listOf(
            "" to Query(),
            "   \t  " to Query(),
            "ref:main" to Query(refIncludes = listOf("main")),
            "-ref:origin/dependabot/*" to Query(refExcludes = listOf("origin/dependabot/*")),
            "ref:origin/* -ref:origin/dependabot/*" to Query(
                refIncludes = listOf("origin/*"),
                refExcludes = listOf("origin/dependabot/*"),
            ),
            "ref:a ref:b -ref:c -ref:d" to Query(
                refIncludes = listOf("a", "b"),
                refExcludes = listOf("c", "d"),
            ),
            "author:Ada" to Query(author = "Ada"),
            "msg:fix" to Query(grep = "fix"),
            "fix" to Query(grep = "fix"),
            "since:2024-01-01" to Query(since = "2024-01-01"),
            "until:2024-02-01" to Query(until = "2024-02-01"),
            "path:src/main.kt" to Query(path = "src/main.kt"),
            "path:-leading-dash" to Query(path = "-leading-dash"),
            "msg:foo:bar" to Query(grep = "foo:bar"),
            "ref:main extra" to Query(refIncludes = listOf("main"), grep = "extra"),
            "author:Ada Lovelace" to Query(author = "Ada", grep = "Lovelace"),
            "msg:a msg:b" to Query(grep = "b"),
            "author:a author:b" to Query(author = "b"),
        )

        cases.forEach { (text, expected) ->
            val result = parseQuery(text)
            assertTrue(result.isSuccess, "expected success for <$text>, got ${result.exceptionOrNull()}")
            assertEquals(expected, result.getOrThrow(), "query: <$text>")
        }
    }

    @Test
    fun quotedValuesMayContainSpacesAndEscapes() {
        val cases = listOf(
            "author:\"Ada Lovelace\"" to Query(author = "Ada Lovelace"),
            "msg:\"fix the bug\"" to Query(grep = "fix the bug"),
            "\"fix the bug\"" to Query(grep = "fix the bug"),
            "since:\"2024-01-01 10:30\"" to Query(since = "2024-01-01 10:30"),
            "path:\"src/a b.kt\"" to Query(path = "src/a b.kt"),
            "ref:\"feature/a b\"" to Query(refIncludes = listOf("feature/a b")),
            "msg:\"say \\\"hi\\\"\"" to Query(grep = "say \"hi\""),
            "msg:\"back\\\\slash\"" to Query(grep = "back\\slash"),
            "msg:\" spaced \"" to Query(grep = " spaced "),
            "  ref:\"feature/x\"   author:\"Grace Hopper\"  " to Query(
                refIncludes = listOf("feature/x"),
                author = "Grace Hopper",
            ),
        )

        cases.forEach { (text, expected) ->
            val result = parseQuery(text)
            assertTrue(result.isSuccess, "expected success for <$text>, got ${result.exceptionOrNull()}")
            assertEquals(expected, result.getOrThrow(), "query: <$text>")
        }
    }

    @Test
    fun combinesEveryTerm() {
        val query = parseQuery(
            "ref:origin/* -ref:origin/dependabot/* author:\"Ada Lovelace\" " +
                "msg:\"fix bug\" since:\"2024-01-01 00:00\" until:2024-12-31 path:\"src/a b.kt\"",
        ).getOrThrow()

        assertEquals(listOf("origin/*"), query.refIncludes)
        assertEquals(listOf("origin/dependabot/*"), query.refExcludes)
        assertEquals("Ada Lovelace", query.author)
        assertEquals("fix bug", query.grep)
        assertEquals("2024-01-01 00:00", query.since)
        assertEquals("2024-12-31", query.until)
        assertEquals("src/a b.kt", query.path)
    }

    @Test
    fun invalidQueriesReturnFailureWithoutThrowing() {
        val invalid = listOf(
            "author:",
            "msg:",
            "ref:",
            "path:",
            "foo:bar",
            "-author:Ada",
            "-msg:fix",
            "-ref",
            "-",
            ":",
            "-ref:",
            "\"unterminated",
            "msg:\"unterminated",
            "msg:\"a b\"x",
            "\"\"",
            "ref:\"\"",
            "msg:\"dangling\\",
            "a\"b\"",
            "-\"quoted\"",
        )

        invalid.forEach { text ->
            val result = parseQuery(text)
            assertTrue(
                result.isFailure,
                "expected failure for <$text>, got ${result.getOrNull()}",
            )
        }
    }
}
