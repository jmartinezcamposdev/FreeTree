package com.jmartinezcampos.freetree.app.menu

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentRepositoriesTest {
    private class InMemoryStore(initial: List<String> = emptyList()) : RecentRepositoriesStore {
        var saved: List<String> = initial

        override fun load(): List<String> = saved

        override fun save(paths: List<String>) {
            saved = paths
        }
    }

    private val base = Path.of(System.getProperty("java.io.tmpdir")).resolve("freetree-recent")

    @Test
    fun recordsNewestFirstWithoutDuplicates() {
        val store = InMemoryStore()
        val recent = RecentRepositories(store, maxEntries = 10)
        val first = base.resolve("one")
        val second = base.resolve("two")

        recent.record(first)
        recent.record(second)
        recent.record(first)

        assertEquals(listOf(first, second), recent.paths.value)
    }

    @Test
    fun keepsOnlyTheNewestTenEntries() {
        val store = InMemoryStore()
        val recent = RecentRepositories(store, maxEntries = 10)

        (1..12).forEach { recent.record(base.resolve("repo-$it")) }

        assertEquals(10, recent.paths.value.size)
        assertEquals(base.resolve("repo-12"), recent.paths.value.first())
        assertFalse(base.resolve("repo-1") in recent.paths.value)
        assertFalse(base.resolve("repo-2") in recent.paths.value)
    }

    @Test
    fun reloadsSavedEntries() {
        val store = InMemoryStore()
        val recent = RecentRepositories(store, maxEntries = 10)
        recent.record(base.resolve("one"))
        recent.record(base.resolve("two"))

        val reloaded = RecentRepositories(store, maxEntries = 10)

        assertEquals(recent.paths.value, reloaded.paths.value)
    }

    @Test
    fun emptyStoreStartsWithNoRepositories() {
        val recent = RecentRepositories(InMemoryStore(), maxEntries = 10)

        assertTrue(recent.paths.value.isEmpty())
    }

    @Test
    fun zeroMaxEntriesStoresNothing() {
        val store = InMemoryStore()
        val recent = RecentRepositories(store, maxEntries = 0)

        recent.record(base.resolve("one"))

        assertTrue(recent.paths.value.isEmpty())
        assertNull(store.saved.firstOrNull())
    }
}
