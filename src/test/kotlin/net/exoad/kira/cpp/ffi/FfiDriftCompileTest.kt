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
 * The drift checks do their job on the real compilers, with headers the real emitter wrote.
 *
 * - forward's `pilot:car`, with one deliberately wrong signature (`finish` returning `Str`
 *   where bibo::Car returns `std::int32_t`), compiled against the case's `driver/car.hxx`:
 *   the build must fail, and fail with Kira's message, on gcc and MSVC (the brief's two),
 *   and on clang where it is found. The right signature builds and links.
 * - a C struct `Pt { int32_t x; int32_t y; }` declared in Kira with `Float32` fields: the
 *   same size, and `int` converts to `float`, so only the exact field check catches it; and
 *   declared with its two `Int32` fields in the other order, which only the offset check
 *   catches. The right declaration builds.
 * - that right declaration reaches `pt.h` through `c =` (design 7.3), and `pt.h` has no
 *   `__cplusplus` guard: it builds only because the generated header includes it inside
 *   `extern "C" { }`, since the driver defines `pt_len` with C linkage and an unwrapped
 *   include would declare it with C++ linkage first.
 * - a C struct `PtCfg { PtMode mode; int32_t n; }` with a typedef'd enum field, declared
 *   in Kira as `mode: Int32` (an extern enum is not a Kira declaration): it builds, since
 *   the field check takes an unscoped enum of the integer's size; declared `Int16`, the
 *   twin pads to the same 8 bytes and only the field's size gate catches it.
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

    /** Kira's view of `pt.h`: the fields as given, and the C function through `c =`. */
    private fun ptModule(fields: String): String = """
        @_extern(c = "Pt", header = "pt.h")
        pub struct Pt {
            $fields
        }

        @_extern(c = "pt_len", header = "pt.h")
        pub fx ptLen: (s: CStr) Int32;
    """

    /** Kira's view of `pt.h`'s `PtCfg`, whose first field is a C enum: declared as the integer given. */
    private fun cfgModule(modeType: String): String = """
        @_extern(c = "PtCfg", header = "pt.h")
        pub struct Cfg {
            pub mode: $modeType = 0
            pub n: Int32 = 0
        }
    """

    /**
     * A C header as C libraries ship them, minus the `__cplusplus` guard. `PtCfg` holds a
     * typedef'd enum, which gcc gives the underlying type `unsigned int` and MSVC `int`.
     */
    private val ptHeader = """
        #ifndef PT_H
        #define PT_H
        #include <stdint.h>
        struct Pt {
            int32_t x;
            int32_t y;
        };
        int32_t pt_len(const char* s);
        typedef enum { PT_OFF, PT_ON } PtMode;
        struct PtCfg {
            PtMode mode;
            int32_t n;
        };
        #endif
    """.trimIndent() + "\n"

    private val cfgMain = """
        #include "cfg.kira.hxx"

        int main()
        {
            PtCfg c{PT_ON, 3};
            int32_t mode = c.mode;
            return mode == 1 && c.n == 3 ? 0 : 1;
        }
    """.trimIndent() + "\n"

    private val ptMain = """
        #include "pt.kira.hxx"

        #include <cstring>

        extern "C" int32_t pt_len(const char* s)
        {
            return static_cast<int32_t>(std::strlen(s));
        }

        int main()
        {
            Pt p{1, 2};
            return pt_len("ok") == 2 && p.x == 1 && p.y == 2 ? 0 : 1;
        }
    """.trimIndent() + "\n"

    private fun emitHeader(uri: String, module: String, options: CppOptions = this.options): String {
        val emitted = DeclTestSupport.emit(DeclTestSupport.module(uri, module), options = options)
        return emitted.header(uri)
    }

    private fun writeUnit(name: String, files: Map<String, String>): File {
        val dir = File("build/tmp/cpp-ffi-drift/$name").apply { deleteRecursively(); mkdirs() }
        files.forEach { (file, text) -> File(dir, file).writeText(text) }
        return dir
    }

    @TestFactory
    fun onEveryHostToolchain(): List<DynamicNode> {
        val toolchains = listOf(CppToolchain.GCC, CppToolchain.MSVC, CppToolchain.CLANG)
        return toolchains.flatMap { tc ->
            listOf(
                DynamicTest.dynamicTest("${tc.id}: the wrong signature fails with Kira's message") { drift(tc) },
                DynamicTest.dynamicTest("${tc.id}: the right signature builds") { clean(tc) },
                DynamicTest.dynamicTest("${tc.id}: a same-size field type drift fails with Kira's message") { fieldTypeDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: two same-typed fields in the other order fail with Kira's message") { fieldOrderDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: the right struct, and a C header without a guard through c =, build") { ptClean(tc) },
                DynamicTest.dynamicTest("${tc.id}: a C enum field declared as the Int32 of its size builds") { enumFieldClean(tc) },
                DynamicTest.dynamicTest("${tc.id}: a C enum field declared as Int16 fails with Kira's message") { enumFieldSizeDrift(tc) },
            )
        }
    }

    private fun compile(tc: CppToolchain, name: String, files: Map<String, String>, withCarDriver: Boolean): CppCompileSupport.CompileResult {
        org.junit.jupiter.api.Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(tc)
        val dir = writeUnit("$name-${tc.id}", files)
        return CppCompileSupport.compile(
            sources = listOf(File(dir, "main.cxx")) + (if (withCarDriver) listOf(File(driver, "main.cxx")) else emptyList()),
            includeDirs = listOf(dir, driver, runtime),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.forToolchain(tc),
            outDir = File(dir, "out"),
        )
    }

    // ---- forward's car ----------------------------------------------------------------------

    private val carMain = """
        #include "car.kira.hxx"

        int main()
        {
            return 0;
        }
    """.trimIndent() + "\n"

    private fun compileCar(tc: CppToolchain, name: String, finishReturns: String): CppCompileSupport.CompileResult =
        compile(tc, name, mapOf("car.kira.hxx" to emitHeader("pilot:car", carModule(finishReturns)), "main.cxx" to carMain), withCarDriver = true)

    private fun drift(tc: CppToolchain) {
        val header = emitHeader("pilot:car", carModule("Str"))
        assertTrue(header.contains("KIRA_EXTERN_CHECK(std::declval<bibo::Car&>().finish(), kira::Str, \"Car.finish\");"), header)
        val result = compileCar(tc, "drift", "Str")
        assertTrue(!result.success, "${tc.id}: a Kira 'finish: () Str' against C++'s std::int32_t finish() compiled:\n${result.describe()}")
        assertMessage(tc, result, "Kira's Car.finish ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    private fun clean(tc: CppToolchain) {
        val result = compileCar(tc, "clean", "Int32")
        if (!result.success) {
            fail("${tc.id}: the right signature does not build:\n${result.describe()}")
        }
    }

    // ---- a C struct's fields, and a C header through c = -------------------------------------

    private fun compilePt(tc: CppToolchain, name: String, fields: String): Pair<String, CppCompileSupport.CompileResult> {
        val header = emitHeader("c:pt", ptModule(fields), CppOptions(lineDirectives = false))
        return header to compile(tc, name, mapOf("pt.kira.hxx" to header, "pt.h" to ptHeader, "main.cxx" to ptMain), withCarDriver = false)
    }

    private fun fieldTypeDrift(tc: CppToolchain) {
        val (header, result) = compilePt(tc, "field-type", "pub x: Float32 = 0.0\npub y: Float32 = 0.0")
        assertTrue(header.contains("KIRA_EXTERN_FIELD(Pt, pt::ffi_::Pt, x, float, \"Pt.x\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'x: Float32' against C's int32_t x compiled (is_convertible would let it):\n${result.describe()}")
        assertMessage(tc, result, "Kira's Pt.x ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    private fun fieldOrderDrift(tc: CppToolchain) {
        val (header, result) = compilePt(tc, "field-order", "pub y: Int32 = 0\npub x: Int32 = 0")
        assertTrue(header.contains("namespace pt::ffi_ { struct Pt { std::int32_t y; std::int32_t x; }; }"), header)
        assertTrue(!result.success, "${tc.id}: Kira's Pt with y before x against C's x before y compiled (sizeof and is_same both pass):\n${result.describe()}")
        assertMessage(tc, result, "Kira's Pt.y ${CppExternEmitter.DRIFT_MESSAGE} (it is not at that offset)")
    }

    private fun ptClean(tc: CppToolchain) {
        val (header, result) = compilePt(tc, "pt-clean", "pub x: Int32 = 0\npub y: Int32 = 0")
        assertTrue(header.contains("extern \"C\" {\n#include \"pt.h\"\n}\n"), header)
        if (!result.success) {
            fail("${tc.id}: the right struct and the c = function do not build:\n${result.describe()}")
        }
    }

    // ---- a C enum field, declared as the integer of its size ---------------------------------

    private fun compileCfg(tc: CppToolchain, name: String, modeType: String): Pair<String, CppCompileSupport.CompileResult> {
        val header = emitHeader("c:cfg", cfgModule(modeType), CppOptions(lineDirectives = false))
        return header to compile(tc, name, mapOf("cfg.kira.hxx" to header, "pt.h" to ptHeader, "main.cxx" to cfgMain), withCarDriver = false)
    }

    private fun enumFieldClean(tc: CppToolchain) {
        val (header, result) = compileCfg(tc, "enum-clean", "Int32")
        assertTrue(header.contains("KIRA_EXTERN_FIELD(PtCfg, cfg::ffi_::Cfg, mode, std::int32_t, \"Cfg.mode\");"), header)
        if (!result.success) {
            fail("${tc.id}: a C enum field declared as Int32 does not build:\n${result.describe()}")
        }
    }

    /** `{ int16_t mode; int32_t n; }` pads to the same 8 bytes, so only the field's size gate catches it. */
    private fun enumFieldSizeDrift(tc: CppToolchain) {
        val (header, result) = compileCfg(tc, "enum-size", "Int16")
        assertTrue(header.contains("namespace cfg::ffi_ { struct Cfg { std::int16_t mode; std::int32_t n; }; }"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'mode: Int16' against a 4-byte C enum compiled (sizeof passes by padding):\n${result.describe()}")
        assertMessage(tc, result, "Kira's Cfg.mode ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    private fun assertMessage(tc: CppToolchain, result: CppCompileSupport.CompileResult, wanted: String) {
        assertTrue(result.diagnostics.contains(wanted), "${tc.id}: the build failed, but not with '$wanted':\n${result.describe()}")
    }
}
