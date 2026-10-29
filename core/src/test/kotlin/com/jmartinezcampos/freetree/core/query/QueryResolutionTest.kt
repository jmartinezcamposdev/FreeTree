package com.jmartinezcampos.freetree.core.query

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RefKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryResolutionTest {

    private val main = ref("main", RefKind.LOCAL_BRANCH)
    private val featureX = ref("feature/x", RefKind.LOCAL_BRANCH)
    private val featureY = ref("feature/y", RefKind.LOCAL_BRANCH)
    private val originMain = ref("origin/main", RefKind.REMOTE_BRANCH)
    private val dependabot = ref("origin/dependabot/x", RefKind.REMOTE_BRANCH)
    private val tagV1 = ref("v1.0", RefKind.TAG)
    private val tagV2 = ref("v1.1", RefKind.TAG)
    private val refs = listOf(main, featureX, featureY, originMain, dependabot, tagV1, tagV2)

    @Test
    fun emptyQueryKeepsAllEnabledRefsAndHead() {
        val spec = resolveQuery("")

        assertEquals(refs.map { it.fullName }, spec.revs)
        assertTrue(spec.includeHead)
        assertTrue(!spec.flat)
    }

    @Test
    fun localsStayOnWhileRemoteAndTagTogglesFilterKinds() {
        assertEquals(
            listOf(main.fullName, featureX.fullName, featureY.fullName),
            resolveQuery("", showRemotes = false, showTags = false).revs,
        )
        assertEquals(
            listOf(main.fullName, featureX.fullName, featureY.fullName, tagV1.fullName, tagV2.fullName),
            resolveQuery("", showRemotes = false).revs,
        )
        assertEquals(
            listOf(main.fullName, featureX.fullName, featureY.fullName, originMain.fullName, dependabot.fullName),
            resolveQuery("", showTags = false).revs,
        )
    }

    @Test
    fun headToggleOnlyChangesIncludeHead() {
        val withHead = resolveQuery("")
        val withoutHead = resolveQuery("", showHead = false)

        assertEquals(withHead.revs, withoutHead.revs)
        assertTrue(withHead.includeHead)
        assertTrue(!withoutHead.includeHead)
    }

    @Test
    fun refIncludesUseGlobsAgainstShortNames() {
        assertEquals(
            listOf(featureX.fullName, featureY.fullName),
            resolveQuery("ref:feature/*").revs,
        )
        assertEquals(
            listOf(originMain.fullName, dependabot.fullName),
            resolveQuery("ref:origin/*").revs,
        )
        assertEquals(
            listOf(main.fullName),
            resolveQuery("ref:?ain").revs,
        )
        assertEquals(
            listOf(tagV1.fullName, tagV2.fullName),
            resolveQuery("ref:v?.*").revs,
        )
        assertEquals(
            listOf(featureX.fullName, featureY.fullName, originMain.fullName, dependabot.fullName),
            resolveQuery("ref:*/*").revs,
        )
        assertEquals(refs.map { it.fullName }, resolveQuery("ref:*").revs)
    }

    @Test
    fun refExcludesRemoveMatchesAndWinOverIncludes() {
        assertEquals(
            listOf(main.fullName, featureX.fullName, featureY.fullName, originMain.fullName, tagV1.fullName, tagV2.fullName),
            resolveQuery("-ref:origin/dependabot/*").revs,
        )
        assertEquals(
            listOf(originMain.fullName, dependabot.fullName),
            resolveQuery("ref:origin/*").revs,
        )
        assertEquals(
            listOf(originMain.fullName),
            resolveQuery("ref:origin/* -ref:origin/dependabot/*").revs,
        )
        assertEquals(
            listOf(main.fullName, originMain.fullName, dependabot.fullName),
            resolveQuery("-ref:feature/* -ref:v*").revs,
        )
    }

    @Test
    fun hiddenRefsAreRemovedByFullName() {
        assertEquals(
            listOf(main.fullName, featureX.fullName, featureY.fullName, originMain.fullName, dependabot.fullName, tagV2.fullName),
            resolveQuery("", hidden = setOf(tagV1.fullName)).revs,
        )
        assertEquals(
            refs.map { it.fullName },
            resolveQuery("", hidden = setOf(tagV1.shortName)).revs,
            "hidden set contains full ref names, not short names",
        )
    }

    @Test
    fun includeExcludeAndHiddenCompose() {
        val spec = resolveQuery(
            "ref:origin/* ref:feature/* -ref:feature/y",
            hidden = setOf(dependabot.fullName),
        )

        assertEquals(listOf(featureX.fullName, originMain.fullName), spec.revs)
    }

    @Test
    fun togglesApplyBeforeIncludes() {
        assertEquals(emptyList(), resolveQuery("ref:origin/*", showRemotes = false).revs)
        assertEquals(emptyList(), resolveQuery("ref:v*", showTags = false).revs)
    }

    @Test
    fun noVisibleRefsProducesEmptySpec() {
        val spec = resolveQuery("ref:does-not-exist", showHead = false)

        assertEquals(emptyList(), spec.revs)
        assertTrue(!spec.includeHead)
        assertEquals(emptyList<String>(), resolve(Query(), emptyList(), emptySet(), true, true, false).revs)
    }

    @Test
    fun contentTypeFiltersArePassedThroughAndSetFlat() {
        val spec = resolveQuery(
            "ref:main author:Ada msg:\"fix bug\" since:\"2024-01-01 00:00\" " +
                "until:2024-12-31 path:\"src/a b.kt\"",
        )

        assertEquals(listOf(main.fullName), spec.revs)
        assertTrue(spec.includeHead)
        assertEquals("Ada", spec.author)
        assertEquals("fix bug", spec.grep)
        assertEquals("2024-01-01 00:00", spec.since)
        assertEquals("2024-12-31", spec.until)
        assertEquals("src/a b.kt", spec.path)
        assertTrue(spec.flat)
    }

    @Test
    fun eachContentFilterEnablesFlatMode() {
        listOf(
            "author:Ada",
            "msg:fix",
            "since:2024-01-01",
            "until:2024-01-01",
            "path:src",
        ).forEach { query ->
            assertTrue(resolveQuery(query).flat, "query <$query> must set flat")
        }
    }

    @Test
    fun revsFollowInputOrderAfterFiltering() {
        val shuffled = listOf(tagV2, main, dependabot, featureX)
        val spec = resolveQuery("", refs = shuffled)

        assertEquals(listOf(tagV2.fullName, main.fullName, dependabot.fullName, featureX.fullName), spec.revs)
    }

    @Test
    fun globMatchingTreatsStarAsIncludingSlash() {
        assertTrue(globMatches("origin/*", "origin/dependabot/x"))
        assertTrue(globMatches("*", "anything/with/slash"))
        assertTrue(globMatches("a*b*c", "a/x/b/y/c"))
        assertTrue(!globMatches("origin/*", "origin"))
        assertTrue(globMatches("origin*", "origin"))
        assertTrue(globMatches("?", "x"))
        assertTrue(!globMatches("?", "xy"))
        assertTrue(!globMatches("?ain", "mainx"))
        assertTrue(globMatches("*main*", "origin/main"))
        assertTrue(globMatches("main", "main"))
        assertTrue(!globMatches("main", "Main"))
    }

    private fun resolveQuery(
        text: String,
        refs: List<GitRef> = this.refs,
        hidden: Set<String> = emptySet(),
        showRemotes: Boolean = true,
        showTags: Boolean = true,
        showHead: Boolean = true,
    ): LogSpec {
        val query = parseQuery(text).getOrThrow()
        return resolve(query, refs, hidden, showRemotes, showTags, showHead)
    }

    private fun ref(shortName: String, kind: RefKind): GitRef {
        val fullName = when (kind) {
            RefKind.LOCAL_BRANCH -> "refs/heads/$shortName"
            RefKind.REMOTE_BRANCH -> "refs/remotes/$shortName"
            RefKind.TAG -> "refs/tags/$shortName"
        }
        return GitRef(
            fullName = fullName,
            shortName = shortName,
            kind = kind,
            commit = "commit-$shortName",
            isCurrentBranch = shortName == "main",
            upstream = null,
        )
    }
}
