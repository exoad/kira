package net.exoad.kira.cpp.ffi

import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import net.exoad.kira.types.rules.RulesTestSupport
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `@_extern` struct by pointer, in the Pico SDK's PIO shapes: a module-level constant `Arr`
 * as a struct's `const uint16_t*` member, a struct passed to a `const T*` parameter
 * (`Unsafe<T>`), a C function that changes a struct through `T*` declared as a `@_mutates`
 * method of it, and a `@_constructible` object passed by pointer without a copy.
 */
class ExternPointerTest {
    private val module = Module(
        "app:piox",
        """
        $PIO

        CODE: Arr<UInt16, 4> = @arrOf(7, 20, 300, 4000)
        PROGRAM: Program { instructions = CODE, length = 4, origin = -1 }

        pub added: () UInt32 {
            addProgram(PIO0, PROGRAM)
        }

        pub addedLocal: () UInt32 {
            p: Program { instructions = CODE, length = 2, origin = 3 }
            addProgram(PIO0, p)
        }

        pub wrapped: () UInt32 {
            c: Mutable<Config> = defaultConfig()
            c.setWrap(5, 9)
            c.setClkdiv(2.5)
            c.execctrl + c.clkdiv
        }

        pub initialised: () Int32 {
            c: Mutable<Config> = defaultConfig()
            c.setWrap(1, 2)
            smInit(PIO0, 0, 7, c)
        }

        pub fresh: () Int32 {
            smInit(PIO0, 0, 3, defaultConfig())
        }

        pub readWrap: () UInt32 {
            c: Config = defaultConfig()
            c.wrapOf()
        }

        pub readFresh: () UInt32 {
            wrapOfConfig(defaultConfig())
        }

        pub counted: () Int32 {
            s: Mutable<Shadow> { }
            s.bump()
            s.bump()
            shadowCount(s)
        }
        """,
    )

    private val tree by lazy {
        CppExprTestSupport.emit("ffi-pointer", listOf(module)).also {
            Files.writeString(it.root.resolve("pio.h"), PIO_H)
            Files.writeString(it.root.resolve("shadow.hxx"), SHADOW_HXX)
        }
    }

    @Test
    fun eachPointerIsTakenAtItsCall() {
        val source = tree.source(module)
        listOf(
            "::pio_program_t PROGRAM = ::pio_program_t{.instructions = CODE.data(), .length = 4, .origin = -1};",
            "::pio_add_program(pio0, kira::ffi::ptr(PROGRAM))",
            "::sm_config_set_wrap(kira::ffi::ptr(c), std::uint32_t{5u}, std::uint32_t{9u});",
            "::pio_sm_init(pio0, std::uint32_t{0u}, std::uint32_t{7u}, kira::ffi::ptr(c))",
            "::pio_hw_t* const t0_ = pio0;", "::pio_sm_init(t0_, std::uint32_t{0u}, std::uint32_t{3u}, kira::ffi::ptr(t1_))",
            "::sm_config_wrap(kira::ffi::ptr(c))",
            "::sm_config_wrap(kira::ffi::ptr(kira::ffi::declared<::pio_sm_config>(::pio_get_default_sm_config())))",
            "::shadow_count(kira::ffi::ptr(s))",
        ).forEach { assertTrue(source.contains(it), "missing '$it' in:\n$source") }
    }

    @Test
    fun theDriftCheckStatesEveryPointerType() {
        val header = tree.header(module)
        listOf(
            "KIRA_EXTERN_FIELD(pio_program_t, piox::ffi_::Program, instructions, const std::uint16_t*, \"Program.instructions\");",
            "KIRA_EXTERN_CHECK((sm_config_set_wrap(std::declval<pio_sm_config*>(), kira::ffi::arg<std::uint32_t>(), kira::ffi::arg<std::uint32_t>()), 0), int, \"Config.setWrap\");",
            "KIRA_EXTERN_CHECK(sm_config_wrap(std::declval<const pio_sm_config*>()), std::uint32_t, \"Config.wrapOf\");",
            "KIRA_EXTERN_CHECK(pio_add_program(std::declval<pio_hw_t*>(), std::declval<const pio_program_t*>()), std::uint32_t, \"addProgram\");",
            "KIRA_EXTERN_CHECK(pio_sm_init(std::declval<pio_hw_t*>(), kira::ffi::arg<std::uint32_t>(), kira::ffi::arg<std::uint32_t>(), std::declval<const pio_sm_config*>()), std::int32_t, \"smInit\");",
            "KIRA_EXTERN_CHECK(pio::shadow_count(std::declval<const pio::Shadow*>()), std::int32_t, \"shadowCount\");",
        ).forEach { assertTrue(header.contains(it), "missing '$it' in:\n$header") }
    }

    @TestFactory
    fun everyRowRunsOnEveryCompiler(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
        DynamicTest.dynamicTest("extern pointer [${tc.id}]") {
            // added: 4327 summed, length 4, relocatable; addedLocal: 27, length 2, origin 3.
            // wrapped: execctrl (5 << 7 | 9 << 12) plus clkdiv 2.5 * 256; initialised: (1 << 7 | 2 << 12) + 7; fresh: (31 << 12) + 3.
            val rows = listOf(
                "added" to 4327041L, "addedLocal" to 27023L, "wrapped" to 37504L + 640L,
                "initialised" to 8327L, "fresh" to 126979L, "readWrap" to 31L, "readFresh" to 31L, "counted" to 2L,
            )
            val driver = buildString {
                append("#include \"").append(module.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                append(CppExprTestSupport.CHECK_PRELUDE)
                append("\nint main()\n{\n")
                rows.forEach { (fn, want) ->
                    append("    {\n        const long long got = static_cast<long long>(piox::$fn());\n")
                    append("        std::printf(\"  $fn = %lld\\n\", got);\n")
                    append("        check(got == ${want}LL, \"$fn() is $want\");\n    }\n")
                }
                append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                append("    return failures == 0 ? 0 : 1;\n}\n")
            }
            val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
            assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
        }
    }

    /** A header whose pointer types changed fails the build: each of [DRIFTS] on its own. */
    @TestFactory
    fun aChangedPointerTypeFailsTheDriftCheck(): List<DynamicNode> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).flatMap { tc ->
        DRIFTS.mapIndexed { i, (what, from, to, message) ->
            DynamicTest.dynamicTest("pointer drift $what [${tc.id}]") {
                Assumptions.assumeTrue(CppToolchains.isEnabled(tc), "toolchain '${tc.id}' is disabled by KIRA_TOOLCHAINS")
                val located = CppToolchains.requireOrSkip(tc)
                val drifted = CppExprTestSupport.emit("ffi-pointer-drift-$i-${tc.id}", listOf(module))
                assertTrue(PIO_H.contains(from), "the header has no '$from'")
                Files.writeString(drifted.root.resolve("pio.h"), PIO_H.replace(from, to))
                Files.writeString(drifted.root.resolve("shadow.hxx"), SHADOW_HXX)
                val result = CppCompileSupport.compile(
                    sources = drifted.sources(),
                    includeDirs = listOf(drifted.root.toFile(), drifted.runtimeDir.toFile()),
                    defines = emptyList(),
                    toolchain = located,
                    profile = CppProfile.forToolchain(tc),
                    outDir = drifted.root.resolve("out/${tc.id}").toFile(),
                )
                assertTrue(!result.success, "${tc.id}: the drifted header built ($what)")
                assertTrue(result.diagnostics.contains(message), "${tc.id} ($what): no '$message' in\n${result.describe()}")
            }
        }
    }

    private fun refusedText(name: String, body: String): String {
        val result = CppExprTestSupport.emitRefused(name, listOf(Module("app:pioy", "$PIO\n\n$body")))
        return result.diagnostics.filter { it.isError }.joinToString("\n") { it.render() }
    }

    @Test
    fun aStructFieldPointsOnlyAtAModuleLevelConstantArr() {
        val text = refusedText(
            "ffi-pointer-field",
            """
            LIVE: Mutable<Arr<UInt16, 2>> = @arrOf(1, 2)

            @_extern(c = "EXT_CODE", header = "pio.h") EXT_CODE: Arr<UInt16, 2>

            pub bad: () UInt32 {
                local: Arr<UInt16, 2> = @arrOf(3, 4)
                a: Program { instructions = local, length = 2, origin = 0 }
                b: Program { instructions = LIVE, length = 2, origin = 0 }
                c: Program { instructions = EXT_CODE, length = 2, origin = 0 }
                addProgram(PIO0, a) + addProgram(PIO0, b) + addProgram(PIO0, c)
            }
            """,
        )
        // A local ends, a Mutable array changes, and an extern constant is read as a copy.
        assertEquals(3, Regex("rules\\.extern\\.pointer").findAll(text).count(), text)
        assertTrue(text.contains("Field 'instructions' of Program keeps a pointer for as long as the struct lives, so it takes a module-level constant Arr"), text)
    }

    @Test
    fun aPointerReadBackIsNeverKept() {
        val text = refusedText(
            "ffi-pointer-keep",
            """
            CODE: Arr<UInt16, 2> = @arrOf(1, 2)
            PROGRAM: Program { instructions = CODE, length = 2, origin = 0 }

            pub bad: () Void {
                keep: Unsafe<UInt16> = PROGRAM.instructions
            }
            """,
        )
        assertTrue(text.contains("rules.view.unsafe: 'keep' is a Unsafe<UInt16>"), text)
    }

    @Test
    fun onlyAnExternStructIsPassedByPointer() {
        val text = refusedText(
            "ffi-pointer-kira-struct",
            """
            Point: Struct {
                pub x: Int32 = 0
            }

            @_extern(c = "point_x", header = "pio.h") pointX: (p: Unsafe<Point>) Int32

            pub bad: () Int32 {
                p: Point { x = 3 }
                pointX(p)
            }
            """,
        )
        assertTrue(text.contains("argument 'p' expects Unsafe<Point>, but this is Point"), text)
    }

    @Test
    fun anExternStructWithoutFieldsIsStillMadeByTheCSide() {
        val text = refusedText(
            "ffi-pointer-fieldless",
            """
            @_extern(c = "pio_hidden_t", header = "pio.h") Hidden: Struct { }

            pub bad: () Void {
                h: Hidden { }
            }
            """,
        )
        assertTrue(text.contains("Hidden is made by the C/C++ side; call the function that returns one"), text)
    }

    @Test
    fun aKiraStructHoldsNoPointer() {
        val text = refusedText(
            "ffi-pointer-kira-field",
            """
            Holder: Struct {
                pub words: Unsafe<UInt16>
            }
            """,
        )
        assertTrue(text.contains("rules.view.unsafe: Field 'words' of Holder"), text)
    }

    @Test
    fun theConstructibleObjectIsStillNeverCopied() {
        val text = refusedText(
            "ffi-pointer-copy",
            """
            @_extern(cpp = "pio::shadow_value", header = "shadow.hxx") shadowValue: (s: Shadow) Int32

            pub bad: () Int32 {
                s: Mutable<Shadow> { }
                shadowValue(s)
            }
            """,
        )
        assertTrue(text.contains("rules.extern.copy"), text)
    }

    private fun typed(body: String): List<String> = RulesTestSupport.snippet(body).diagnostics.map { it.message }

    @Test
    fun pyRefusesTheConstructionAndThePointer() {
        val body = """
            @_extern S: Struct {
                pub n: Int32
            }

            @_extern take: (s: Unsafe<S>) Int32

            pub run: () Int32 {
                s: S { n = 1 }
                take(s)
            }
        """.trimIndent()
        val previous = GeneratedProvider.outputMode
        try {
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.PY
            val messages = typed(body)
            listOf(
                "'S { ... }' builds a C struct from its fields, which reaches --target cpp only: the PY backend has no C struct",
                "Passing a S to 'take' by pointer reaches --target cpp only: the PY backend has no C struct",
            ).forEach { want -> assertTrue(messages.any { it.contains(want) }, "expected '$want', got:\n${messages.joinToString("\n")}") }
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.CPP
            assertEquals(emptyList(), typed(body).filter { it.contains("--target cpp only") })
        } finally {
            GeneratedProvider.outputMode = previous
        }
    }

    private companion object {
        const val PIO = """pub @_extern(c = "pio_program_t", header = "pio.h") Program: Struct {
            pub instructions: Unsafe<UInt16>
            pub length: UInt8
            pub origin: Int8
        }

        pub @_extern(c = "pio_sm_config", header = "pio.h") Config: Struct {
            pub clkdiv: UInt32
            pub execctrl: UInt32
            pub shiftctrl: UInt32
            pub pinctrl: UInt32
            pub @_mutates @_extern(c = "sm_config_set_wrap") setWrap: (target: UInt32, wrap: UInt32) Void
            pub @_mutates @_extern(c = "sm_config_set_clkdiv") setClkdiv: (div: Float32) Void
            pub @_extern(c = "sm_config_wrap") wrapOf: () UInt32
        }

        pub @_opaque @_extern(c = "pio_hw_t", header = "pio.h") Pio: Mutable<Any> { }

        @_extern(c = "pio0", header = "pio.h") PIO0: Pio

        @_extern(c = "pio_get_default_sm_config", header = "pio.h") defaultConfig: () Config
        @_extern(c = "pio_add_program", header = "pio.h") addProgram: (pio: Pio, program: Unsafe<Program>) UInt32
        @_extern(c = "sm_config_wrap", header = "pio.h") wrapOfConfig: (c: Unsafe<Config>) UInt32
        @_extern(c = "pio_sm_init", header = "pio.h") smInit: (pio: Pio, sm: UInt32, pc: UInt32, config: Unsafe<Config>) Int32

        pub @_constructible @_extern(cpp = "pio::Shadow", header = "shadow.hxx") Shadow: Struct {
            pub @_mutates bump: () Void
        }

        @_extern(cpp = "pio::shadow_count", header = "shadow.hxx") shadowCount: (s: Unsafe<Shadow>) Int32"""

        /** hardware/pio.h's shapes, cut down: what each function was given is in its result. */
        val PIO_H = """
            #pragma once
            #include <stdint.h>

            typedef unsigned int uint;

            typedef struct pio_program {
                const uint16_t *instructions;
                uint8_t length;
                int8_t origin;
            } pio_program_t;

            typedef struct {
                uint32_t clkdiv;
                uint32_t execctrl;
                uint32_t shiftctrl;
                uint32_t pinctrl;
            } pio_sm_config;

            typedef struct pio_hw { uint32_t ctrl; } pio_hw_t;
            static inline pio_hw_t *pio0_get(void) {
                static pio_hw_t hw;
                return &hw;
            }
            #define pio0 (pio0_get())

            static inline pio_sm_config pio_get_default_sm_config(void) {
                pio_sm_config c = {1u << 16, 31u << 12, 0u, 0u};
                return c;
            }

            static inline void sm_config_set_wrap(pio_sm_config *c, uint wrap_target, uint wrap) {
                c->execctrl = (wrap_target << 7) | (wrap << 12);
            }

            static inline void sm_config_set_clkdiv(pio_sm_config *c, float div) {
                c->clkdiv = (uint32_t)(div * 256.0f);
            }

            static inline uint sm_config_wrap(const pio_sm_config *c) {
                return (c->execctrl >> 12) & 31u;
            }

            static inline uint pio_add_program(pio_hw_t *pio, const pio_program_t *program) {
                uint sum = pio->ctrl;
                for (uint i = 0; i < program->length; i++) {
                    sum += program->instructions[i];
                }
                return sum * 1000u + program->length * 10u + (program->origin < 0 ? 1u : (uint)program->origin);
            }

            static inline int pio_sm_init(pio_hw_t *pio, uint sm, uint initial_pc, const pio_sm_config *config) {
                return (int)(pio->ctrl + sm + config->execctrl + initial_pc);
            }
        """.trimIndent() + "\n"

        /** A C++ class whose copy is deleted: any copy in the emitted C++ fails to compile. */
        val SHADOW_HXX = """
            #pragma once
            #include <cstdint>

            namespace pio
            {
              class Shadow
              {
              public:
                  Shadow() = default;
                  Shadow(const Shadow&) = delete;
                  Shadow& operator=(const Shadow&) = delete;
                  Shadow(Shadow&&) = default;

                  void bump() { ++count_; }
                  std::int32_t count() const { return count_; }

              private:
                  std::int32_t count_ = 0;
              };

              inline std::int32_t shadow_count(const Shadow* s) { return s->count(); }
              inline std::int32_t shadow_value(Shadow s) { return s.count(); }
            }
        """.trimIndent() + "\n"

        /** What changes in the header, and the message that names it. */
        val DRIFTS = listOf(
            Drift("field", "const uint16_t *instructions;", "const uint32_t *instructions;", "Kira's Program.instructions no longer matches its C++ header"),
            Drift("const parameter", "const pio_sm_config *config)", "pio_sm_config *config)", "pio_sm_init"),
            Drift("receiver", "pio_sm_config *c, uint wrap_target", "pio_sm_config c, uint wrap_target", "sm_config_set_wrap"),
        )
    }

    private data class Drift(val what: String, val from: String, val to: String, val message: String)
}
