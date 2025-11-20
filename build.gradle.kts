plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0-RC"
}

group = "net.exoad"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
val flatlafVersion = "3.6"

dependencies {
    testImplementation(kotlin("test"))
    // flatlaf used for the internal editor ui
    implementation("com.formdev:flatlaf:${flatlafVersion}")
    implementation("com.formdev:flatlaf-intellij-themes:${flatlafVersion}")
    implementation("com.akuleshov7:ktoml-core:0.7.1")
    implementation("com.akuleshov7:ktoml-file:0.7.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
