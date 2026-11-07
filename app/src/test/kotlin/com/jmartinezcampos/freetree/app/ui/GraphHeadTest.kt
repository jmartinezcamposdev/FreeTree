package com.jmartinezcampos.freetree.app.ui

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphHeadTest {
    private val hash = "a".repeat(40)
    private val head = HeadInfo(hash, "main")

    private fun ref(kind: RefKind, name: String) = GitRef(
        fullName = "refs/${kind.name.lowercase()}/$name",
        shortName = name,
        kind = kind,
        commit = hash,
        isCurrentBranch = kind == RefKind.LOCAL_BRANCH,
        upstream = null,
    )

    @Test
    fun hidesHeadWhenAVisibleLocalBranchIsAtHead() {
        assertFalse(
            showHeadHighlight(
                head,
                mapOf(hash to listOf(ref(RefKind.LOCAL_BRANCH, "main"))),
                showHead = true,
            ),
        )
    }

    @Test
    fun hidesHeadWhenAVisibleRemoteBranchIsAtHead() {
        assertFalse(
            showHeadHighlight(
                head,
                mapOf(hash to listOf(ref(RefKind.REMOTE_BRANCH, "origin/main"))),
                showHead = true,
            ),
        )
    }

    @Test
    fun showsHeadWhenOnlyTagsAreVisibleAtHead() {
        assertTrue(
            showHeadHighlight(
                head,
                mapOf(hash to listOf(ref(RefKind.TAG, "v1.0"))),
                showHead = true,
            ),
        )
    }

    @Test
    fun showsHeadWhenNoRefsAreVisibleAtHead() {
        assertTrue(showHeadHighlight(head, emptyMap(), showHead = true))
    }

    @Test
    fun doesNotShowHeadWithoutAHeadOrWhenDisabled() {
        assertFalse(showHeadHighlight(null, emptyMap(), showHead = true))
        assertFalse(showHeadHighlight(head, emptyMap(), showHead = false))
    }
}
