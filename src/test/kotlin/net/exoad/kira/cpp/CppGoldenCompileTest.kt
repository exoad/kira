package net.exoad.kira.cpp

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppGoldenCase
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * For every golden case (design 5.10) and every toolchain its case.yaml
 * names: compile the `expected/` tree plus the driver against the runtime, run it
 * where the toolchain links, and diff stdout with expected.txt. Compile-only
 * toolchains (zig-aarch64, arm) prove the tree builds; arm objects are also
 * nm-checked for heap, exception and RTTI symbols.
 *
 * Roots: `-Dkira.cppGoldenDir=<dir>[<pathsep><dir>...]` (or
 * `KIRA_CPP_GOLDEN_DIR`); by default `src/test/resources/cpp-golden` (W1.3's
 * corpus) and `src/test/resources/cpp-harness-selftest` (this harness's own
 * fixture). Every root must yield at least one case. A default root that is
 * absent from the checkout is a failure under `KIRA_REQUIRE_TOOLCHAINS=1`
 * and a visible skip otherwise.
 *
 * Runtime include dir: a root that brings its own `include/` always uses it
 * (the self-test's stand-in has an API of its own that the real runtime does
 * not share); otherwise `-Dkira.cppRuntimeDir`, else `kira/cpp`. So the
 * property redirects the corpus and leaves the self-test alone.
 *
 * Profile: the toolchain's (design 8.2), never the case's. `arm` compiles
 * freestanding, everything else hosted; see [CppProfile.forToolchain].
 */
class CppGoldenCompileTest {
    companion object {
        val defaultRoots: List<File> = listOf(
            File("src/test/resources/cpp-golden"),
            File("src/test/resources/cpp-harness-selftest"),
        )

        fun configuredRoots(): Pair<List<File>, Boolean> {
            val raw = System.getProperty("kira.cppGoldenDir")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: System.getenv("KIRA_CPP_GOLDEN_DIR")?.trim().takeUnless { it.isNullOrEmpty() }
            if (raw == null) return defaultRoots to false
            val roots = raw.split(File.pathSeparatorChar).map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
            return roots to true
        }

        fun runtimeDirFor(root: File): File {
            val own = File(root, "include")
            if (own.isDirectory) return own
            System.getProperty("kira.cppRuntimeDir")?.trim()?.takeIf { it.isNotEmpty() }?.let { return File(it) }
            return File("kira/cpp")
        }

        fun outDirFor(root: File, case: CppGoldenCase, toolchain: CppToolchain): File =
            File("build/tmp/cpp-harness/${root.name}/${case.name}/${toolchain.id}")
    }

    @TestFactory
    fun goldenCases(): List<DynamicNode> {
        val (roots, explicit) = configuredRoots()
        val nodes = ArrayList<DynamicNode>()
        var caseCount = 0
        for (root in roots) {
            if (!root.isDirectory) {
                nodes += DynamicTest.dynamicTest("corpus present: $root") {
                    val message = "golden root $root does not exist" +
                        (if (explicit) "" else " (W1.3 writes it; -Dkira.cppGoldenDir points elsewhere)")
                    if (CppToolchains.required || explicit) fail(message)
                    Assumptions.assumeTrue(false, message)
                }
                continue
            }
            val cases = CppGoldenCase.discover(root)
            caseCount += cases.size
            nodes += DynamicTest.dynamicTest("corpus has cases: $root") {
                assertTrue(cases.isNotEmpty(), "no case under $root: a corpus that runs nothing proves nothing")
            }
            val runtimeDir = runtimeDirFor(root)
            nodes += DynamicContainer.dynamicContainer(
                root.name,
                cases.map { case -> caseContainer(root, case, runtimeDir) }
            )
        }
        nodes += DynamicTest.dynamicTest("at least one golden case exists") {
            assertTrue(caseCount > 0, "no golden case under any of $roots\n${CppToolchains.inventory()}")
        }
        return nodes
    }

    private fun caseContainer(root: File, case: CppGoldenCase, runtimeDir: File): DynamicNode {
        val tests = case.toolchains.map { toolchain ->
            DynamicTest.dynamicTest("${case.name} [${toolchain.id}]") {
                runCase(root, case, toolchain, runtimeDir)
            }
        }
        return DynamicContainer.dynamicContainer(case.name, tests)
    }

    private fun runCase(root: File, case: CppGoldenCase, toolchain: CppToolchain, runtimeDir: File) {
        Assumptions.assumeTrue(CppToolchains.isEnabled(toolchain), "toolchain '${toolchain.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(toolchain)
        assertTrue(runtimeDir.isDirectory, "runtime include dir $runtimeDir does not exist (set -Dkira.cppRuntimeDir)")

        val outDir = outDirFor(root, case, toolchain)
        val result = CppCompileSupport.compile(
            sources = case.sources(),
            includeDirs = case.includeDirs(runtimeDir),
            defines = case.defines,
            toolchain = located,
            profile = CppProfile.forToolchain(toolchain),
            outDir = outDir,
        )
        assertTrue(result.success, "${case.name}: compile failed under ${toolchain.id}\n${result.describe()}")

        if (toolchain.compileOnly) {
            assertTrue(result.objects.isNotEmpty(), "${case.name}: ${toolchain.id} compiled nothing")
            if (toolchain == CppToolchain.ARM) {
                for (obj in result.objects) {
                    val nm = CppCompileSupport.nmCheck(obj, located)
                    assertTrue(
                        nm.clean,
                        "${case.name}: freestanding object ${obj.name} references forbidden symbols ${nm.forbidden}"
                    )
                }
            }
            return
        }

        val exe = result.exe ?: fail("${case.name}: ${toolchain.id} reported success without an executable")
        val run = CppCompileSupport.run(exe, workingDir = outDir, extraPathDirs = listOfNotNull(located.binDir))
        assertTrue(!run.timedOut, "${case.name}: ${toolchain.id} program timed out")
        assertEquals(
            case.expectedStdout(), run.stdout,
            "${case.name}: stdout differs from expected.txt under ${toolchain.id} (exit ${run.exitCode})\nstderr:\n${run.stderr}"
        )
        assertEquals(0, run.exitCode, "${case.name}: program exited ${run.exitCode} under ${toolchain.id}\nstderr:\n${run.stderr}")
    }
}
