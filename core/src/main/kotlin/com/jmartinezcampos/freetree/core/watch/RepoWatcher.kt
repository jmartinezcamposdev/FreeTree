package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.model.GitDirs

interface RepoWatcher {
    fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit)
    fun stop()
}

enum class WatchEvent { REFS_CHANGED, STATUS_CHANGED }
