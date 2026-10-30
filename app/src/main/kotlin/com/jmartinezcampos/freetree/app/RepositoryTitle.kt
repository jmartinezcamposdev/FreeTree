package com.jmartinezcampos.freetree.app

import java.io.File
import java.nio.file.Path

internal const val APP_NAME = "FreeTree"

internal fun windowTitle(repository: Path?, home: Path): String =
    if (repository == null) APP_NAME else "$APP_NAME ${shortRepositoryPath(repository, home)}"

internal fun shortRepositoryPath(repository: Path, home: Path): String {
    val path = repository.toAbsolutePath().normalize()
    val homePath = home.toAbsolutePath().normalize()
    return when {
        path == homePath -> "~"
        path.startsWith(homePath) ->
            "~" + File.separator + homePath.relativize(path).toString()
        else -> path.toString()
    }
}
