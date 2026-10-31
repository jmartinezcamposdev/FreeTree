package com.jmartinezcampos.freetree.app.menu

import com.jmartinezcampos.freetree.app.ui.AppSettingsController
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface RecentRepositoriesStore {
    fun load(): List<String>
    fun save(paths: List<String>)
}

internal class SettingsRecentRepositoriesStore(
    private val settingsController: AppSettingsController,
) : RecentRepositoriesStore {
    override fun load(): List<String> = settingsController.state.value.recentRepositories

    override fun save(paths: List<String>) {
        settingsController.setRecentRepositories(paths)
    }
}

internal class RecentRepositories(
    private val store: RecentRepositoriesStore,
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
