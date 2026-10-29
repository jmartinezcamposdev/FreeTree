import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
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

    implementation(libs.kotlinx.coroutinesSwing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.compose.uiTest)
    testImplementation(libs.compose.uiTestJunit4)
}

compose.desktop {
    application {
        mainClass = "com.jmartinezcampos.freetree.app.MainKt"
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FreeTree"
            packageVersion = "1.0.0"
        }
    }
}
