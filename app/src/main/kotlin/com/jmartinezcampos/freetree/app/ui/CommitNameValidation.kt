package com.jmartinezcampos.freetree.app.ui

internal fun validateRefName(raw: String, isBranch: Boolean): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "Name cannot be empty."
    if (name.startsWith("-")) return "Name cannot start with '-'."
    if (name.endsWith("/") || name.endsWith(".")) return "Name cannot end with '/' or '.'."
    if (name.contains("..") || name.contains("//")) return "Name cannot contain '..' or '//'."
    if (name.endsWith(".lock")) return "Name cannot end with '.lock'."
    if (name.any { it in " ~^:?*[\\" }) return "Name contains characters that are not allowed."
    if (name.contains("@{") || name == "@") return "Name contains characters that are not allowed."
    if (name.any { it.code < 32 || it.code == 127 }) return "Name contains control characters."
    if (isBranch && name == "HEAD") return "HEAD is not a valid branch name."
    return null
}
