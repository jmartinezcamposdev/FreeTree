package com.jmartinezcampos.freetree.app.menu

import java.nio.file.Path

internal sealed interface AppCommand {
    data object OpenRepository : AppCommand
    data class OpenRecent(val path: Path) : AppCommand
    data object CloseTab : AppCommand
    data object Exit : AppCommand
    data object Reload : AppCommand
    data object Pull : AppCommand
    data object Push : AppCommand
    data object StageAll : AppCommand
    data object UnstageAll : AppCommand
    data object DiscardUnstaged : AppCommand
    data object ShowRemotes : AppCommand
    data object ShowTags : AppCommand
    data object NextTab : AppCommand
    data object PreviousTab : AppCommand
    data object Minimize : AppCommand
    data object About : AppCommand
    data object MessagesLog : AppCommand
    data object Preferences : AppCommand
}
