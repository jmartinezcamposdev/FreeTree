package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.query.Query
import com.jmartinezcampos.freetree.core.query.parseQuery
import com.jmartinezcampos.freetree.core.query.resolve
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class QueryAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var backend: CliGitBackend

    @BeforeTest
    fun setUp() {
        repo = GitTestRepo.create()
        backend = runBlocking { repo.openBackend() }
        buildHistory()
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun refIncludesAndExcludesResolveToGitRevList() = runBlocking {
        val spec = resolveQuery("ref:feature/* -ref:feature/y", showHead = false)

        assertEquals(listOf("refs/heads/feature/x"), spec.revs)
        assertLogMatchesRevList(spec)
        assertTrue(backend.logStream(spec).toList().isNotEmpty())
    }

    @Test
    fun contentFiltersProduceTheSameCommitsAsGitRevList() = runBlocking {
        val queries = listOf(
            "author:Alice",
            "msg:\"fix parser\"",
            "path:\"src/a b.kt\"",
            "since:2024-01-01 until:2024-12-31",
        )

        queries.forEach { text ->
            val spec = resolveQuery(text, showHead = false)
            assertTrue(spec.flat, "content filter must use flat mode: $text")
            assertTrue(backend.logStream(spec).toList().isNotEmpty(), "expected matches for $text")
            assertLogMatchesRevList(spec)
        }
    }

    @Test
    fun refTogglesAndHiddenRefsUseEnabledKinds() = runBlocking {
        val locals = resolveQuery("", showRemotes = false, showTags = false, showHead = false)
        assertEquals(
            setOf("refs/heads/feature/x", "refs/heads/feature/y", "refs/heads/main", "refs/heads/release/1.0"),
            locals.revs.toSet(),
        )

        val hidden = resolveQuery(
            "",
            hidden = setOf("refs/tags/light"),
            showHead = false,
        )
        assertTrue("refs/tags/v1.0" in hidden.revs)
        assertTrue("refs/tags/light" !in hidden.revs)

        val noRemotes = resolveQuery("ref:upstream/*", showRemotes = false, showHead = false)
        assertTrue(noRemotes.revs.isEmpty())
    }

    @Test
    fun allVisibleRefsMatchRevListForEveryBranchAndTag() = runBlocking {
        val spec = resolveQuery("", showHead = false)

        assertEquals(
            setOf(
                "refs/heads/feature/x",
                "refs/heads/feature/y",
                "refs/heads/main",
                "refs/heads/release/1.0",
                "refs/remotes/upstream/main",
                "refs/tags/light",
                "refs/tags/v1.0",
            ),
            spec.revs.toSet(),
        )
        assertLogMatchesRevList(spec)
    }

    @Test
    fun noVisibleRefsProducesEmptySelection() = runBlocking {
        val spec = resolveQuery("ref:does-not-exist", showHead = false)

        assertTrue(spec.revs.isEmpty())
        assertTrue(!spec.includeHead)
        assertTrue(backend.logStream(spec).toList().isEmpty())
    }

    private suspend fun resolveQuery(
        text: String,
        hidden: Set<String> = emptySet(),
        showRemotes: Boolean = true,
        showTags: Boolean = true,
        showHead: Boolean = true,
    ): LogSpec {
        val query = parseQuery(text).getOrThrow()
        return resolve(query, backend.refs(), hidden, showRemotes, showTags, showHead)
    }

    private suspend fun assertLogMatchesRevList(spec: LogSpec) {
        val actual = backend.logStream(spec).toList()
        val expected = parseRevListParents(oracleRevList(spec))

        assertEquals(expected.map { it.first }, actual.map { it.hash })
        assertEquals(expected.map { it.second }, actual.map { it.parents })
    }

    private fun oracleRevList(spec: LogSpec): String {
        val args = mutableListOf("rev-list", "--parents", "--topo-order")
        spec.author?.let { args += "--author=$it" }
        spec.grep?.let { args += "--grep=$it" }
        spec.since?.let { args += "--since=$it" }
        spec.until?.let { args += "--until=$it" }
        if (spec.includeHead) args += "HEAD"
        args += spec.revs
        if (spec.path != null) {
            args += "--"
            args += spec.path
        }
        return repo.git(*args.toTypedArray()).requireSuccess().stdout
    }

    private fun buildHistory() {
        repo.writeFile("src/a b.kt", "alpha\n")
        repo.commitAll(
            message = "initial",
            authorName = "Alice",
            authorEmail = "alice@example.com",
            date = "2023-01-01T00:00:00+0000",
        )

        repo.checkoutNewBranch("feature/x").requireSuccess()
        repo.commitFile(
            relative = "src/a b.kt",
            content = "alpha feature\n",
            message = "fix alpha parser",
            authorName = "Alice",
            authorEmail = "alice@example.com",
            date = "2024-01-15T00:00:00+0000",
        )

        repo.checkout("main").requireSuccess()
        repo.checkoutNewBranch("feature/y").requireSuccess()
        repo.commitFile(
            relative = "docs/readme.md",
            content = "docs\n",
            message = "document beta",
            authorName = "Bob",
            authorEmail = "bob@example.com",
            date = "2024-02-10T00:00:00+0000",
        )

        repo.checkout("main").requireSuccess()
        val main = repo.commitFile(
            relative = "src/parser.kt",
            content = "parser\n",
            message = "fix parser bug",
            authorName = "Alice",
            authorEmail = "alice@example.com",
            date = "2024-06-01T00:00:00+0000",
        )
        repo.git("tag", "-a", "v1.0", "-m", "release", main).requireSuccess()
        val featureX = repo.gitOk("rev-parse", "refs/heads/feature/x")
        repo.git("tag", "light", featureX).requireSuccess()
        repo.merge("feature/x", message = "merge feature x", date = "2024-08-01T00:00:00+0000").requireSuccess()

        val merge = repo.headHash()
        repo.createBranch("release/1.0", merge).requireSuccess()
        repo.git("update-ref", "refs/remotes/upstream/main", main).requireSuccess()
        repo.git("symbolic-ref", "refs/remotes/upstream/HEAD", "refs/remotes/upstream/main").requireSuccess()
    }
}
