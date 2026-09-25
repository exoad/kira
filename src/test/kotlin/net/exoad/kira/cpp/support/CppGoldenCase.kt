package net.exoad.kira.cpp.support

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * One golden case in the design 5.10 format:
 *
 * ```
 * <case>/kira.yaml            the project the emitter is run on (W2.2's CppGoldenEmitTest)
 * <case>/src/...kira          its sources
 * <case>/expected/...         the generated tree, relative to the case root
 * <case>/driver/main.cxx      a driver printing bibo's check format, plus optional .hxx fakes beside it
 * <case>/expected.txt         the driver's exact stdout
 * <case>/case.yaml            toolchains, profile, defines, emit
 * ```
 *
 * A corpus root may also hold `include/` (a runtime stand-in used by the
 * harness self-test) and directories starting with `_`, which are not cases.
 * Any other directory without a `case.yaml` is an error: a half-written case
 * must not vanish silently.
 */
data class CppGoldenCase(
    val name: String,
    val dir: File,
    val toolchains: List<CppToolchain>,
    val profile: CppProfile,
    val defines: List<String>,
    val emit: String,
) {
    val expectedDir: File get() = File(dir, "expected")
    val driverDir: File get() = File(dir, "driver")
    val expectedText: File get() = File(dir, "expected.txt")
    val kiraYaml: File get() = File(dir, "kira.yaml")

    /** The generated sources and the driver's, in a stable order. */
    fun sources(): List<File> {
        val generated = expectedDir.walkTopDown().filter { it.isFile && it.name.endsWith(".cxx") }.sortedBy { it.path }.toList()
        val driver = driverDir.listFiles { f -> f.isFile && f.name.endsWith(".cxx") }?.sortedBy { it.name } ?: emptyList()
        return generated + driver
    }

    /**
     * Include roots: the case's `expected/` and `driver/`, every directory
     * under `expected/` that holds a header (so a driver may say either
     * `#include "src/x.kira.hxx"` or `#include "x.kira.hxx"`), then the
     * runtime.
     */
    fun includeDirs(runtimeDir: File): List<File> {
        val headerDirs = expectedDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".hxx") || it.name.endsWith(".h") || it.name.endsWith(".hpp")) }
            .map { it.parentFile }
            .distinct()
            .sortedBy { it.path }
            .toList()
        return (listOf(expectedDir, driverDir) + headerDirs + listOf(runtimeDir)).distinctBy { it.absoluteFile.normalize() }
    }

    /** expected.txt with the host's `\r` removed, matching what [CppCompileSupport.run] returns. */
    fun expectedStdout(): String = expectedText.readText().replace("\r", "")

    companion object {
        val emitModes: Set<String> = setOf("pending", "required")

        /** Load `<dir>/case.yaml` and validate the case's files. */
        fun load(dir: File): CppGoldenCase {
            val caseYaml = File(dir, "case.yaml")
            require(caseYaml.isFile) { "${dir.name}: no case.yaml" }
            val raw = Yaml().load<Any?>(caseYaml.readText())
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("${dir.name}/case.yaml: expected a mapping, got ${raw?.javaClass?.simpleName}")

            val toolchainIds = (map["toolchains"] as? List<*>)?.map { it.toString() }
                ?: throw IllegalArgumentException("${dir.name}/case.yaml: 'toolchains' must be a list")
            require(toolchainIds.isNotEmpty()) { "${dir.name}/case.yaml: 'toolchains' is empty; a case that no toolchain builds proves nothing" }
            val toolchains = toolchainIds.map { id ->
                CppToolchain.byId(id) ?: throw IllegalArgumentException("${dir.name}/case.yaml: unknown toolchain '$id' (known: ${CppToolchain.ids})")
            }
            require(toolchains.distinct().size == toolchains.size) { "${dir.name}/case.yaml: duplicate toolchain" }

            val profileId = map["profile"]?.toString() ?: "hosted"
            val profile = CppProfile.byId(profileId)
                ?: throw IllegalArgumentException("${dir.name}/case.yaml: profile must be hosted or freestanding, got '$profileId'")

            val defines = when (val d = map["defines"]) {
                null -> emptyList()
                is List<*> -> d.map { it.toString() }
                else -> throw IllegalArgumentException("${dir.name}/case.yaml: 'defines' must be a list")
            }

            val emit = map["emit"]?.toString() ?: "pending"
            require(emit in emitModes) { "${dir.name}/case.yaml: emit must be pending or required, got '$emit'" }

            val case = CppGoldenCase(dir.name, dir, toolchains, profile, defines, emit)
            require(case.expectedDir.isDirectory) { "${dir.name}: no expected/ directory" }
            require(case.expectedDir.walkTopDown().any { it.isFile }) { "${dir.name}: expected/ is empty" }
            require(File(case.driverDir, "main.cxx").isFile) { "${dir.name}: no driver/main.cxx" }
            require(case.expectedText.isFile) { "${dir.name}: no expected.txt" }
            return case
        }

        /** Every case under [root], sorted by name. */
        fun discover(root: File): List<CppGoldenCase> {
            require(root.isDirectory) { "golden root $root is not a directory" }
            val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
            return dirs
                .filter { !it.name.startsWith("_") && it.name != "include" }
                .map { load(it) }
        }
    }
}
