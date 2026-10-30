package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppPlacement
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Design 4.2 and table 5.2 over small modules. The parts that lower bodies and
 * expressions are faked where a test needs a definition to exist, so this file depends on
 * no other wave-2 package.
 */
class CppDeclEmitterTest {
    /** A statement part that writes one recognisable line per body, so definitions have a shape to check. */
    private object FakeStmts : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            w.line("/* stmt */")
        }

        override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
            w.line("return {}; // body of ${fn?.name}")
        }
    }

    /** An expression part that spells every expression as its node kind. */
    private object FakeExprs : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = "<${e.javaClass.simpleName}>"
    }

    private val fakeBodies = CppEmitParts(stmts = FakeStmts, exprs = FakeExprs)

    private fun header(
        body: String,
        uri: String = "test:main",
        parts: CppEmitParts = CppEmitParts.standard(),
        options: CppOptions = CppOptions(lineDirectives = false),
        usageOf: ((CppEmitContextImpl) -> CppUsage)? = null,
    ): String {
        val (emitted, _) = DeclTestSupport.emitWith(DeclTestSupport.module(uri, body), uri = uri, parts = parts, options = options, usageOf = usageOf)
        val errors = emitted.diagnostics.filter { it.isError }
        if (errors.isNotEmpty()) {
            fail("errors:\n" + errors.joinToString("\n") { it.render() } + "\n--- header ---\n" + emitted.header)
        }
        return CppWriter.normalize(emitted.header)
    }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    // ---- section 6: enums, aliases, constants -----------------------------------------------

    @Test
    fun constantsKeepTheirSpellingAndTakeTheirSuffix() {
        val h = header(
            """
            pub LIMIT: Int32 = 10
            pub IP: Str = "192.168.1.62"
            pub MAGIC: Arr<UInt8, 4> = @arrOf(0x55, 0xAA, 0x05, 0x0A)
            pub MASK: UInt32 = 0xFFFFFFFF
            pub HALF: Float32 = 0.5
            pub RATE: Float64 = 1e-3
            pub WHOLE: Float32 = 2
            pub BIG: UInt64 = 0xFFFFFFFFFFFFFFF
            pub QUOTE: Str = "say \"hi\"\n"
            pub NL: Char = '\n'
            pub DOUBLED: Int32 = LIMIT * 2
            pub NEG: Int32 = -7
            pub LIST: Arr<Int32> = @arrOf(1, 2, 3)
            """
        )
        assertContains(
            h,
            "inline constexpr std::int32_t LIMIT = 10;",
            "inline constexpr const char* IP = \"192.168.1.62\";",
            "inline constexpr std::array<std::uint8_t, 4> MAGIC = {0x55, 0xAA, 0x05, 0x0A};",
            "inline constexpr std::uint32_t MASK = 0xFFFFFFFFu;",
            "inline constexpr float HALF = 0.5f;",
            "inline constexpr double RATE = 1e-3;",
            "inline constexpr float WHOLE = 2.0f;",
            "inline constexpr std::uint64_t BIG = 0xFFFFFFFFFFFFFFF;",
            "inline constexpr const char* QUOTE = \"say \\\"hi\\\"\\n\";",
            "inline constexpr char NL = '\\n';",
            "inline constexpr std::int32_t DOUBLED = 20;",
            "inline constexpr std::int32_t NEG = -7;",
            "inline const kira::List<std::int32_t> LIST = {1, 2, 3};",
        )
        // consecutive one-liners are not separated by blank lines
        assertContains(h, "LIMIT = 10;\n  inline constexpr const char* IP")
    }

    @Test
    fun moduleStateIsAnInlineVariableInTheHeaderOrStaticInTheSource() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub servoMin: Mutable<Int32> = 1230
                calls: Mutable<Int32> = 0
                LOCAL: Float32 = 0.10
                """,
            ),
            uri = "test:main",
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("a module with private state needs a source"))
        assertContains(h, "  inline std::int32_t servoMin = 1230;")
        assertTrue(!h.contains("calls") && !h.contains("LOCAL"), h)
        // nothing in the module names them: -Wunused-variable (gcc) and -Wunused-const-variable (clang) under -Werror
        assertContains(s, "  namespace\n  {\n    [[maybe_unused]] std::int32_t calls = 0;\n    [[maybe_unused]] constexpr float LOCAL = 0.10f;\n  }")
    }

    /**
     * `@_iram` places a function's code in IRAM: its prototype and its definition both carry the section attribute, so
     * the function is one place in the image whatever the file it lands in. A function without the marker carries none.
     */
    @Test
    fun anIramFunctionCarriesTheSectionAttributeInItsPrototypeAndItsDefinition() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                count: Mutable<Int32> = 0

                pub @_iram onEdge: () Void {
                    count = count + 1
                }

                pub plain: () Void {
                    count = count + 2
                }
                """,
            ),
            uri = "test:main",
        )
        val attr = "__attribute__((section(\".iram1\"))) "
        assertContains(CppWriter.normalize(emitted.header), "${attr}void onEdge();")
        assertContains(CppWriter.normalize(emitted.source ?: fail("no source")), "${attr}void onEdge()")
        assertTrue(!CppWriter.normalize(emitted.header).contains("${attr}void plain"), "plain has no IRAM attribute")
    }

    /** rules.iram.callee: a call through an Fx value runs a closure, which an @_iram body may not reach. */
    @Test
    fun anIramBodyMayNotCallThroughAFunctionValue() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub @_iram onEdge: (f: Fx<Tuple0, Void>) Void {
                    f()
                }

                pub plain: (f: Fx<Tuple0, Void>) Void {
                    f()
                }
                """,
            ),
            uri = "test:main",
        )
        val errors = emitted.diagnostics.filter { it.isError }.map { it.render() }
        assertTrue(errors.any { it.contains("rules.iram.callee") && it.contains("'onEdge'") }, "a call through a function value in an IRAM body is refused: $errors")
        assertTrue(errors.none { it.contains("'plain'") }, "a plain function may call a function value: $errors")
    }

    /** rules.iram.callee: an @_iram body may call only @_iram Kira functions, and externs (their code is the binding's). */
    @Test
    fun anIramBodyMayCallOnlyIramFunctionsAndExterns() {
        fun errorsOf(body: String): List<String> {
            val (emitted, _) = DeclTestSupport.emitWith(DeclTestSupport.module("test:main", body), uri = "test:main")
            return emitted.diagnostics.filter { it.isError }.map { it.render() }
        }
        val plain = errorsOf(
            """
            count: Mutable<Int32> = 0

            helper: () Void {
                count = count + 1
            }

            pub @_iram onEdge: () Void {
                helper()
            }
            """,
        )
        assertTrue(plain.any { it.contains("rules.iram.callee") && it.contains("'helper'") }, "a plain callee is refused: $plain")

        val iram = errorsOf(
            """
            count: Mutable<Int32> = 0

            pub @_iram helper: () Void {
                count = count + 1
            }

            pub @_iram onEdge: () Void {
                helper()
            }
            """,
        )
        assertTrue(iram.isEmpty(), "an IRAM callee is allowed: $iram")

        val extern = errorsOf(
            """
            pub @_extern(cpp = "digitalWrite", header = "Arduino.h") digitalWrite: (pin: UInt8, level: UInt8) Void;

            pub @_iram onEdge: () Void {
                digitalWrite(2, 1)
            }
            """,
        )
        assertTrue(extern.none { it.contains("rules.iram.callee") }, "an extern is trusted, not refused: $extern")
    }

    @Test
    fun aPrivateDeclarationSomethingNamesIsNotMarkedUnused() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                calls: Mutable<Int32> = 0
                LOCAL: Float32 = 0.10
                helper: (v: Float32) Float32 { v * LOCAL }
                orphan: () Void { }
                pub go: () Void { calls += 1 }
                pub useIt: () Float32 { helper(1.0) }
                """,
            ),
            uri = "test:main",
            parts = fakeBodies,
        )
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(s, "    std::int32_t calls = 0;\n    constexpr float LOCAL = 0.10f;\n")
        assertContains(s, "    [[nodiscard]] float helper(float v);\n    [[maybe_unused]] void orphan();\n")
    }

    @Test
    fun anAliasIsAUsingDeclarationAndAGenericOneATemplate() {
        val h = header(
            """
            pub N: Size = 32
            pub Arr<UInt8, N> as Frame
            pub Tuple2<A, B> as Pair<A, B>
            pub Str as Name
            """
        )
        assertContains(
            h,
            "  using Frame = std::array<std::uint8_t, N>;\n",
            "  template<typename A, typename B>\n  using Pair = kira::Tuple2<A, B>;",
            "  using Name = kira::Str;",
        )
    }

    // ---- section 7: structs -----------------------------------------------------------------

    @Test
    fun structEqualityIsDefaultedOnlyWhereTheProgramComparesIt() {
        val plain = header("pub Pt: Struct { pub x: Int32 = 0 }")
        assertTrue(!plain.contains("operator=="), plain)
        val compared = header(
            "pub Pt: Struct { pub x: Int32 = 0 }",
            usageOf = { ctx -> CppUsage.of(compared = setOf(ctx.symbol.members["Pt"] as ClassSymbol)) },
        )
        assertContains(compared, "  struct Pt\n  {\n      std::int32_t x = 0;\n\n      bool operator==(const Pt&) const = default;\n  };")
    }

    @Test
    fun aStructCycleIsAnError() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", "pub A: Struct { pub b: Maybe<B> = null }\npub B: Struct { pub a: Maybe<A> = null }"),
            uri = "test:main",
        )
        val cycle = emitted.diagnostics.filter { it.code == CppPlacement.STRUCT_CYCLE_CODE }
        assertEquals(2, cycle.size, emitted.diagnostics.joinToString("\n") { it.render() })
        assertTrue(cycle.first().message.contains("A -> B") || cycle.first().message.contains("B -> A"), cycle.first().message)
    }

    // ---- sections 9 and 10, and the source -------------------------------------------------------

    @Test
    fun prototypesCarryNodiscardAndDefaultsDefinitionsDoNot() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub SCALE: Int32 = 100
                pub command: (verb: Str, args: Str = "", n: Int32 = SCALE) Str { "${'$'}{verb} ${'$'}{args} ${'$'}{n}" }
                isSpace: (c: Char) Bool { c == ' ' }
                pub tell: (unused: Int32, used: Int32) Void { trace(used) }
                """,
            ),
            uri = "test:main",
            parts = fakeBodies,
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(h, "  [[nodiscard]] kira::Str command(const kira::Str& verb, const kira::Str& args = \"\", std::int32_t n = SCALE);\n  void tell(std::int32_t unused, std::int32_t used);\n")
        assertTrue(!h.contains("isSpace"), "a private function stays out of the header:\n$h")
        assertContains(
            s,
            "#include \"main.kira.hxx\"\n#include \"kira/macro_push.hxx\"\nnamespace main_\n{\n  namespace\n  {\n    [[maybe_unused]] [[nodiscard]] bool isSpace(char c);\n\n    bool isSpace(char c)\n    {\n        return {}; // body of isSpace\n    }\n  }\n\n  kira::Str command(const kira::Str& verb, const kira::Str& args, std::int32_t n)\n  {\n      return {}; // body of command\n  }\n\n  void tell([[maybe_unused]] std::int32_t unused, std::int32_t used)\n  {\n      return {}; // body of tell\n  }\n}\n#include \"kira/macro_pop.hxx\"\n",
        )
    }

    @Test
    fun aHeaderOnlyModulePutsPrivateHelpersInImplAndInlinesEverything() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "lib:units",
                """
                pub SCALE: Int32 = 100
                clampPct: (v: Int32) Int32 { v }
                pub percent: (part: Int32, whole: Int32) Int32 { part * whole }
                pub @_const crc: (v: UInt32) UInt32 { v }
                """,
            ),
            uri = "lib:units",
            parts = fakeBodies,
            options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:units"), namespaces = mapOf("lib:units" to "golden::units")),
        )
        assertNull(emitted.source)
        val h = CppWriter.normalize(emitted.header)
        assertContains(
            h,
            "namespace golden::units\n{\n  inline constexpr std::int32_t SCALE = 100;\n\n  namespace impl_\n  {\n    [[nodiscard]] inline std::int32_t clampPct(std::int32_t v);\n  }\n  [[nodiscard]] inline std::int32_t percent(std::int32_t part, std::int32_t whole);\n  [[nodiscard]] constexpr std::uint32_t crc(std::uint32_t v);\n\n  namespace impl_\n  {\n    inline std::int32_t clampPct(std::int32_t v)\n    {\n        return {}; // body of clampPct\n    }\n  }\n\n  inline std::int32_t percent(std::int32_t part, std::int32_t whole)\n  {\n      return {}; // body of percent\n  }\n\n  constexpr std::uint32_t crc(std::uint32_t v)\n  {\n      return {}; // body of crc\n  }\n}\n",
        )
    }

    @Test
    fun aConstFunctionAndAGenericOneAreDefinedInTheHeaderOfASourceModule() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub @_const crc32: (buf: View<UInt8>) UInt32 { buf.size() as UInt32 }
                pub id<T>: (v: T) T { v }
                pub plain: () Void { }
                """,
            ),
            uri = "test:main",
            parts = fakeBodies,
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(
            h,
            "  [[nodiscard]] constexpr std::uint32_t crc32(kira::View<std::uint8_t> buf);\n  template<typename T>\n  [[nodiscard]] T id(const T& v);\n  void plain();\n\n  constexpr std::uint32_t crc32(kira::View<std::uint8_t> buf)\n  {\n      return {}; // body of crc32\n  }\n\n  template<typename T>\n  T id(const T& v)\n  {\n      return {}; // body of id\n  }\n}",
        )
        assertTrue(!s.contains("crc32") && !s.contains("id("), s)
        assertContains(s, "  void plain()\n  {\n      return {}; // body of plain\n  }")
    }

    @Test
    fun fxMainIsDeclaredInTheHeaderAndWrappedByIntMain() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("programs:forward", "CREEP: Float32 = 0.10\nmain: () Int32 { 0 }"),
            uri = "programs:forward",
            parts = fakeBodies,
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(h, "namespace forward\n{\n  [[nodiscard]] std::int32_t main();\n}")
        assertContains(
            s,
            "#include \"forward.kira.hxx\"\n#include \"kira/main.hxx\"\n#include \"kira/macro_push.hxx\"\nnamespace forward\n{\n  namespace\n  {\n    [[maybe_unused]] constexpr float CREEP = 0.10f;\n  }\n\n  std::int32_t main()\n  {\n      return {}; // body of main\n  }\n}\n#include \"kira/macro_pop.hxx\"\nint main(int argc, char** argv)\n{\n    return kira::rt::runMain(argc, argv, &forward::main);\n}\n",
        )
    }

    @Test
    fun aModuleWithNothingForASourceEmitsNone() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("pilot:carlink", "pub LINK_OK: Int32 = 0\npub WRITE_WAIT_MS: Int32 = 30"),
            uri = "pilot:carlink",
        )
        assertNull(emitted.source)
        assertContains(CppWriter.normalize(emitted.header), "  inline constexpr std::int32_t LINK_OK = 0;\n  inline constexpr std::int32_t WRITE_WAIT_MS = 30;\n}")
    }

    // ---- includes, banner, namespace, profile, #line ---------------------------------------------

    @Test
    fun theHeaderIncludesTheRuntimeByProfileThenUsedModulesRelatively() {
        val units = DeclTestSupport.module("lib:units", "pub SCALE: Int32 = 100")
        val report = DeclTestSupport.module("app:report", "lib.units as units\nlib.units.SCALE as SCALE\npub LIMIT: Int32 = SCALE")
        val hosted = DeclTestSupport.emitWith(units, report, uri = "app:report").first
        assertContains(
            CppWriter.normalize(hosted.header),
            "// GENERATED by kira dev --target cpp from app.report (src/app/report.kira). Do not edit: python tools/kira_gen.py --check fails on any difference.\n#pragma once\n#include \"kira/rt.hxx\"\n#include \"../lib/units.kira.hxx\"\n#include \"kira/macro_push.hxx\"\nnamespace report\n{\n  inline constexpr std::int32_t LIMIT = ::units::SCALE;\n}\n#include \"kira/macro_pop.hxx\"\n",
        )
        val freestanding = DeclTestSupport.emitWith(
            units, report, uri = "app:report",
            options = CppOptions(lineDirectives = false, freestanding = listOf("app:**"), namespaces = mapOf("app:report" to "bibo::report")),
        ).first
        assertContains(CppWriter.normalize(freestanding.header), "#include \"kira/core.hxx\"\n#include \"../lib/units.kira.hxx\"\n#include \"kira/macro_push.hxx\"\nnamespace bibo::report\n{")
    }

    @Test
    fun aKiraWrittenStdlibModuleIsHeaderOnlyUnderKiraStd() {
        val (emitted, ctx) = DeclTestSupport.emitWith(
            DeclTestSupport.module("app:calc", "kira.math as math\nkira.bytes as bytes\npub ZERO: Float64 = 0.0"),
            uri = "app:calc",
        )
        // kira:math has Kira-written functions, so it is included through the include path; kira:bytes is all magic and is not.
        assertContains(CppWriter.normalize(emitted.header), "#include \"kira/rt.hxx\"\n#include \"kira/std/math.kira.hxx\"\n#include \"kira/macro_push.hxx\"\n")
        val math = ctx.program.module("kira:math") ?: fail("no kira:math")
        val files = ctx.layout.filesFor(math.uri, java.nio.file.Path.of(math.source.file))
        assertNull(files.source)
        assertEquals("kira::math", ctx.layout.namespaceFor("kira:math"))
        assertTrue(files.header.toString().replace('\\', '/').endsWith("/gen/kira/kira/std/math.kira.hxx"), files.header.toString())
    }

    @Test
    fun theStdlibBannerNamesTheModuleNotTheCheckout() {
        val (emitted, _) = DeclTestSupport.emit(DeclTestSupport.module("app:calc", "pub ZERO: Float64 = 0.0"), includeStdlib = true).let { e ->
            e.module("kira:bytes") to e
        }
        assertContains(CppWriter.normalize(emitted.header), "// GENERATED by kira dev --target cpp from kira.bytes (kira/bytes.kira). Do not edit")
        assertContains(CppWriter.normalize(emitted.header), "#include \"kira/rt.hxx\"\n#include \"kira/macro_push.hxx\"\n#include \"kira/macro_pop.hxx\"\n")
    }

    @Test
    fun lineDirectivesFollowTheOption() {
        val src = DeclTestSupport.module("test:main", "pub f: () Void { }")
        val off = DeclTestSupport.emitWith(src, uri = "test:main").second
        assertNull(off.lineDirective((off.symbol.members["f"] as FnSymbol).decl!!))
        val on = DeclTestSupport.emitWith(src, uri = "test:main", options = CppOptions(lineDirectives = true)).second
        assertEquals("#line 1 \"src/test/main.kira\"", on.lineDirective((on.symbol.members["f"] as FnSymbol).decl!!))
    }

    // ---- names -------------------------------------------------------------------------------------

    @Test
    fun aKeywordNameGetsATrailingUnderscore() {
        val h = header("pub Box: Struct { pub new: Int32 = 0 pub delete: () Void; }\npub register: (template: Int32) Void;")
        assertContains(h, "      std::int32_t new_ = 0;\n\n      void delete_() const;", "void register_(std::int32_t template_);")
    }

    @Test
    fun aPubNameOnTheMacroListIsWarned() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module("test:main", "pub ERROR: Int32 = 1\npub IN: Int32 = 0\nOUT: () Void { }\npub max: (a: Int32, b: Int32) Int32;"),
            uri = "test:main",
        )
        val warnings = emitted.diagnostics.filter { it.code == CppDeclEmitter.MACRO_NAME_CODE }
        assertEquals(listOf("ERROR", "IN"), warnings.map { it.message.substringAfter("'").substringBefore("'") }, warnings.joinToString("\n") { it.render() })
        assertTrue(warnings.all { !it.isError })
    }

    @Test
    fun aStaticAssertGoesThroughTheExpressionPart() {
        val h = header("pub @_const crc: (v: UInt32) UInt32 { v }\n@_static_assert(crc(1) == 1, \"crc\")", parts = fakeBodies)
        assertContains(h, "  static_assert(<BinaryExpr>, \"crc\");\n}")
    }

    @Test
    fun whatNoPartLowersIsReportedNotGuessed() {
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                "test:main",
                """
                pub Node: Any { pub v: Int32 = 0 }
                pub Shape: Any { pub area: () Int32; }
                pub f: () Void { }
                pub @_extern(cpp = "bibo::openCar", header = "car.hxx") openCar: () Int32;
                """,
            ),
            uri = "test:main",
            // Every part at its refusing default, whichever parts the packages have registered since.
            parts = CppEmitParts(),
        )
        val messages = emitted.diagnostics.filter { it.code == "cpp.unsupported" }.map { it.message }
        assertEquals(
            listOf(
                "the extern declaration 'openCar' is not lowered yet",
                "the trait 'Shape' is not lowered yet",
                "the class 'Node' is not lowered yet",
                "the body of 'f' is not lowered yet",
            ),
            messages,
        )
        // the header still forward-declares what the classes part will define
        assertContains(emitted.header, "  class Node;\n  class Shape;\n")
    }
}
