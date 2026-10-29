package com.jmartinezcampos.freetree

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FreeTree",
    ) {
        App()
    }
}