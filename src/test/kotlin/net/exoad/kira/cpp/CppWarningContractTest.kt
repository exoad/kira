package net.exoad.kira.cpp

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the warning contract of design 8.2 is really applied, flag by flag.
 * Each fixture under `_warning-contract/` holds exactly one warning that one
 * contracted flag produces, and must FAIL to compile under every toolchain
 * whose contract line carries that flag:
 *
 * | fixture | flag | rejected by |
 * |---|---|---|
 * | `unused-parameter.cxx` | `-Wextra`, `/W4` (C4100) | all five |
 * | `unused-variable.cxx` | `-Wall`, `/W4` (C4189) | all five |
 * | `conversion.cxx` | `-Wconversion`, `/W4` (C4244) | all five |
 * | `sign-conversion.cxx` | `-Wsign-conversion` | gcc, clang, zig-aarch64 |
 * | `shadow.cxx` | `-Wshadow`, `/W4` (C4456) | gcc, clang, zig-aarch64, msvc |
 * | `non-virtual-dtor.cxx` | `-Wnon-virtual-dtor` | gcc, clang, zig-aarch64 |
 *
 * `arm` carries only `-Wall -Wextra -Wconversion` and MSVC has no `/W4`
 * warning for signed/unsigned conversion (C4365) or a non-virtual destructor
 * (C4265): both are off by default, so the design does not contract them.
 *
 * The control `clean.cxx` uses a C++20 `concept` and must compile under every
 * toolchain: it proves `-std=c++20` / `/std:c++20` and that a fixture fails
 * for its warning, not for a broken include path or flag. Every failure is
 * also checked to name the expected diagnostic, so a fixture cannot pass by
 * failing on something else.
 *
 * Under `KIRA_REQUIRE_TOOLCHAINS=1` a missing toolchain fails the test;
 * otherwise it skips, visibly, naming the toolchain.
 */
class CppWarningContractTest {
    private val fixtureDir = File("src/test/resources/cpp-harness-selftest/_warning-contract")
    private val include = File("src/test/resources/cpp-harness-selftest/include")
    private val clean = File(fixtureDir, "clean.cxx")

    /** One contracted warning: the fixture, the flag, who must reject it, and what the diagnostic must contain. */
    data class Fixture(val file: String, val flag: String, val rejectedBy: Set<CppToolchain>, val marks: List<String>)

    private val all = CppToolchain.entries.toSet()
    private val gnu = setOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.ZIG_AARCH64)

    val fixtures: List<Fixture> = listOf(
        Fixture("unused-parameter.cxx", "-Wextra / C4100", all, listOf("unused parameter", "C4100")),
        Fixture("unused-variable.cxx", "-Wall / C4189", all, listOf("unused variable", "C4189")),
        Fixture("conversion.cxx", "-Wconversion / C4244", all, listOf("conversion", "C4244")),
        Fixture("sign-conversion.cxx", "-Wsign-conversion", gnu, listOf("sign-conversion")),
        Fixture("shadow.cxx", "-Wshadow / C4456", gnu + CppToolchain.MSVC, listOf("shadow", "C4456")),
        Fixture("non-virtual-dtor.cxx", "-Wnon-virtual-dtor", gnu, listOf("non-virtual-dtor")),
    )

    @TestFactory
    fun everyToolchainRejectsEachContractedWarning(): List<DynamicNode> {
        return CppToolchain.entries.map { toolchain ->
            val tests = ArrayList<DynamicNode>()
            tests += DynamicTest.dynamicTest("${toolchain.id} compiles the clean C++20 control") {
                checkControl(toolchain)
            }
            for (fixture in fixtures.filter { toolchain in it.rejectedBy }) {
                tests += DynamicTest.dynamicTest("${toolchain.id} rejects ${fixture.file} (${fixture.flag})") {
                    checkRejects(toolchain, fixture)
                }
            }
            DynamicContainer.dynamicContainer(toolchain.id, tests)
        }
    }

    private fun located(toolchain: CppToolchain) = run {
        Assumptions.assumeTrue(CppToolchains.isEnabled(toolchain), "toolchain '${toolchain.id}' is disabled by KIRA_TOOLCHAINS")
        CppToolchains.requireOrSkip(toolchain)
    }

    private fun outDir(toolchain: CppToolchain, leaf: String) = File("build/tmp/cpp-harness/warning-contract/${toolchain.id}/$leaf")

    private fun checkControl(toolchain: CppToolchain) {
        val tc = located(toolchain)
        assertTrue(clean.isFile, "control fixture missing: $clean")
        val control = CppCompileSupport.compile(
            sources = listOf(clean),
            includeDirs = listOf(include),
            defines = emptyList(),
            toolchain = tc,
            profile = CppProfile.forToolchain(toolchain),
            outDir = outDir(toolchain, "clean"),
        )
        assertTrue(control.success, "${toolchain.id}: the clean C++20 control must compile\n${control.describe()}")
    }

    private fun checkRejects(toolchain: CppToolchain, fixture: Fixture) {
        val tc = located(toolchain)
        val source = File(fixtureDir, fixture.file)
        assertTrue(source.isFile, "fixture missing: $source")

        val result = CppCompileSupport.compile(
            sources = listOf(source),
            includeDirs = listOf(include),
            defines = emptyList(),
            toolchain = tc,
            profile = CppProfile.forToolchain(toolchain),
            outDir = outDir(toolchain, source.nameWithoutExtension),
        )
        assertFalse(
            result.success,
            "${toolchain.id}: ${fixture.file} compiled, so ${fixture.flag} (or -Werror / /WX) is not applied\n${result.describe()}"
        )
        val diagnostics = result.diagnostics
        assertTrue(
            fixture.marks.any { diagnostics.contains(it) },
            "${toolchain.id}: ${fixture.file} failed, but not on ${fixture.flag} (expected one of ${fixture.marks})\n${result.describe()}"
        )
        assertTrue(result.exe == null, "${toolchain.id}: a failed compile must leave no executable")
        assertTrue(
            result.objects.none { it.name.startsWith("0_" + source.nameWithoutExtension) },
            "${toolchain.id}: a failed compile must leave no object for the warning TU: ${result.objects}"
        )
    }
}
