package net.exoad.kira.cpp.support

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The harness's own contracts, each pinned by a test that would go red if the
 * harness became lenient again:
 *
 * - the profile follows the toolchain (design 8.2): `compile` refuses a
 *   freestanding build on a host toolchain and a hosted one on `arm`, and a
 *   hosted case may not list `arm`;
 * - the include roots are `expected/`, `driver/` and the runtime, nothing
 *   else (design 4.3), so a wrongly spelled cross-module include fails;
 * - `nmCheck` really rejects: `new[]`/`delete[]`, scalar `new`/`delete`
 *   and `malloc` in an arm object are all reported, and a clean object is
 *   not. On 32-bit arm-none-eabi `size_t` mangles as `j`, so the symbols are
 *   `_Znaj`, `_ZdaPv`, `_Znwj`, `_ZdlPv`: the ones a pattern written for x64
 *   (`_Znam`) misses.
 */
class CppHarnessSelfTest {
    private val selftest = File("src/test/resources/cpp-harness-selftest")
    private val include = File(selftest, "include")
    private val nmFixtures = File(selftest, "_nm-check")

    private fun fakeFound(toolchain: CppToolchain) =
        LocatedToolchain.Found(toolchain, listOf(toolchain.id), null, "fake", "test")

    private val anySource = File(selftest, "_warning-contract/clean.cxx")

    @Test
    fun theProfileFollowsTheToolchain() {
        assertEquals(CppProfile.FREESTANDING, CppProfile.forToolchain(CppToolchain.ARM))
        for (tc in CppToolchain.entries.filter { it != CppToolchain.ARM }) {
            assertEquals(CppProfile.HOSTED, CppProfile.forToolchain(tc), tc.id)
        }
    }

    @Test
    fun compileRefusesAFreestandingBuildOnAHostToolchain() {
        for (tc in CppToolchain.entries.filter { it != CppToolchain.ARM }) {
            val e = assertFailsWith<IllegalArgumentException>(tc.id) {
                CppCompileSupport.compile(
                    listOf(anySource), listOf(include), emptyList(), fakeFound(tc), CppProfile.FREESTANDING,
                    File("build/tmp/cpp-harness/selftest/refused"),
                )
            }
            assertTrue(e.message!!.contains("hosted profile only"), e.message)
        }
        val e = assertFailsWith<IllegalArgumentException> {
            CppCompileSupport.compile(
                listOf(anySource), listOf(include), emptyList(), fakeFound(CppToolchain.ARM), CppProfile.HOSTED,
                File("build/tmp/cpp-harness/selftest/refused"),
            )
        }
        assertTrue(e.message!!.contains("freestanding profile only"), e.message)
    }

    @Test
    fun compileRefusesAProfileDefineFromTheCaller() {
        val e = assertFailsWith<IllegalArgumentException> {
            CppCompileSupport.compile(
                listOf(anySource), listOf(include), listOf("KIRA_PROFILE_FREESTANDING=1"),
                fakeFound(CppToolchain.GCC), CppProfile.HOSTED, File("build/tmp/cpp-harness/selftest/refused"),
            )
        }
        assertTrue(e.message!!.contains("not a case define"), e.message)
    }

    @Test
    fun aHostedCaseMayNotListArm() {
        val dir = File("build/tmp/cpp-harness/selftest/hosted-arm").apply { deleteRecursively(); mkdirs() }
        File(dir, "case.yaml").writeText("toolchains: [gcc, arm]\nprofile: hosted\n")
        File(dir, "expected").mkdirs()
        File(dir, "expected/x.kira.hxx").writeText("#pragma once\n")
        File(dir, "driver").mkdirs()
        File(dir, "driver/main.cxx").writeText("int main() { return 0; }\n")
        File(dir, "expected.txt").writeText("")
        val e = assertFailsWith<IllegalArgumentException> { CppGoldenCase.load(dir) }
        assertTrue(e.message!!.contains("'arm' builds the freestanding profile only"), e.message)

        File(dir, "case.yaml").writeText("toolchains: [gcc, arm]\nprofile: freestanding\ndefines: [KIRA_PROFILE_FREESTANDING=1]\n")
        val e2 = assertFailsWith<IllegalArgumentException> { CppGoldenCase.load(dir) }
        assertTrue(e2.message!!.contains("not a case define"), e2.message)

        File(dir, "case.yaml").writeText("toolchains: [gcc, arm]\nprofile: freestanding\n")
        assertEquals(CppProfile.FREESTANDING, CppGoldenCase.load(dir).profile)
    }

    @Test
    fun includeRootsAreExpectedDriverAndTheRuntimeOnly() {
        val case = CppGoldenCase.load(File(selftest, "hello"))
        val runtime = File("some/runtime")
        assertEquals(listOf(case.expectedDir, case.driverDir, runtime), case.includeDirs(runtime))
        // The case's header lives in expected/src, and that directory is not a root.
        assertTrue(File(case.expectedDir, "src/hello.kira.hxx").isFile)
        assertTrue(case.includeDirs(runtime).none { it.name == "src" })
    }

    private fun armObject(fixture: String): Pair<LocatedToolchain.Found, File> {
        Assumptions.assumeTrue(CppToolchains.isEnabled(CppToolchain.ARM), "toolchain 'arm' is disabled by KIRA_TOOLCHAINS")
        val arm = CppToolchains.requireOrSkip(CppToolchain.ARM)
        val source = File(nmFixtures, fixture)
        assertTrue(source.isFile, "fixture missing: $source")
        val result = CppCompileSupport.compile(
            listOf(source), listOf(include), emptyList(), arm, CppProfile.FREESTANDING,
            File("build/tmp/cpp-harness/selftest/nm/${source.nameWithoutExtension}"),
        )
        assertTrue(result.success, "the nm fixture must compile on arm\n${result.describe()}")
        assertEquals(1, result.objects.size, result.objects.toString())
        return arm to result.objects.single()
    }

    @Test
    fun nmCheckRejectsArrayNewAndDelete() {
        val (arm, obj) = armObject("heap-array.cxx")
        val nm = CppCompileSupport.nmCheck(obj, arm)
        assertTrue(nm.forbidden.any { it.startsWith("_Zna") }, "operator new[] not reported: symbols ${nm.symbols}")
        assertTrue(nm.forbidden.any { it.startsWith("_Zda") }, "operator delete[] not reported: symbols ${nm.symbols}")
    }

    @Test
    fun nmCheckRejectsScalarNewAndDelete() {
        val (arm, obj) = armObject("heap-scalar.cxx")
        val nm = CppCompileSupport.nmCheck(obj, arm)
        assertTrue(nm.forbidden.any { it.startsWith("_Znw") }, "operator new not reported: symbols ${nm.symbols}")
        assertTrue(nm.forbidden.any { it.startsWith("_Zdl") }, "operator delete not reported: symbols ${nm.symbols}")
    }

    @Test
    fun nmCheckRejectsMalloc() {
        val (arm, obj) = armObject("heap-malloc.cxx")
        val nm = CppCompileSupport.nmCheck(obj, arm)
        assertTrue(nm.forbidden.any { it.endsWith("malloc") }, "malloc not reported: symbols ${nm.symbols}")
        assertTrue(nm.forbidden.any { it.endsWith("free") }, "free not reported: symbols ${nm.symbols}")
    }

    @Test
    fun nmCheckAcceptsACleanObject() {
        val (arm, obj) = armObject("clean.cxx")
        val nm = CppCompileSupport.nmCheck(obj, arm)
        assertTrue(nm.clean, "clean object reported ${nm.forbidden}")
        // kira::panic stays undefined in a freestanding object (design 10): the board defines it.
        assertTrue(nm.symbols.any { it.contains("5panic") }, "expected an undefined kira::panic: ${nm.symbols}")
    }
}
