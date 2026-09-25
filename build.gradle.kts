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

    // The C++ harness (src/test/kotlin/net/exoad/kira/cpp, see TESTING.md) is
    // steered by these knobs. `-Dkira.*` on the gradlew command line lands on
    // the build JVM, not the forked test JVM, so forward it; and register both
    // the properties and the environment variables as task inputs, so a
    // changed knob re-runs the tests instead of replaying an up-to-date result.
    for (key in listOf("kira.cppGoldenDir", "kira.cppRuntimeDir")) {
        val value = System.getProperty(key) ?: ""
        if (value.isNotEmpty()) systemProperty(key, value)
        inputs.property(key, value)
    }
    for (key in listOf(
        "KIRA_TOOLCHAINS", "KIRA_REQUIRE_TOOLCHAINS", "KIRA_CPP_GOLDEN_DIR",
        "KIRA_CXX_GCC", "KIRA_CXX_CLANG", "KIRA_ZIG", "KIRA_MSVC_VCVARS", "KIRA_ARM_GXX",
    )) {
        inputs.property("env.$key", System.getenv(key) ?: "")
    }
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
