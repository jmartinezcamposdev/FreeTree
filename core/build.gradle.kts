plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)

    testFixturesImplementation(libs.kotlinx.coroutinesCore)
    testFixturesImplementation(libs.kotlinx.serializationJson)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
}

// Separate from `check`/`build`; run explicitly with `./gradlew bench`.
val bench by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the Phase 4 performance benchmarks on the 50k DAG and the cached 200k repo."
    val testSourceSet = sourceSets.named("test").get()
    dependsOn(testSourceSet.classesTaskName)
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("com.jmartinezcampos.freetree.core.bench.BenchMainKt")
    workingDir = rootProject.layout.projectDirectory.asFile
    systemProperty(
        "freetree.bench.perfRepo",
        rootProject.layout.buildDirectory.dir("perf-repo").get().asFile.absolutePath,
    )
    jvmArgs("-Xms1g", "-Xmx3g", "-XX:+UseG1GC")
}
