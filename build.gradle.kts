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

// ci/stages.txt: one "stage pattern" per line. CI runs one stage per job with
// ./gradlew test -Pstage=NAME; checkStages fails unless every test class is in exactly one stage.
val stagePatterns: Map<String, List<String>> = file("ci/stages.txt").readLines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { it.split(Regex("\\s+"), limit = 2) }
    .groupBy({ it[0] }, { it[1] })

tasks.test {
    useJUnitPlatform()

    // Skipped tests (an assumeTrue with no Python, say) are listed, never silent.
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // -Pstage=NAME selects one CI stage's classes; an unknown name fails, never runs nothing.
    (project.findProperty("stage") as String?)?.let { stage ->
        val patterns = stagePatterns[stage] ?: throw GradleException("unknown stage '$stage'; known: ${stagePatterns.keys}")
        patterns.forEach { filter.includeTestsMatching(it) }
    }

    // The C++ harness (src/test/kotlin/net/exoad/kira/cpp) is
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
        "KIRA_CXX_GCC", "KIRA_CXX_CLANG", "KIRA_ZIG", "KIRA_MSVC_VCVARS", "KIRA_ARM_GXX", "KIRA_PYTHON", "KIRA_REQUIRE_PYTHON",
    )) {
        inputs.property("env.$key", System.getenv(key) ?: "")
    }
    // The files the harness compiles that live outside src/test: the runtimes
    // (kira/cpp, and kira/py for the py goldens), any golden root and runtime
    // dir named by the knobs above.
    // Without these, editing kira/cpp/kira/*.hxx and running `./gradlew test`
    // replays the previous result as UP-TO-DATE without compiling anything.
    // A missing directory is an empty tree, not an error.
    val cppGoldenRoots = (System.getProperty("kira.cppGoldenDir") ?: System.getenv("KIRA_CPP_GOLDEN_DIR") ?: "")
        .split(File.pathSeparator).map { it.trim() }.filter { it.isNotEmpty() }
    val cppRuntimeDir = listOfNotNull(System.getProperty("kira.cppRuntimeDir")?.trim()?.takeIf { it.isNotEmpty() })
    for ((index, dir) in (listOf("kira/cpp", "kira/py") + cppRuntimeDir + cppGoldenRoots).withIndex()) {
        inputs.files(fileTree(dir))
            .withPropertyName("cppHarnessTree$index")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// Fails unless every test class under src/test is matched by exactly one stage pattern,
// and every pattern matches at least one class, so a new test file is never left out of CI.
tasks.register("checkStages") {
    val root = file("src/test/kotlin")
    val stages = stagePatterns
    inputs.dir(root)
    inputs.file("ci/stages.txt")
    doLast {
        val marker = Regex("@(Test|TestFactory|ParameterizedTest|RepeatedTest)\\b")
        val classes = root.walkTopDown().filter { it.isFile && it.extension == "kt" && marker.containsMatchIn(it.readText()) }
            .map { f ->
                val pkg = Regex("(?m)^package\\s+(\\S+)").find(f.readText())!!.groupValues[1]
                "$pkg.${f.nameWithoutExtension}"
            }.sorted().toList()
        val regexes = stages.flatMap { (stage, ps) -> ps.map { Triple(stage, it, Regex(it.split("*").joinToString(".*") { p -> Regex.escape(p) })) } }
        val problems = mutableListOf<String>()
        for (c in classes) {
            val hits = regexes.filter { it.third.matches(c) }.map { "${it.first}:${it.second}" }
            if (hits.isEmpty()) problems += "in no stage: $c"
            if (hits.size > 1) problems += "in ${hits.size} stages: $c ($hits)"
        }
        for ((stage, pattern, rx) in regexes) if (classes.none { rx.matches(it) }) problems += "matches no class: $stage $pattern"
        if (problems.isNotEmpty()) throw GradleException("ci/stages.txt does not partition the tests:\n" + problems.joinToString("\n"))
        println("checkStages: ${classes.size} test classes, each in exactly one of ${stages.size} stages")
    }
}

// The compiler reads project.kira from the process working directory.
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
