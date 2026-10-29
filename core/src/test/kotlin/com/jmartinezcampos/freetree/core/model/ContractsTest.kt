package com.jmartinezcampos.freetree.core.model

import com.jmartinezcampos.freetree.core.session.TabState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun logSpecIsFlatOnlyForContentFilters() {
        assertFalse(LogSpec(revs = listOf("refs/heads/main"), includeHead = true).flat)
        assertTrue(LogSpec(revs = emptyList(), includeHead = false, grep = "fix").flat)
    }

    @Test
    fun tabStateRoundTripsThroughJson() {
        val state = TabState(id = "tab-1", path = "/tmp/repo")

        assertEquals(state, Json.decodeFromString<TabState>(Json.encodeToString(state)))
    }
}
