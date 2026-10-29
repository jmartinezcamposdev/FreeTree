package com.jmartinezcampos.freetree.core.session

import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionManager {
    val tabs: StateFlow<List<TabState>> = MutableStateFlow(emptyList())
    val activeId: StateFlow<String?> = MutableStateFlow(null)
    val activeSession: StateFlow<RepoSession?> = MutableStateFlow(null)

    fun openRepo(path: Path): Unit = TODO("Implemented in Phase 2")

    fun closeTab(id: String): Unit = TODO("Implemented in Phase 2")

    fun activate(id: String): Unit = TODO("Implemented in Phase 2")
}
