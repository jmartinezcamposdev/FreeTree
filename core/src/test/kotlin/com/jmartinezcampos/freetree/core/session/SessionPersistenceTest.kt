package com.jmartinezcampos.freetree.core.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionPersistenceTest {

    @Test
    fun roundTripsTheVersionedSessionDocument() {
        val directory = Files.createTempDirectory("freetree-session-json")
        val file = directory.resolve("session.json")
        val persistence = JsonSessionPersistence(file)
        val state = PersistedSessionState(
            tabs = listOf(
                TabState(
                    id = "one",
                    path = "/repo/one",
                    query = "author:Ada",
                    hiddenRefs = setOf("refs/heads/topic"),
                    selectedHash = "abc",
                    scrollAnchorHash = "def",
                ),
            ),
            activeId = "one",
        )
        try {
            persistence.save(state)

            val loaded = assertNotNull(persistence.load())
            assertEquals(1, loaded.schemaVersion)
            assertEquals(state, loaded)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyStateFileIsImportedIntoSessionFile() {
        val directory = Files.createTempDirectory("freetree-session-legacy")
        val legacy = directory.resolve("state.json")
        val current = directory.resolve("session.json")
        val state = PersistedSessionState(
            tabs = listOf(TabState(id = "legacy", path = "/repo/legacy")),
            activeId = "legacy",
        )
        try {
            JsonSessionPersistence(legacy).save(state)

            val persistence = JsonSessionPersistence(current, legacyFile = legacy)
            assertEquals(state, persistence.load())
            assertTrue(Files.exists(current))
            assertEquals(state, JsonSessionPersistence(current).load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
