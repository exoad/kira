package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppPlacement
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the first review of the declaration emitter found wrong, each pinned: the shapes
 * the goldens cannot hold because they need a body, a class, a comparison or a cycle.
 */
class CppDeclFixesTest {
    private object FakeStmts : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            w.line("/* stmt */")
        }

        override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
            w.line("return {}; // body of ${fn?.name}")
        }
    }

    private object FakeExprs : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = "<${e.javaClass.simpleName}>"
    }

    /** A classes part that writes an empty class, so a class-typed value has something to construct. */
    private object FakeClasses : CppClassesPart {
        override fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter) {
            w.line("class ${sym.name} {};")
        }

        override fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean) {}
    }

    private val fakeBodies = CppEmitParts(stmts = FakeStmts, exprs = FakeExprs, classes = FakeClasses)

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    private fun emit(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null) =
        DeclTestSupport.emitWith(DeclTestSupport.module(uri, body), uri = uri, parts = fakeBodies, options = options, usageOf = usageOf)

    private fun header(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null): String {
        val (emitted, _) = emit(body, uri, options, usageOf)
        val errors = emitted.diagnostics.filter { it.isError }
        if (errors.isNotEmpty()) {
            fail("errors:\n" + errors.joinToString("\n") { it.render() } + "\n--- header ---\n" + emitted.header)
        }
        return CppWriter.normalize(emitted.header)
    }

    // ---- issue 2: null ---------------------------------------------------------------------------

    @Test
    fun aNullDefaultIsKiraNone() {
        val h = header(
            """
            pub struct Cfg { pub limit: Maybe<Int32> = null }
            pub mut last: Maybe<Int32> = null
            pub fx find: (key: Str, hint: Maybe<Int32> = null) Maybe<Int32>;
            """
        )
        assertContains(
            h,
            "      kira::Maybe<std::int32_t> limit = kira::none;",
            "  inline kira::Maybe<std::int32_t> last = kira::none;",
            "[[nodiscard]] kira::Maybe<std::int32_t> find(const kira::Str& key, const kira::Maybe<std::int32_t>& hint = kira::none);",
        )
        assertTrue(!h.contains("kira::core::null"), h)
    }

    // ---- issue 3: numbers ------------------------------------------------------------------------

    @Test
    fun decimalsWithLeadingZerosAndWrappedHexAreSpelledAsKiraReadsThem() {
        val h = header(
            """
            pub TEN: Int32 = 010
            pub NINE: Int32 = 09
            pub ZERO: Int32 = 000
            pub NEG1: Int64 = 0xFFFFFFFFFFFFFFFF
            pub NEG32: Int32 = 0xFFFFFFFF
            pub NEG8: Int8 = 0xFF
            pub BIG: UInt64 = 0xFFFFFFFFFFFFFFFF
            pub MASK: UInt32 = 0xFFFFFFFF
            pub MINUS: Int32 = -010
            pub TENS: Arr<Int32, 010> = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
            pub enum Kind: Int32 { K_A = 010, K_B = 0x7F }
            pub enum Wide: Int64 { W_A = 0xFFFFFFFFFFFFFFFF }
            """
        )
        assertContains(
            h,
            "inline constexpr std::int32_t TEN = 10;",
            "inline constexpr std::int32_t NINE = 9;",
            "inline constexpr std::int32_t ZERO = 0;",
            "inline constexpr std::int64_t NEG1 = static_cast<std::int64_t>(0xFFFFFFFFFFFFFFFFu);",
            "inline constexpr std::int32_t NEG32 = static_cast<std::int32_t>(0xFFFFFFFFu);",
            "inline constexpr std::int8_t NEG8 = static_cast<std::int8_t>(0xFFu);",
            "inline constexpr std::uint64_t BIG = 0xFFFFFFFFFFFFFFFFu;",
            "inline constexpr std::uint32_t MASK = 0xFFFFFFFFu;",
            "inline constexpr std::int32_t MINUS = -10;",
            "inline constexpr std::array<std::int32_t, 10> TENS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};",
            "      K_A = 10,\n      K_B = 0x7F,",
            "      W_A = static_cast<std::int64_t>(0xFFFFFFFFFFFFFFFFu),",
        )
    }

    // ---- issue 4: order --------------------------------------------------------------------------

    @Test
    fun aConstantOfStructTypeFollowsItsStructAndForwardReferencesAreReordered() {
        val h = header(
            """
            pub ORIGIN: Pt = Pt {}
            pub mut cursor: Pt = Pt {}
            pub alias Frame as Arr<UInt8, N>
            pub A: Int32 = B
            pub K: Kind = Kind.K_A
            pub N: Size = 4
            pub B: Int32 = 2
            pub enum Kind: Int32 { K_A = 0 }
            pub struct Pt { pub x: Int32 = 0 }
            pub SCALE: Int32 = 3
            """
        )
        // values that need nothing later keep their place; each forward reference waits for its target
        assertContains(
            h,
            "  inline constexpr kira::Size N = 4;\n  using Frame = std::array<std::uint8_t, N>;\n  inline constexpr std::int32_t B = 2;\n  inline constexpr std::int32_t A = B;\n\n  enum class Kind : std::int32_t\n  {\n      K_A = 0,\n  };\n\n  inline constexpr Kind K = Kind::K_A;\n  inline constexpr std::int32_t SCALE = 3;\n\n  struct Pt\n  {\n      std::int32_t x = 0;\n  };\n\n  inline constexpr Pt ORIGIN = Pt{};\n  inline Pt cursor = Pt{};\n}",
        )
    }

    @Test
    fun aStructWhoseDefaultNamesALaterConstantFollowsIt() {
        val h = header(
            """
            pub struct S { pub n: Int32 = LIMIT pub fx f: (x: Int32 = LIMIT) Int32; }
            pub LIMIT: Int32 = 5
            """
        )
        assertContains(h, "  inline constexpr std::int32_t LIMIT = 5;\n\n  struct S\n  {\n      std::int32_t n = LIMIT;\n\n      [[nodiscard]] std::int32_t f(std::int32_t x = LIMIT) const;\n  };")
    }

    @Test
    fun aCycleThatIsNotByValueContainmentIsAnError() {
        val (emitted, _) = emit(
            """
            pub struct S { pub xs: List<S> = EMPTY }
            pub EMPTY: List<S> = []
            """
        )
        val cycle = emitted.diagnostics.filter { it.code == CppPlacement.DECL_CYCLE_CODE }
        assertEquals(2, cycle.size, emitted.diagnostics.joinToString("\n") { it.render() })
        assertTrue(cycle.any { it.message.contains("S -> EMPTY -> S") || it.message.contains("EMPTY -> S -> EMPTY") }, cycle.joinToString { it.message })
    }

    // ---- issue 5: private declarations the header names -----------------------------------------

    @Test
    fun privateDeclarationsAPublicOneNamesGoInTheHeadersImplNamespace() {
        val (emitted, _) = emit(
            """
            LIMIT: Int32 = 5
            enum Mode: Int32 { MODE_A = 0 }
            struct Inner { pub v: Int32 = 1 }
            struct Unseen { pub v: Int32 = 2 }
            fx helper: (v: Int32) Int32 { return v }
            fx unseenHelper: (v: Int32) Int32 { return v }
            pub struct S { pub m: Mode = Mode.MODE_A pub n: Int32 = LIMIT pub i: Inner = Inner {} }
            pub fx f: (x: Int32 = LIMIT) Int32 { return unseenHelper(x) }
            pub @_const fx c: (x: Int32) Int32 { return helper(x) }
            @_static_assert(LIMIT == 5, "limit")
            """
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(
            h,
            "  namespace impl_\n  {\n    inline constexpr std::int32_t LIMIT = 5;\n\n    enum class Mode : std::int32_t\n    {\n        MODE_A = 0,\n    };\n\n    struct Inner\n    {\n        std::int32_t v = 1;\n    };\n  }\n\n  struct S\n  {\n      impl_::Mode m = impl_::Mode::MODE_A;\n      std::int32_t n = impl_::LIMIT;\n      impl_::Inner i = impl_::Inner{};\n  };",
            "  namespace impl_\n  {\n    [[nodiscard]] inline std::int32_t helper(std::int32_t v);\n  }\n  [[nodiscard]] std::int32_t f(std::int32_t x = impl_::LIMIT);\n  [[nodiscard]] constexpr std::int32_t c(std::int32_t x);",
            "  namespace impl_\n  {\n    inline std::int32_t helper(std::int32_t v)\n    {\n        return {}; // body of helper\n    }\n  }\n\n  constexpr std::int32_t c(std::int32_t x)",
            "  static_assert(<BinaryExpr>, \"limit\");",
        )
        assertTrue(!h.contains("Unseen") && !h.contains("unseenHelper"), "what only the .cxx names stays out of the header:\n$h")
        assertContains(s, "  namespace\n  {\n    struct Unseen\n    {\n        std::int32_t v = 2;\n    };\n\n    [[nodiscard]] std::int32_t unseenHelper(std::int32_t v);")
        assertTrue(!s.contains("struct Unseen;"), "a private type nothing refers to early gets no forward declaration:\n$s")
    }

    @Test
    fun aHeaderOnlyModuleDefinesAPrivateStructBeforeThePublicOneHoldingIt() {
        val h = header(
            """
            pub struct S { pub i: Inner = Inner {} pub xs: List<Later> = List<Later> {} }
            struct Inner { pub v: Int32 = 1 }
            struct Later { pub v: Int32 = 2 }
            """,
            uri = "lib:units",
            options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:units")),
        )
        assertContains(
            h,
            "  struct S;\n\n  namespace impl_\n  {\n    struct Later;\n  }\n\n  namespace impl_\n  {\n    struct Inner\n    {\n        std::int32_t v = 1;\n    };\n  }\n\n  struct S\n  {\n      impl_::Inner i = impl_::Inner{};\n      kira::List<impl_::Later> xs = kira::List<impl_::Later>{};\n  };\n\n  namespace impl_\n  {\n    struct Later\n    {\n        std::int32_t v = 2;\n    };\n  }",
        )
    }

    // ---- issue 6: equality closes over the fields ---------------------------------------------

    @Test
    fun comparingAStructDefaultsEqualityOnEveryStructItHolds() {
        val h = header(
            """
            pub struct Inner { pub v: Int32 = 0 }
            pub struct Outer { pub a: Inner = Inner {} pub xs: List<Inner> = List<Inner> {} }
            pub struct Apart { pub v: Int32 = 0 }
            """,
            usageOf = { ctx -> CppUsage.of(compared = setOf(ctx.symbol.members["Outer"] as ClassSymbol)) },
        )
        assertContains(h, "      bool operator==(const Inner&) const = default;", "      bool operator==(const Outer&) const = default;")
        assertTrue(!h.contains("operator==(const Apart&)"), h)
    }

    @Test
    fun comparingAContainerOfStructsMarksTheStruct() {
        val (_, ctx) = emit(
            """
            pub struct Pt { pub x: Int32 = 0 }
            pub fx same: (a: List<Pt>, b: List<Pt>) Bool { return a == b }
            """
        )
        val pt = ctx.symbol.members["Pt"] as ClassSymbol
        val list = ctx.program.builtins.classFor("List") ?: fail("no List")
        net.exoad.kira.compiler.analysis.types.AstTree.walk(ctx.module.ast) { node ->
            if (node is BinaryExpr) {
                ctx.model.types[node.leftExpr] = KType.Nominal(list, listOf(TypeArg.Ty(KType.Nominal(pt))))
                ctx.model.types[node.rightExpr] = KType.Nominal(list, listOf(TypeArg.Ty(KType.Nominal(pt))))
            }
        }
        assertTrue(CppUsage.scan(ctx.program).needsEquality(pt), "List<Pt> == List<Pt> compares Pt")
    }

    @Test
    fun aComparedStructWithAFieldThatHasNoEqualityIsAnError() {
        val (emitted, _) = emit(
            "pub struct Cb { pub f: Fx<Tuple0, Void> = null }",
            usageOf = { ctx -> CppUsage.of(compared = setOf(ctx.symbol.members["Cb"] as ClassSymbol)) },
        )
        val error = emitted.diagnostics.singleOrNull { it.code == CppDeclEmitter.STRUCT_EQUALITY_CODE } ?: fail(emitted.diagnostics.joinToString("\n") { it.render() })
        assertTrue(error.message.contains("'f'"), error.message)
    }

    // ---- issue 7: an empty class construction ----------------------------------------------------

    @Test
    fun anEmptyClassConstructionGoesThroughMakeShared() {
        val h = header(
            """
            pub class Reg { pub n: Int32 = 0 }
            pub mut reg: Reg = Reg {}
            pub struct Holder { pub r: Reg = Reg {} pub p: Pt = Pt {} }
            pub struct Pt { pub x: Int32 = 0 }
            """
        )
        assertContains(h, "  inline kira::Rc<Reg> reg = std::make_shared<Reg>();", "      kira::Rc<Reg> r = std::make_shared<Reg>();\n      Pt p = Pt{};")
    }

    // ---- issue 8: a generic struct's out-of-line methods ---------------------------------------

    @Test
    fun aGenericStructsMethodIsDefinedWithItsTemplateHeadAndArguments() {
        val (emitted, _) = emit(
            """
            pub struct Pair<T> {
                pub a: T
                pub fx first: () T { return a }
                pub fx swap<U>: (u: U) U { return u }
            }
            """
        )
        val h = CppWriter.normalize(emitted.header)
        assertContains(
            h,
            "  template<typename T>\n  struct Pair\n  {\n      T a{};\n\n      [[nodiscard]] T first() const;\n      template<typename U>\n      [[nodiscard]] U swap(const U& u) const;\n  };",
            "  template<typename T>\n  T Pair<T>::first() const\n  {\n      return {}; // body of first\n  }\n\n  template<typename T>\n  template<typename U>\n  U Pair<T>::swap(const U& u) const\n  {",
        )
        assertTrue(emitted.source == null || !emitted.source!!.contains("Pair"), "a template is defined in the header:\n${emitted.source}")
    }

    // ---- issue 9: [[maybe_unused]] reads uses, not names ---------------------------------------

    @Test
    fun aMemberNameOrANamedArgumentIsNotAUseOfTheParameter() {
        val (emitted, _) = emit(
            """
            pub struct P { pub x: Int32 = 0 }
            pub fx f: (x: Int32, p: P) Int32 { return p.x }
            pub fx g: (x: Int32) P { return P { x = 1 } }
            pub fx h: (x: Int32) P { return P { x = x } }
            """
        )
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(
            s,
            "  std::int32_t f([[maybe_unused]] std::int32_t x, const P& p)",
            "  P g([[maybe_unused]] std::int32_t x)",
            "  P h(std::int32_t x)",
        )
    }

    // ---- issue 10: parameters that would shadow ------------------------------------------------

    @Test
    fun aParameterNamedLikeAFieldOrAModuleDeclarationIsRenamed() {
        val (emitted, ctx) = emit(
            """
            pub LIMIT: Int32 = 3
            pub struct Pt { pub x: Int32 = 0 pub mut fx setX: (x: Int32, y: Int32) Void { x = y } }
            pub fx g: (LIMIT: Int32, other: Int32) Int32 { return LIMIT + other }
            """
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(h, "      void setX(std::int32_t x_p, std::int32_t y);", "  [[nodiscard]] std::int32_t g(std::int32_t limit_p, std::int32_t other);")
        assertContains(s, "  void Pt::setX(std::int32_t x_p, std::int32_t y)", "  std::int32_t g(std::int32_t limit_p, std::int32_t other)")
        val g = ctx.symbol.members["g"] as FnSymbol
        assertEquals("limit_p", ctx.paramName(g.params[0]))
        assertEquals("other", ctx.paramName(g.params[1]))
    }

    // ---- issue 15: #line before every definition -----------------------------------------------

    @Test
    fun lineDirectivesPrecedeDefinitionsWhenAsked() {
        val (emitted, _) = emit(
            """
            pub fx f: () Void { }
            pub @_const fx c: (v: Int32) Int32 { return v }
            """,
            options = CppOptions(lineDirectives = true),
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(h, "  #line 4 \"src/test/main.kira\"\n  constexpr std::int32_t c(std::int32_t v)")
        assertContains(s, "  #line 3 \"src/test/main.kira\"\n  void f()")
    }

    // ---- the Int alias lives in kira:core, so its header is included and reached ----------------

    @Test
    fun aNameFromAnUnusedStdlibModuleIncludesItsHeaderAndReachesIt() {
        val (emitted, _) = emit("pub X: Int = 3\npub Y: Int32 = 4")
        val h = CppWriter.normalize(emitted.header)
        assertContains(h, "#include \"kira/rt.hxx\"\n#include \"kira/std/core.kira.hxx\"\n#include \"kira/macro_push.hxx\"\n", "  inline constexpr ::kira::core::Int X = 3;")
        assertEquals(listOf("kira:core"), emitted.uses)
        val plain = emit("pub Y: Int32 = 4").first
        assertEquals(emptyList(), plain.uses)
        assertTrue(!plain.header.contains("kira/std/"), plain.header)
    }
}
