package com.jmartinezcampos.freetree.app

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryTitleTest {
    private val home = Path.of(System.getProperty("java.io.tmpdir")).resolve("freetree-home")

    @Test
    fun noActiveRepositoryUsesTheProductName() {
        assertEquals("FreeTree", windowTitle(null, home))
    }

    @Test
    fun repositoryUnderTheUserHomeUsesTilde() {
        val repository = home.resolve("GitProjects").resolve("FreeTree")
        val expected = "FreeTree ~${File.separator}GitProjects${File.separator}FreeTree"

        assertEquals(expected, windowTitle(repository, home))
    }

    @Test
    fun userHomeItselfUsesTilde() {
        assertEquals("FreeTree ~", windowTitle(home, home))
    }

    @Test
    fun repositoryOutsideTheUserHomeKeepsItsFullPath() {
        val repository = home.resolveSibling("other-repo")

        assertEquals("FreeTree ${repository.toAbsolutePath().normalize()}", windowTitle(repository, home))
    }

    @Test
    fun siblingWithTheSamePrefixIsNotMistakenForTheHomeDirectory() {
        val repository = home.resolveSibling("${home.fileName}-other")

        assertEquals("FreeTree ${repository.toAbsolutePath().normalize()}", windowTitle(repository, home))
    }
}
