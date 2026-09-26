package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppExternEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
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
 * - a C++ `std::optional<std::uint32_t>` declared `Maybe<Int32>`, a `std::optional<std::uint8_t>`
 *   parameter declared `Maybe<Int32>`, and a `std::function<void(unsigned)>` declared
 *   `Fx<Tuple1<Int32>, Void>`: the converting constructors of optional and function let
 *   each through is_convertible, and the same rule one level down refuses them; the right
 *   ones build.
 * - a C++ `const char* name()` declared `Str`: the call the emitter writes compares by
 *   value against a Kira Str constant, and the program says so by exiting 0; and one
 *   returning nullptr is the empty Str, not a crash.
 * - a `char letter()` declared `Int8` and a `std::int8_t code()` declared `Char`: char is
 *   only Char, so both fail with Kira's message (a char kept as the call's type printed A
 *   where an Int8 prints 65, measured).
 * - a C enum result declared `Int32`, and a C enum field declared `Int32`: the call and the
 *   field read the emitter writes are std::int32_t, since the check accepts a same-size
 *   twin (an enum, a C `int` on arm-none-eabi, a `long` on Windows) and Kira's own
 *   operations do not; a field whose C++ type is the declared one stays the member itself.
 * - a `std::function<void(const char*)>` declared `Fx<Tuple1<Str>, Void>` fails with Kira's
 *   message, and a `std::function<void(const std::string&)>` declared `Fx<Tuple1<CStr>, Void>`
 *   builds and is called: a Fn's parameters match in the direction the argument flows.
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
        uint32_t pt_count(void);
        void pt_take(uint8_t v);
        PtMode pt_mode(void);
        struct PtInt {
            int x;
            int y;
        };
        #endif
    """.trimIndent() + "\n"

    /** Kira's view of `pt.h`'s scalar functions and its `int` struct: the declarations as given. */
    private fun scalarModule(decls: String): String = """
        $decls
    """

    /**
     * A C++ header with the shapes std::optional and std::function let through one level
     * down: an optional of the wrong scalar, an optional parameter of a narrower one, a
     * std::function of the wrong scalar; and a `const char*` result, which is a Str by 7.2.
     */
    private val probeHeader = """
        #pragma once
        #include <cstdint>
        #include <functional>
        #include <optional>
        #include <string>
        namespace probe {
            inline std::optional<std::uint32_t> find_u() { return 0u; }
            inline std::optional<std::int32_t> find_i() { return 0; }
            inline std::uint8_t seen = 0;
            inline void put_u8(std::optional<std::uint8_t> m) { seen = m.value_or(0); }
            inline void put_i(const std::optional<std::int32_t>& m) { seen = static_cast<std::uint8_t>(m.value_or(0)); }
            inline std::function<void(unsigned)> on_u() { return [](unsigned) {}; }
            inline std::function<void(std::int32_t)> on_i() { return [](std::int32_t) {}; }
            inline void set_i(std::function<void(std::int32_t)>) {}
            inline const char* name() { static char buf[] = "abc"; return buf; }
            inline const char* none() { return nullptr; }
            inline char letter() { return 'A'; }
            inline std::int8_t code() { return 66; }
            inline std::size_t said = 0;
            inline std::function<void(const char*)> on_c() { return [](const char*) {}; }
            inline std::function<void(const std::string&)> on_s() { return [](const std::string& s) { said = s.size(); }; }
        }
    """.trimIndent() + "\n"

    /** A translation unit that includes the module's header and calls nothing: the header is the test. */
    private fun headerOnlyMain(header: String): String = """
        #include "$header"

        int main()
        {
            return 0;
        }
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
                DynamicTest.dynamicTest("${tc.id}: a uint32_t return declared Int32 fails with Kira's message") { returnSignednessDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: a uint8_t parameter declared Int32 is no viable call") { parameterWidthDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: a C int field declared Int32 builds") { cIntFieldClean(tc) },
                DynamicTest.dynamicTest("${tc.id}: an optional<uint32_t> return declared Maybe<Int32> fails with Kira's message") { maybeReturnDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: an optional<uint8_t> parameter declared Maybe<Int32> is no viable call") { maybeParameterDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: a function<void(unsigned)> return declared Fx<Tuple1<Int32>, Void> fails with Kira's message") { fnReturnDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: the right Maybe and Fn declarations build") { maybeAndFnClean(tc) },
                DynamicTest.dynamicTest("${tc.id}: a const char* result declared Str compares by value at the call") { strResultIsConvertedAtTheCall(tc) },
                DynamicTest.dynamicTest("${tc.id}: a null const char* result declared Str is the empty Str at the call") { nullStrResultIsEmpty(tc) },
                DynamicTest.dynamicTest("${tc.id}: a char result declared Int8 fails with Kira's message") { charResultDeclaredInt8Drift(tc) },
                DynamicTest.dynamicTest("${tc.id}: an int8_t result declared Char fails with Kira's message") { int8ResultDeclaredCharDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: a C enum result declared Int32 is an int32_t at the call") { scalarResultIsTheDeclaredType(tc) },
                DynamicTest.dynamicTest("${tc.id}: a field read of an extern struct is the declared type, and the member itself where the types agree") { fieldReadIsTheDeclaredType(tc) },
                DynamicTest.dynamicTest("${tc.id}: a function<void(const char*)> return declared Fx<Tuple1<Str>, Void> fails with Kira's message") { fnParameterDirectionDrift(tc) },
                DynamicTest.dynamicTest("${tc.id}: a function<void(const string&)> return declared Fx<Tuple1<CStr>, Void> builds and is called") { fnParameterDirectionClean(tc) },
            )
        }
    }

    // ---- what Kira then reads: declared<T> and field<T> (kira/ffi.hxx's head) ----------------------

    /** The text [CppExternEmitter.call] writes for the one call of [name] in [decls], and the emitted header. */
    private fun callText(uri: String, decls: String, name: String): Pair<String, String> {
        val (emitted, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module(uri, decls), uri = uri, options = CppOptions(lineDirectives = false))
        val errors = emitted.diagnostics.filter { it.isError && !it.message.contains("is not lowered yet") }
        assertTrue(errors.isEmpty(), "errors:\n" + errors.joinToString("\n") { it.render() })
        val call = ctx.model.calls.values.firstOrNull { it.fn?.name == name } ?: fail("no call of $name() in the model")
        return CppExternEmitter.call(ctx, call, null, emptyList()) to CppWriter.normalize(emitted.header)
    }

    private fun runProbe(tc: CppToolchain, name: String, files: Map<String, String>, what: String) {
        org.junit.jupiter.api.Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(tc)
        val result = compile(tc, name, files, withCarDriver = false)
        if (!result.success) {
            fail("${tc.id}: $what does not build:\n${result.describe()}")
        }
        val exe = result.exe ?: fail("${tc.id}: reported success without an executable")
        val run = CppCompileSupport.run(exe, extraPathDirs = listOfNotNull(located.binDir))
        assertTrue(!run.timedOut, "${tc.id}: the program timed out")
        assertEquals(0, run.exitCode, "${tc.id}: $what: the program exited ${run.exitCode}\nstderr:\n${run.stderr}")
    }

    /**
     * `const char* none()` returning nullptr, declared `Str`: `static_cast<kira::Str>(nullptr)`
     * was a std::logic_error on libstdc++ (rc 3) and a segfault on libc++ (rc 139), measured,
     * and ImGui's GetClipboardText returns NULL for an empty clipboard. declared<Str> makes it
     * the empty Str; the program exits 0 only then.
     */
    private fun nullStrResultIsEmpty(tc: CppToolchain) {
        val (text, header) = callText(
            "c:sc",
            """
            @_extern(cpp = "probe::none", header = "probe.hxx")
            pub fx none: () Str;

            fx run: () Str {
                return none()
            }
            """.trimIndent(),
            "none",
        )
        assertEquals("kira::ffi::declared<kira::Str>(::probe::none())", text)
        val main = """
            #include "sc.kira.hxx"

            int main()
            {
                return $text.empty() ? 0 : 1;
            }
        """.trimIndent() + "\n"
        runProbe(tc, "null-str", mapOf("sc.kira.hxx" to header, "probe.hxx" to probeHeader, "main.cxx" to main), "a null const char* declared Str")
    }

    /** `char letter()` declared `() Int8`: a scalar of Int8's size and (on x86) signedness, and trace(letter()) printed A where an Int8 prints 65 (measured). char is only Char. */
    private fun charResultDeclaredInt8Drift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "char-int8", "@_extern(cpp = \"probe::letter\", header = \"probe.hxx\")\npub fx letter: () Int8;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::letter(), std::int8_t, \"letter\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'letter: () Int8' against C++'s char letter() compiled (a scalar of the size would let it):\n${result.describe()}")
        assertMessage(tc, result, "Kira's letter ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    /** `std::int8_t code()` declared `() Char`: the same rule from the other side (trace(code()) printed 66 where a Char prints B, measured). */
    private fun int8ResultDeclaredCharDrift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "int8-char", "@_extern(cpp = \"probe::code\", header = \"probe.hxx\")\npub fx code: () Char;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::code(), char, \"code\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'code: () Char' against C++'s std::int8_t code() compiled:\n${result.describe()}")
        assertMessage(tc, result, "Kira's code ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    /**
     * A C `PtMode pt_mode(void)` (an unscoped enum) declared `() Int32`: the check accepts the
     * enum of Int32's size, and the call left as C++'s type then failed inside kira::cat
     * (`'nameOf' was not declared`, measured), as a `long` on Windows and an `int` on
     * arm-none-eabi failed in kira::div. The call is `declared<std::int32_t>`, and the
     * program proves its type is std::int32_t and its value the enum's.
     */
    private fun scalarResultIsTheDeclaredType(tc: CppToolchain) {
        val (text, header) = callText(
            "c:sc",
            """
            @_extern(c = "pt_mode", header = "pt.h")
            pub fx ptMode: () Int32;

            fx run: () Int32 {
                return ptMode()
            }
            """.trimIndent(),
            "ptMode",
        )
        assertEquals("kira::ffi::declared<std::int32_t>(::pt_mode())", text)
        val main = """
            #include "sc.kira.hxx"

            #include <type_traits>

            extern "C" PtMode pt_mode(void)
            {
                return PT_ON;
            }

            int main()
            {
                static_assert(std::is_same_v<decltype($text), std::int32_t>, "the call is the declared type");
                const std::int32_t v = $text;
                return v == 1 ? 0 : 1;
            }
        """.trimIndent() + "\n"
        runProbe(tc, "enum-result", mapOf("sc.kira.hxx" to header, "pt.h" to ptHeader, "main.cxx" to main), "a C enum result declared Int32")
    }

    /**
     * `PtCfg { PtMode mode; int32_t n; }` declared with two Int32 fields: the read of `mode`
     * the expression part hands to [CppExternEmitter.field] is a std::int32_t (a `char c`
     * declared Int8 printed C where an Int8 prints 67 before char became only Char, and an
     * enum member breaks kira::cat as a result does), and the read of `n`, whose C++ type is
     * the declared one, is the member itself: an lvalue a write goes through.
     */
    private fun fieldReadIsTheDeclaredType(tc: CppToolchain) {
        val uri = "c:cfg"
        val (emitted, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module(uri, cfgModule("Int32")), uri = uri, options = CppOptions(lineDirectives = false))
        val cfg = ctx.symbol.members["Cfg"] as ClassSymbol
        val mode = CppExternEmitter.field(ctx, cfg.fields.first { it.name == "mode" }, "c.mode")
        val n = CppExternEmitter.field(ctx, cfg.fields.first { it.name == "n" }, "c.n")
        assertEquals("kira::ffi::field<std::int32_t>(c.mode)", mode)
        assertEquals("kira::ffi::field<std::int32_t>(c.n)", n)
        val main = """
            #include "cfg.kira.hxx"

            #include <type_traits>

            int main()
            {
                PtCfg c{PT_ON, 3};
                static_assert(std::is_same_v<decltype($mode), std::int32_t>, "an enum member reads as the declared integer");
                static_assert(std::is_same_v<decltype($n), std::int32_t&>, "a member of the declared type is the member itself");
                $n = 4;
                const std::int32_t m = $mode;
                return m == 1 && c.n == 4 ? 0 : 1;
            }
        """.trimIndent() + "\n"
        runProbe(tc, "field-read", mapOf("cfg.kira.hxx" to CppWriter.normalize(emitted.header), "pt.h" to ptHeader, "main.cxx" to main), "a field read of an extern struct")
    }

    /**
     * `std::function<void(const char*)> on_c()` declared `() Fx<Tuple1<Str>, Void>`: Kira
     * would call it with a Str, which reaches no const char*. Matched parameter by parameter
     * in the wrong direction it passed the check, and the conversion the emitter writes then
     * failed inside libstdc++'s std::function constructor instead of with Kira's message
     * (measured, g++ 13.2).
     */
    private fun fnParameterDirectionDrift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "fn-direction", "@_extern(cpp = \"probe::on_c\", header = \"probe.hxx\")\npub fx onC: () Fx<Tuple1<Str>, Void>;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::on_c(), kira::Fn<void(const kira::Str&)>, \"onC\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'onC: () Fx<Tuple1<Str>, Void>' against C++'s std::function<void(const char*)> compiled:\n${result.describe()}")
        assertMessage(tc, result, "Kira's onC ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    /** `std::function<void(const std::string&)> on_s()` declared `() Fx<Tuple1<CStr>, Void>`: the const char* Kira passes reaches the string. The wrong direction refused it. */
    private fun fnParameterDirectionClean(tc: CppToolchain) {
        val (text, header) = callText(
            "c:sc",
            """
            @_extern(cpp = "probe::on_s", header = "probe.hxx")
            pub fx onS: () Fx<Tuple1<CStr>, Void>;

            fx run: () Fx<Tuple1<CStr>, Void> {
                return onS()
            }
            """.trimIndent(),
            "onS",
        )
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::on_s(), kira::Fn<void(const char*)>, \"onS\");"), header)
        assertEquals("kira::ffi::declared<kira::Fn<void(const char*)>>(::probe::on_s())", text)
        val main = """
            #include "sc.kira.hxx"

            int main()
            {
                $text("abcd");
                return probe::said == 4 ? 0 : 1;
            }
        """.trimIndent() + "\n"
        runProbe(tc, "fn-direction-clean", mapOf("sc.kira.hxx" to header, "probe.hxx" to probeHeader, "main.cxx" to main), "a function<void(const string&)> declared Fx<Tuple1<CStr>, Void>")
    }

    // ---- Maybe and Fn: the same rule one level down (kira/ffi.hxx's head) ----------------------

    private fun compileProbe(tc: CppToolchain, name: String, decls: String, main: String = headerOnlyMain("sc.kira.hxx")): Pair<String, CppCompileSupport.CompileResult> {
        val header = emitHeader("c:sc", scalarModule(decls), CppOptions(lineDirectives = false))
        return header to compile(tc, name, mapOf("sc.kira.hxx" to header, "probe.hxx" to probeHeader, "main.cxx" to main), withCarDriver = false)
    }

    /** `std::optional<std::uint32_t> find_u()` declared `() Maybe<Int32>`: optional's converting constructor passed it, and `unwrap(find()) - 1` printed 4294967295 (measured). */
    private fun maybeReturnDrift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "maybe-return", "@_extern(cpp = \"probe::find_u\", header = \"probe.hxx\")\npub fx findU: () Maybe<Int32>;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::find_u(), kira::Maybe<std::int32_t>, \"findU\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'findU: () Maybe<Int32>' against C++'s std::optional<std::uint32_t> compiled (is_convertible would let it):\n${result.describe()}")
        assertMessage(tc, result, "Kira's findU ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    /** `void put_u8(std::optional<std::uint8_t>)` declared `(m: Maybe<Int32>)`: a declval of the Maybe converted, and 300 reached C++ as 44 (measured). */
    private fun maybeParameterDrift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "maybe-param", "@_extern(cpp = \"probe::put_u8\", header = \"probe.hxx\")\npub fx putU8: (m: Maybe<Int32>) Void;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK((probe::put_u8(kira::ffi::arg<kira::Maybe<std::int32_t>>()), 0), int, \"putU8\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'putU8: (m: Maybe<Int32>)' against C++'s put_u8(std::optional<std::uint8_t>) compiled (declval would let it):\n${result.describe()}")
        assertTrue(result.diagnostics.contains("Arg<"), "${tc.id}: the build failed, but not at the kira::ffi::Arg proxy:\n${result.describe()}")
    }

    /** `std::function<void(unsigned)> on_u()` declared `() Fx<Tuple1<Int32>, Void>`: std::function converts from any callable of a compatible signature. */
    private fun fnReturnDrift(tc: CppToolchain) {
        val (header, result) = compileProbe(tc, "fn-return", "@_extern(cpp = \"probe::on_u\", header = \"probe.hxx\")\npub fx onU: () Fx<Tuple1<Int32>, Void>;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(probe::on_u(), kira::Fn<void(std::int32_t)>, \"onU\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'onU: () Fx<Tuple1<Int32>, Void>' against C++'s std::function<void(unsigned)> compiled (is_convertible would let it):\n${result.describe()}")
        assertMessage(tc, result, "Kira's onU ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    private fun maybeAndFnClean(tc: CppToolchain) {
        val (_, result) = compileProbe(
            tc, "maybe-fn-clean",
            """
            @_extern(cpp = "probe::find_i", header = "probe.hxx")
            pub fx findI: () Maybe<Int32>;
            @_extern(cpp = "probe::put_i", header = "probe.hxx")
            pub fx putI: (m: Maybe<Int32>) Void;
            @_extern(cpp = "probe::on_i", header = "probe.hxx")
            pub fx onI: () Fx<Tuple1<Int32>, Void>;
            @_extern(cpp = "probe::set_i", header = "probe.hxx")
            pub fx setI: (f: Fx<Tuple1<Int32>, Void>) Void;
            """.trimIndent(),
        )
        if (!result.success) {
            fail("${tc.id}: the right Maybe and Fn declarations do not build:\n${result.describe()}")
        }
    }

    /**
     * The call the emitter writes for `name()`, a C++ `const char*` declared `Str`, against
     * a Kira Str constant (D12: `inline constexpr const char*`). Left as C++'s type, `name()
     * == ABC` compared two pointers and printed 0 for Kira's 1 (measured, on all three of
     * these with -Werror silent); converted at the call it compares the text. The program
     * exits 0 only when it does.
     */
    private fun strResultIsConvertedAtTheCall(tc: CppToolchain) {
        val uri = "c:sc"
        val decls = """
            @_extern(cpp = "probe::name", header = "probe.hxx")
            pub fx name: () Str;

            fx run: () Bool {
                return name() == "abc"
            }
        """.trimIndent()
        val (emitted, ctx) = DeclTestSupport.emitWith(DeclTestSupport.module(uri, decls), uri = uri, options = CppOptions(lineDirectives = false))
        // The typer types run's body, which is where the call comes from; whether this build
        // lowers the body (W2.3's part) is beside the point, and its "not lowered yet" is let by.
        val errors = emitted.diagnostics.filter { it.isError && !it.message.contains("is not lowered yet") }
        assertTrue(errors.isEmpty(), "errors:\n" + errors.joinToString("\n") { it.render() })
        val call = ctx.model.calls.values.firstOrNull { it.fn?.name == "name" } ?: fail("no call of name() in the model")
        val text = CppExternEmitter.call(ctx, call, null, emptyList())
        assertEquals("kira::ffi::declared<kira::Str>(::probe::name())", text)
        val main = """
            #include "sc.kira.hxx"

            namespace
            {
              constexpr const char* ABC = "abc";
            }

            int main()
            {
                return ($text == ABC) ? 0 : 1;
            }
        """.trimIndent() + "\n"
        org.junit.jupiter.api.Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
        val located = CppToolchains.requireOrSkip(tc)
        val result = compile(tc, "str-result", mapOf("sc.kira.hxx" to CppWriter.normalize(emitted.header), "probe.hxx" to probeHeader, "main.cxx" to main), withCarDriver = false)
        if (!result.success) {
            fail("${tc.id}: the converted call does not build:\n${result.describe()}")
        }
        val exe = result.exe ?: fail("${tc.id}: reported success without an executable")
        val run = CppCompileSupport.run(exe, extraPathDirs = listOfNotNull(located.binDir))
        assertTrue(!run.timedOut, "${tc.id}: the program timed out")
        assertEquals(0, run.exitCode, "${tc.id}: '$text == ABC' is false: the Str result was compared as a pointer\nstderr:\n${run.stderr}")
    }

    // ---- the scalar rule at the return and at a parameter (kira/ffi.hxx's head) ----------------

    private fun compileScalar(tc: CppToolchain, name: String, decls: String): Pair<String, CppCompileSupport.CompileResult> {
        val header = emitHeader("c:sc", scalarModule(decls), CppOptions(lineDirectives = false))
        return header to compile(tc, name, mapOf("sc.kira.hxx" to header, "pt.h" to ptHeader, "main.cxx" to headerOnlyMain("sc.kira.hxx")), withCarDriver = false)
    }

    /** `std::uint32_t pt_count()` declared `() Int32`: is_convertible passed it, and Kira's `count() - 1` then printed 4294967295 (measured). */
    private fun returnSignednessDrift(tc: CppToolchain) {
        val (header, result) = compileScalar(tc, "return-sign", "@_extern(c = \"pt_count\", header = \"pt.h\")\npub fx ptCount: () Int32;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK(pt_count(), std::int32_t, \"ptCount\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'ptCount: () Int32' against C's uint32_t pt_count() compiled (is_convertible would let it):\n${result.describe()}")
        assertMessage(tc, result, "Kira's ptCount ${CppExternEmitter.DRIFT_MESSAGE}")
    }

    /**
     * `void pt_take(uint8_t)` declared `(v: Int32)`: `std::declval<std::int32_t>()` converted to
     * the uint8_t and the call narrowed silently; `kira::ffi::arg<std::int32_t>()` reaches no
     * uint8_t parameter, so the call in the check is ill-formed. That is a compiler error at
     * the check naming kira::ffi::Arg, not Kira's message: the expression fails before the
     * static_assert sees a type (as a wrong parameter count did already).
     */
    private fun parameterWidthDrift(tc: CppToolchain) {
        val (header, result) = compileScalar(tc, "param-width", "@_extern(c = \"pt_take\", header = \"pt.h\")\npub fx ptTake: (v: Int32) Void;")
        assertTrue(header.contains("KIRA_EXTERN_CHECK((pt_take(kira::ffi::arg<std::int32_t>()), 0), int, \"ptTake\");"), header)
        assertTrue(!result.success, "${tc.id}: Kira's 'ptTake: (v: Int32)' against C's pt_take(uint8_t) compiled (declval would let it):\n${result.describe()}")
        assertTrue(result.diagnostics.contains("Arg<"), "${tc.id}: the build failed, but not at the kira::ffi::Arg proxy:\n${result.describe()}")
    }

    /** `struct PtInt { int x; int y; }` declared with Int32 fields: int and std::int32_t are one scalar (on arm-none-eabi too, where is_same was not enough). */
    private fun cIntFieldClean(tc: CppToolchain) {
        val (header, result) = compileScalar(tc, "int-field", "@_extern(c = \"PtInt\", header = \"pt.h\")\npub struct PtI {\n    pub x: Int32 = 0\n    pub y: Int32 = 0\n}")
        assertTrue(header.contains("KIRA_EXTERN_FIELD(PtInt, sc::ffi_::PtI, x, std::int32_t, \"PtI.x\");"), header)
        if (!result.success) {
            fail("${tc.id}: a C int field declared Int32 does not build:\n${result.describe()}")
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
