package net.exoad.kira.cpp

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the warning contract of design 8.2 is really applied: a fixture
 * holding one known warning (an unused parameter: `-Wextra` on the GNU
 * family, C4100 under `/W4`) must FAIL to compile under every toolchain,
 * while the same fixture without the warning compiles. Without the control
 * a broken flag line would pass this test for the wrong reason.
 *
 * Under `KIRA_REQUIRE_TOOLCHAINS=1` a missing toolchain fails the test;
 * otherwise it skips, visibly, naming the toolchain.
 */
class CppWarningContractTest {
    private val fixtureDir = File("src/test/resources/cpp-harness-selftest/_warning-contract")
    private val warning = File(fixtureDir, "warns.cxx")
    private val clean = File(fixtureDir, "clean.cxx")
    private val include = File("src/test/resources/cpp-harness-selftest/include")

    @TestFactory
    fun everyToolchainRejectsTheWarning(): List<DynamicNode> {
        return CppToolchain.entries.map { toolchain ->
            DynamicTest.dynamicTest("${toolchain.id} rejects an unused parameter") {
                check(toolchain)
            }
        }
    }

    private fun check(toolchain: CppToolchain) {
        Assumptions.assumeTrue(CppToolchains.isEnabled(toolchain), "toolchain '${toolchain.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(toolchain)
        assertTrue(warning.isFile && clean.isFile, "fixture missing under $fixtureDir")

        val base = File("build/tmp/cpp-harness/warning-contract/${toolchain.id}")

        val control = CppCompileSupport.compile(
            sources = listOf(clean),
            includeDirs = listOf(include),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.HOSTED,
            outDir = File(base, "clean"),
        )
        assertTrue(control.success, "${toolchain.id}: the clean control fixture must compile\n${control.describe()}")

        val result = CppCompileSupport.compile(
            sources = listOf(warning),
            includeDirs = listOf(include),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.HOSTED,
            outDir = File(base, "warns"),
        )
        assertFalse(
            result.success,
            "${toolchain.id}: the warning fixture compiled, so -Werror / /WX is not applied\n${result.describe()}"
        )
        val diagnostics = result.diagnostics
        assertTrue(
            diagnostics.contains("unused parameter") || diagnostics.contains("C4100"),
            "${toolchain.id}: the compile failed, but not on the unused parameter\n${result.describe()}"
        )
        assertTrue(result.exe == null, "${toolchain.id}: a failed compile must leave no executable")
        assertTrue(
            result.objects.none { it.name.startsWith("0_warns") || it.name == "warns.obj" },
            "${toolchain.id}: a failed compile must leave no object for the warning TU: ${result.objects}"
        )
    }
}
