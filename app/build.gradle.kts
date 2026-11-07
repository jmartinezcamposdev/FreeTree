import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.serializationJson)

    implementation(libs.kotlinx.coroutinesSwing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.compose.uiTest)
    testImplementation(libs.compose.uiTestJunit4)
}

// Keep one icon source for the Linux/Windows package icons and the runtime window icon.
tasks.named<ProcessResources>("processResources") {
    from("icons/FreeTree.png")
}

// -Xdock and the Apple AWT properties are only understood by the macOS JVM.
val isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
val isLinuxOs = System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)

compose.desktop {
    application {
        mainClass = "com.jmartinezcampos.freetree.app.MainKt"
        if (isMacOs) {
            jvmArgs("-Xdock:name=FreeTree")
            jvmArgs("-Dapple.awt.application.name=FreeTree")
            jvmArgs("-Dcom.apple.mrj.application.apple.menu.about.name=FreeTree")
        }
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "FreeTree"
            packageVersion = "1.1.0"
            description = "FreeTree is a desktop Git client focused on making repository history easy to read and navigate."
            macOS {
                iconFile.set(project.file("icons/FreeTree.icns"))
            }
            linux {
                packageName = "freetree"
                iconFile.set(project.file("icons/FreeTree.png"))
                appCategory = "Development"
                // jpackage fills the desktop entry's Categories from menuGroup.
                menuGroup = "Development"
                shortcut = true
                rpmLicenseType = "GPL-2.0-or-later"
            }
            windows {
                iconFile.set(project.file("icons/FreeTree.ico"))
                shortcut = true
                menu = true
            }
        }
    }
}

// jpackage has no StartupWMClass option. Without it, Cinnamon tracks the window
// as an unassociated "window:..." app instead of the FreeTree desktop entry.
val patchDebDesktop = tasks.register("patchDebDesktop") {
    group = "compose desktop"
    description = "Adds StartupWMClass to the desktop entry inside the generated .deb."
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val workDir = layout.buildDirectory.dir("compose/tmp/deb-desktop-patch")
    // Local values only: this action must stay serializable for the configuration cache.
    val startupWmClass =
        "StartupWMClass=" + "com.jmartinezcampos.freetree.app.MainKt".replace('.', '-')
    doLast {
        val deb = debDir.get().asFile
            .listFiles { file -> file.isFile && file.extension == "deb" }
            .orEmpty()
            .maxByOrNull { it.lastModified() }
            ?: error("No .deb found in ${debDir.get().asFile}")
        val work = workDir.get().asFile
        work.deleteRecursively()
        work.mkdirs()

        fun run(vararg command: String) {
            val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed ($exit): ${command.joinToString(" ")}" }
        }

        run("dpkg-deb", "-R", deb.absolutePath, work.absolutePath)
        val desktop = work.resolve("opt/freetree/lib/freetree-FreeTree.desktop")
        check(desktop.isFile) { "Desktop entry not found: $desktop" }
        val text = desktop.readText()
        if (text.lineSequence().none { it == startupWmClass }) {
            desktop.writeText(text.replace("[Desktop Entry]\n", "[Desktop Entry]\n$startupWmClass\n"))
        }

        run("dpkg-deb", "--root-owner-group", "-b", work.absolutePath, deb.absolutePath)
    }
}

if (isLinuxOs) {
    tasks.matching { it.name == "packageDeb" }.configureEach {
        finalizedBy(patchDebDesktop)
    }
}

