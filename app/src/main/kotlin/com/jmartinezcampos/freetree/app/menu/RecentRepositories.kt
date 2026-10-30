package com.jmartinezcampos.freetree.app.menu

import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface RecentRepositoriesStore {
    fun load(): List<String>
    fun save(paths: List<String>)
}

internal class PreferencesRecentRepositoriesStore(
    private val preferences: Preferences = Preferences.userRoot()
        .node("com/jmartinezcampos/freetree"),
) : RecentRepositoriesStore {
    override fun load(): List<String> {
        val saved = try {
            preferences.get(RECENT_REPOSITORIES_KEY, null)
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot read recent repositories: ${e.message}")
            return emptyList()
        } ?: return emptyList()

        return saved.split(',').mapNotNull { encoded ->
            try {
                String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                System.err.println("FreeTree: ignoring invalid recent repository entry: ${e.message}")
                null
            }
        }
    }

    override fun save(paths: List<String>) {
        val encoded = paths.joinToString(",") { path ->
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
        }
        try {
            preferences.put(RECENT_REPOSITORIES_KEY, encoded)
            preferences.flush()
        } catch (e: BackingStoreException) {
            System.err.println("FreeTree: cannot save recent repositories: ${e.message}")
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot save recent repositories: ${e.message}")
        }
    }

    private companion object {
        const val RECENT_REPOSITORIES_KEY = "recentRepositories"
    }
}

internal class RecentRepositories(
    private val store: RecentRepositoriesStore = PreferencesRecentRepositoriesStore(),
    private val maxEntries: Int = 10,
) {
    private val mutablePaths = MutableStateFlow(loadPaths())
    val paths: StateFlow<List<Path>> = mutablePaths.asStateFlow()

    fun record(path: Path) {
        if (maxEntries <= 0) return
        val normalized = path.toAbsolutePath().normalize()
        val updated = (listOf(normalized) + mutablePaths.value.filter { it != normalized })
            .take(maxEntries)
        mutablePaths.value = updated
        store.save(updated.map(Path::toString))
    }

    private fun loadPaths(): List<Path> = store.load().mapNotNull { value ->
        try {
            Path.of(value).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            System.err.println("FreeTree: ignoring invalid recent repository path: ${e.message}")
            null
        }
    }.take(maxEntries)
}
