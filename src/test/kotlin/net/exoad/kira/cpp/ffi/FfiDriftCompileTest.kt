package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The drift check does its job on the real compilers: forward's `pilot:car`, with one
 * deliberately wrong signature (`finish` returning `Str` where bibo::Car returns
 * `std::int32_t`), is emitted by the real emitter and compiled against the case's
 * `driver/car.hxx`. The build must fail, and fail with Kira's message, on gcc and MSVC
 * (the brief's two), and on clang where it is found. The right signature builds and links.
 */
class FfiDriftCompileTest {
    private val driver = File("src/test/resources/cpp-golden/forward/driver")
    private val runtime = File("kira/cpp")
    private val options = CppOptions(lineDirectives = false, namespaces = mapOf("pilot:car" to "bibo"))

    private fun carModule(finishReturns: String): String = """
        @_extern(cpp = "bibo::Scan", header = "car.hxx")
        pub struct Scan {
            pub fx ahead: () Float32;
        }

        @_extern(cpp = "bibo::Car", header = "car.hxx")
        pub class Car {
            pub mut fx arm: () Bool;
            pub fx ok: () Bool;
            pub fx drivable: () Bool;
            pub mut fx scan: () Scan;
            pub mut fx drive: (throttle: Float32, steer: Float32) Void;
            pub mut fx finish: () $finishReturns;
        }

        @_extern(cpp = "bibo::openCar", header = "car.seam.hxx")
        pub fx openCar: () Car;
    """

    private fun emitHeader(finishReturns: String): String {
        val emitted = DeclTestSupport.emit(DeclTestSupport.module("pilot:car", carModule(finishReturns)), options = options)
        return emitted.header("pilot:car")
    }

    private fun writeUnit(name: String, header: String): File {
        val dir = File("build/tmp/cpp-ffi-drift/$name").apply { deleteRecursively(); mkdirs() }
        File(dir, "car.kira.hxx").writeText(header)
        File(dir, "main.cxx").writeText(
            """
            #include "car.kira.hxx"

            int main()
            {
                return 0;
            }
            """.trimIndent() + "\n"
        )
        return dir
    }

    @TestFactory
    fun onEveryHostToolchain(): List<DynamicNode> {
        val toolchains = listOf(CppToolchain.GCC, CppToolchain.MSVC, CppToolchain.CLANG)
        return toolchains.flatMap { tc ->
            listOf(
                DynamicTest.dynamicTest("${tc.id}: the wrong signature fails with Kira's message") { drift(tc) },
                DynamicTest.dynamicTest("${tc.id}: the right signature builds") { clean(tc) },
            )
        }
    }

    private fun compile(tc: CppToolchain, name: String, header: String): CppCompileSupport.CompileResult {
        org.junit.jupiter.api.Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(tc)
        val dir = writeUnit("$name-${tc.id}", header)
        return CppCompileSupport.compile(
            sources = listOf(File(dir, "main.cxx"), File(driver, "main.cxx")),
            includeDirs = listOf(dir, driver, runtime),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.forToolchain(tc),
            outDir = File(dir, "out"),
        )
    }

    private fun drift(tc: CppToolchain) {
        val header = emitHeader("Str")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(std::declval<bibo::Car&>().finish(), kira::Str, \"Car.finish\");"), header)
        val result = compile(tc, "drift", header)
        assertTrue(!result.success, "${tc.id}: a Kira 'finish: () Str' against C++'s std::int32_t finish() compiled:\n${result.describe()}")
        val wanted = "Kira's Car.finish ${CppExternEmitter.DRIFT_MESSAGE}"
        assertTrue(result.diagnostics.contains(wanted), "${tc.id}: the build failed, but not with '$wanted':\n${result.describe()}")
    }

    private fun clean(tc: CppToolchain) {
        val result = compile(tc, "clean", emitHeader("Int32"))
        if (!result.success) {
            fail("${tc.id}: the right signature does not build:\n${result.describe()}")
        }
    }
}
