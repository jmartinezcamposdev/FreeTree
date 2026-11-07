package com.jmartinezcampos.freetree.core.session

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageLogTest {
    private val alpha = Path.of("/repo/alpha")
    private val beta = Path.of("/repo/beta")

    @Test
    fun recordsTimestampRepositoryLevelAndStderr() {
        val log = MessageLog()

        log.record(1_000L, alpha, UserMessage.Info("Index updated."))
        log.record(2_000L, beta, UserMessage.Error("Git command failed.", "fatal: boom"))

        val entries = log.entries.value
        assertEquals(2, entries.size)
        assertEquals(MessageLevel.INFO, entries[0].level)
        assertEquals("alpha", entries[0].repoName)
        assertEquals(alpha.toString(), entries[0].repoPath)
        assertEquals(1_000L, entries[0].timestampMillis)
        assertEquals("Index updated.", entries[0].text)
        assertNull(entries[0].stderr)

        assertEquals(MessageLevel.ERROR, entries[1].level)
        assertEquals("beta", entries[1].repoName)
        assertEquals("Git command failed.", entries[1].text)
        assertEquals("fatal: boom", entries[1].stderr)
    }

    @Test
    fun keepsOnlyTheNewestEntriesWhenOverCapacity() {
        val log = MessageLog(capacity = 3)

        repeat(5) { index ->
            log.record(index.toLong(), alpha, UserMessage.Info("message $index"))
        }

        assertEquals(listOf("message 2", "message 3", "message 4"), log.entries.value.map { it.text })
        assertEquals(listOf(2L, 3L, 4L), log.entries.value.map { it.timestampMillis })
    }

    @Test
    fun defaultCapacityRetainsAtLeastOneThousandMessages() {
        val log = MessageLog()

        repeat(1_050) { index ->
            log.record(index.toLong(), alpha, UserMessage.Info("message $index"))
        }

        val entries = log.entries.value
        assertEquals(1_000, entries.size)
        assertEquals("message 50", entries.first().text)
        assertEquals("message 1049", entries.last().text)
        assertTrue(entries.map { it.id }.zipWithNext().all { (left, right) -> right == left + 1 })
    }
}
