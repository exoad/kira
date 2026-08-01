plugins {
    application
    kotlin("jvm") version "2.3.20"
}

application {
    mainClass.set("net.exoad.kira.cli.MainKt")
    applicationName = "kira"
}

group = "net.exoad"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.yaml:snakeyaml:2.2")
    // Language Server Protocol (stdio JSON-RPC)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
}

tasks.test {
    useJUnitPlatform()
}

// Compiler reads kira.yaml from the process working directory.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("test_kira")
}

// Second application script: language server over stdio.
// installDist ships both `kira` and `kira-lsp` under build/install/kira/bin/.
tasks.register<CreateStartScripts>("startLspScripts") {
    applicationName = "kira-lsp"
    mainClass.set("net.exoad.kira.lsp.LspMainKt")
    classpath = tasks.jar.get().outputs.files + configurations.runtimeClasspath.get()
    outputDir = layout.buildDirectory.dir("scripts-lsp").get().asFile
}

tasks.named<Sync>("installDist") {
    dependsOn("startLspScripts")
    from(tasks.named("startLspScripts")) {
        into("bin")
    }
}

kotlin {
    jvmToolchain(17)
}
