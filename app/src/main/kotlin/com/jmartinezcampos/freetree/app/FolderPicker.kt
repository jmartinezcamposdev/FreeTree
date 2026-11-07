package com.jmartinezcampos.freetree.app

import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser

internal fun interface FolderPicker {
    fun pickFolder(owner: java.awt.Window?): Path?
}

internal class NativeFolderPicker : FolderPicker {
    private var lastDirectory: Path? = null

    override fun pickFolder(owner: java.awt.Window?): Path? {
        val selected = when {
            isMac() -> pickWithAwt(owner)
            isWindows() -> pickWithWindowsShell()
            else -> pickWithLinuxDialog()
        }
        val path = selected ?: return null
        lastDirectory = path
        return path
    }

    private fun pickWithAwt(owner: java.awt.Window?): Path? {
        val previous = System.getProperty(MAC_DIRECTORY_PROPERTY)
        System.setProperty(MAC_DIRECTORY_PROPERTY, "true")
        return try {
            val dialog = FileDialog(owner as? Frame, "Open Git repository", FileDialog.LOAD)
            lastDirectory?.let { dialog.directory = it.toString() }
            dialog.isMultipleMode = false
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            Path.of(directory, file).toAbsolutePath().normalize()
        } catch (e: Exception) {
            System.err.println("FreeTree: native folder picker failed: ${e.message}")
            fallback(owner)
        } finally {
            if (previous == null) {
                System.clearProperty(MAC_DIRECTORY_PROPERTY)
            } else {
                System.setProperty(MAC_DIRECTORY_PROPERTY, previous)
            }
        }
    }

    private fun pickWithWindowsShell(): Path? {
        val initial = lastDirectory?.toString()?.replace("'", "''").orEmpty()
        val script = buildString {
            appendLine("Add-Type -AssemblyName System.Windows.Forms")
            appendLine("\$dialog = New-Object System.Windows.Forms.FolderBrowserDialog")
            appendLine("\$dialog.Description = 'Open Git repository'")
            appendLine("\$dialog.ShowNewFolderButton = \$true")
            if (initial.isNotEmpty()) {
                appendLine("\$dialog.SelectedPath = '$initial'")
            }
            appendLine("if (\$dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {")
            appendLine("  Write-Output \$dialog.SelectedPath")
            appendLine("}")
        }
        val output = runProcess(
            listOf("powershell.exe", "-NoProfile", "-STA", "-Command", script),
        ) ?: return fallback(null)
        return output.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    }

    private fun pickWithLinuxDialog(): Path? {
        val initial = lastDirectory?.toString().orEmpty()
        if (commandAvailable("zenity")) {
            val args = buildList {
                add("zenity")
                add("--file-selection")
                add("--directory")
                add("--title=Open Git repository")
                if (initial.isNotEmpty()) add("--filename=$initial/")
            }
            return runProcess(args)?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        }
        if (commandAvailable("kdialog")) {
            val args = buildList {
                add("kdialog")
                add("--getexistingdirectory")
                if (initial.isNotEmpty()) add(initial)
                add("--title")
                add("Open Git repository")
            }
            return runProcess(args)?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        }
        return fallback(null)
    }

    private fun fallback(owner: java.awt.Window?): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Open Git repository"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            lastDirectory?.let { currentDirectory = it.toFile() }
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.toPath()
        } else {
            null
        }
    }

    private fun runProcess(args: List<String>): String? {
        val process = try {
            ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (e: IOException) {
            return null
        }
        return try {
            if (!process.waitFor(PICKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun commandAvailable(command: String): Boolean = runProcess(
        listOf("sh", "-c", "command -v '$command' >/dev/null 2>&1 && printf yes"),
    )?.trim() == "yes"

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private companion object {
        const val MAC_DIRECTORY_PROPERTY = "apple.awt.fileDialogForDirectories"
        const val PICKER_TIMEOUT_SECONDS = 300L
    }
}
