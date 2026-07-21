plugins {
    antlr
    application
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
}

application {
    mainClass.set("net.exoad.kira.cli.MainKt")
}

group = "net.exoad"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
val flatlafVersion = "3.6"

dependencies {
    testImplementation(kotlin("test"))
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.yaml:snakeyaml:2.2")
    // flatlaf used for the internal editor ui
    implementation("com.formdev:flatlaf:${flatlafVersion}")
    implementation("com.formdev:flatlaf-intellij-themes:${flatlafVersion}")
}

tasks.generateGrammarSource {
    arguments = arguments + listOf(
        "-visitor",
        "-package",
        "net.exoad.kira.compiler.frontend.parser.antlr.generated"
    )
}

tasks.generateTestGrammarSource {
    arguments = arguments + listOf(
        "-visitor",
        "-package",
        "net.exoad.kira.compiler.frontend.parser.antlr.generated"
    )
}

sourceSets {
    main {
        java.srcDir(tasks.generateGrammarSource.get().outputDirectory)
    }
    test {
        java.srcDir(tasks.generateTestGrammarSource.get().outputDirectory)
    }
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

tasks.test {
    useJUnitPlatform()
}

// Compiler reads kira.yaml from the process working directory. Default the run
// task at the in-repo sample project so `./gradlew run` works out of the box.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("test_kira")
}

kotlin {
    jvmToolchain(17)
}
