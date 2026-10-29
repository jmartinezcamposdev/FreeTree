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
