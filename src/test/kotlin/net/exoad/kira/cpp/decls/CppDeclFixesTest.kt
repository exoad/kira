package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
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
            // the parser folds a 64-bit hex literal to its Int64 value (-1); a narrower one past its signed width is a typer range error
            "inline constexpr std::int64_t NEG1 = static_cast<std::int64_t>(0xFFFFFFFFFFFFFFFFu);",
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
            pub fx f: (x: Int32) Int32 { return unseenHelper(x) }
            pub @_const fx c: (x: Int32) Int32 { return helper(x) }
            @_static_assert(LIMIT == 5, "limit")
            """
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(
            h,
            "  namespace impl_\n  {\n    inline constexpr std::int32_t LIMIT = 5;\n\n    enum class Mode : std::int32_t\n    {\n        MODE_A = 0,\n    };\n\n    struct Inner\n    {\n        std::int32_t v = 1;\n    };\n  }\n\n  struct S\n  {\n      impl_::Mode m = impl_::Mode::MODE_A;\n      std::int32_t n = impl_::LIMIT;\n      impl_::Inner i = impl_::Inner{};\n  };",
            "  namespace impl_\n  {\n    [[nodiscard]] inline std::int32_t helper(std::int32_t v);\n  }\n  [[nodiscard]] std::int32_t f(std::int32_t x);\n  [[nodiscard]] constexpr std::int32_t c(std::int32_t x);",
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
        // Inner is held by value, so it comes first; a List element needs only the forward
        // declaration, and the empty List default is value-initialized in place (`xs{}`), the
        // one form libc++ accepts before Later is complete.
        assertContains(
            h,
            "  struct S;\n\n  namespace impl_\n  {\n    struct Later;\n  }\n\n  namespace impl_\n  {\n    struct Inner\n    {\n        std::int32_t v = 1;\n    };\n  }\n\n  struct S\n  {\n      impl_::Inner i = impl_::Inner{};\n      kira::List<impl_::Later> xs{};\n  };\n\n  namespace impl_\n  {\n    struct Later\n    {\n        std::int32_t v = 2;\n    };\n  }",
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
        // A required field: `= null` on an Fx is what the semantic pass rejects (types are non-nullable).
        val (emitted, _) = emit(
            "pub struct Cb { pub f: Fx<Tuple0, Void> }",
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
            pub struct Holder { pub r: Reg = Reg {} pub p: Pt = Pt {} }
            pub struct Pt { pub x: Int32 = 0 }
            """
        )
        // (module-level mut state may not start from a class construction: D49 wants a constant expression)
        assertContains(h, "      kira::Rc<Reg> r = std::make_shared<Reg>();\n      Pt p = Pt{};")
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

    // ---- the Int and Float aliases of kira:core are spelled as their targets -------------------

    @Test
    fun theCoreAliasesAreSpelledAsTheirTargetsAndReachNoStdlibHeader() {
        // kira:core's header would hold its traits (unsupported until W2.4) and is hosted, which a
        // freestanding module could not include; `Int` is `std::int32_t` wherever it is written.
        val (emitted, _) = emit("pub X: Int = 3\npub F: Float = 0.5\npub alias Idx as Int\npub struct S { pub n: Int = 1 }\npub fx f: (n: Int, xs: List<Int>) Int;")
        val h = CppWriter.normalize(emitted.header)
        assertContains(
            h,
            "#include \"kira/rt.hxx\"\n#include \"kira/macro_push.hxx\"\n",
            "  inline constexpr std::int32_t X = 3;",
            "  inline constexpr float F = 0.5f;",
            "  using Idx = std::int32_t;",
            "      std::int32_t n = 1;",
            "  [[nodiscard]] std::int32_t f(std::int32_t n, const kira::List<std::int32_t>& xs);",
        )
        assertEquals(emptyList(), emitted.uses)
        assertTrue(!h.contains("kira/std/") && !h.contains("kira::core"), h)
        val freestanding = emit("pub X: Int = 3", uri = "pico:hall", options = CppOptions(lineDirectives = false, freestanding = listOf("pico:hall"))).first
        assertContains(CppWriter.normalize(freestanding.header), "#include \"kira/core.hxx\"\n#include \"kira/macro_push.hxx\"\n", "  inline constexpr std::int32_t X = 3;")
        assertEquals(emptyList(), freestanding.uses)
    }

    // ---- fix round 2 -----------------------------------------------------------------------------

    @Test
    fun aLiteralUnderAMaybeCarriesTheNarrowTypeItConvertsTo() {
        // MSVC /W4 /WX: C4244 inside <optional> for `kira::Maybe<std::uint8_t> m = 200;` (int to
        // unsigned char), `Maybe<float> = 0.5` (double to float) and `Maybe<std::int16_t> = 5`.
        val h = header(
            """
            pub MU8: Maybe<UInt8> = 200
            pub MI8: Maybe<Int8> = -7
            pub MI16: Maybe<Int16> = 5
            pub MU16: Maybe<UInt16> = 0x10
            pub MI32: Maybe<Int32> = 42
            pub MU32: Maybe<UInt32> = 5
            pub MI64: Maybe<Int64> = 5
            pub MSZ: Maybe<Size> = 3
            pub MF: Maybe<Float32> = 0.5
            pub MFI: Maybe<Float32> = 1
            pub MD: Maybe<Float64> = 0.5
            pub MFN: Maybe<Float32> = -0.25
            pub struct M { pub a: Maybe<UInt8> = 7 pub b: Maybe<Float32> = 2.5 pub c: UInt8 = 7 }
            pub fx g: (x: Maybe<Int16> = 3, y: Int16 = 3) Void;
            """
        )
        assertContains(
            h,
            "inline constexpr kira::Maybe<std::uint8_t> MU8 = std::uint8_t{200};",
            "inline constexpr kira::Maybe<std::int8_t> MI8 = std::int8_t{-7};",
            "inline constexpr kira::Maybe<std::int16_t> MI16 = std::int16_t{5};",
            "inline constexpr kira::Maybe<std::uint16_t> MU16 = std::uint16_t{0x10};",
            "inline constexpr kira::Maybe<std::int32_t> MI32 = 42;",
            "inline constexpr kira::Maybe<std::uint32_t> MU32 = 5u;",
            "inline constexpr kira::Maybe<std::int64_t> MI64 = std::int64_t{5};",
            "inline constexpr kira::Maybe<kira::Size> MSZ = kira::Size{3};",
            "inline constexpr kira::Maybe<float> MF = 0.5f;",
            "inline constexpr kira::Maybe<float> MFI = 1.0f;",
            "inline constexpr kira::Maybe<double> MD = 0.5;",
            "inline constexpr kira::Maybe<float> MFN = -0.25f;",
            "      kira::Maybe<std::uint8_t> a = std::uint8_t{7};\n      kira::Maybe<float> b = 2.5f;\n      std::uint8_t c = 7;",
            "void g(const kira::Maybe<std::int16_t>& x = std::int16_t{3}, std::int16_t y = 3);",
        )
    }

    @Test
    fun aContainerOfALaterStructIsOrderedOrValueInitializedAsTheCompilersNeed() {
        val h = header(
            """
            pub struct Early { pub xs: List<Later> = List<Later> {} pub m: Map<Str, Later> = Map<Str, Later> {} pub w: Weak<Node> }
            pub struct Queued { pub d: Deque<Later> = Deque<Later> {} }
            pub struct Viewed { pub v: View<Later> }
            pub struct Later { pub v: Int32 = 0 }
            pub class Node { pub v: Int32 = 0 }
            """,
            usageOf = { CppUsage.NONE },
        )
        // A List or Map value needs only the forward declaration, and its empty default is `xs{}`
        // (the prvalue form makes libc++ instantiate the temporary's destructor); a Deque, Stack,
        // Queue or View element must be complete on libc++ and MSVC, so Later moves up.
        assertContains(
            h,
            "  struct Early\n  {\n      kira::List<Later> xs{};\n      kira::Map<kira::Str, Later> m{};\n      kira::Weak<Node> w{};\n  };\n\n  struct Later\n  {\n      std::int32_t v = 0;\n  };\n\n  struct Queued\n  {\n      kira::Deque<Later> d{};\n  };\n\n  struct Viewed\n  {\n      kira::View<Later> v{};\n  };",
        )
    }

    @Test
    fun structsHoldingEachOtherInListsAreFineAndInDequesAreACycle() {
        val h = header("pub struct A { pub bs: List<B> = List<B> {} }\npub struct B { pub items: List<A> = List<A> {} }")
        assertContains(h, "  struct A\n  {\n      kira::List<B> bs{};\n  };\n\n  struct B\n  {\n      kira::List<A> items{};\n  };")
        val (emitted, _) = emit("pub struct A { pub bs: Deque<B> = Deque<B> {} }\npub struct B { pub items: Deque<A> = Deque<A> {} }")
        val cycle = emitted.diagnostics.filter { it.code == CppPlacement.DECL_CYCLE_CODE }
        assertEquals(2, cycle.size, emitted.diagnostics.joinToString("\n") { it.render() })
        assertTrue(cycle.all { it.message.contains("container") && it.message.contains("class") }, cycle.joinToString { it.message })
        val self = header("pub struct S { pub kids: List<S> = List<S> {} pub v: Int32 = 0 }")
        assertContains(self, "  struct S\n  {\n      kira::List<S> kids{};\n      std::int32_t v = 0;\n  };")
    }

    @Test
    fun aModuleNamedMainGetsAnEscapedNamespaceSoIntMainCanSitBesideIt() {
        val (emitted, _) = emit("pub LIMIT: Int32 = 3\nfx main: () Int32 { return 0 }", uri = "app:main")
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        assertContains(h, "namespace main_\n{\n  inline constexpr std::int32_t LIMIT = 3;\n\n  [[nodiscard]] std::int32_t main();\n}")
        assertContains(s, "namespace main_\n{\n  std::int32_t main()\n", "int main(int argc, char** argv)\n{\n    return kira::rt::runMain(argc, argv, &main_::main);\n}")
        assertTrue(!s.contains("namespace main\n"), s)
        // the same for a module named after a C library global: <cstdlib> declares ::exit
        val exit = emit("pub LIMIT: Int32 = 3", uri = "app:exit").first
        assertContains(CppWriter.normalize(exit.header), "namespace exit_\n{")
        val time = emit("pub LIMIT: Int32 = 3", uri = "app:time").first
        assertContains(CppWriter.normalize(time.header), "namespace time_\n{")
    }

    @Test
    fun anotherModulesPrivateDeclarationIsQualifiedWhereItsOwnHeaderPutIt() {
        val lib = DeclTestSupport.module(
            "lib:a",
            """
            LIMIT: Int32 = 5
            HIDDEN: Int32 = 6
            pub struct S { pub n: Int32 = LIMIT }
            pub fx f: (x: Int32) Int32 { return x + HIDDEN }
            """,
        )
        val app = DeclTestSupport.module("app:b", "use \"lib:a\"\npub Y: Int32 = 1")
        val (_, ctx) = DeclTestSupport.emitWith(lib, app, uri = "app:b", parts = fakeBodies)
        val a = ctx.program.module("lib:a") ?: fail("no lib:a")
        // LIMIT is a field default of an exported struct, so lib:a's header holds it in impl_; HIDDEN is only the body's, so the .cxx holds it.
        assertEquals("::a::impl_::LIMIT", ctx.qualified(a.members["LIMIT"]!!))
        assertEquals("::a::HIDDEN", ctx.qualified(a.members["HIDDEN"]!!))
        assertEquals("::a::f", ctx.qualified(a.members["f"]!!))
        // a header-only module keeps every private declaration in impl_
        val (_, ctx2) = DeclTestSupport.emitWith(lib, app, uri = "app:b", parts = fakeBodies, options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:a")))
        val a2 = ctx2.program.module("lib:a") ?: fail("no lib:a")
        assertEquals("::a::impl_::HIDDEN", ctx2.qualified(a2.members["HIDDEN"]!!))
    }

    @Test
    fun aFunctionAValueOrADefaultNamesIsHoistedBeforeIt() {
        val (emitted, _) = emit(
            """
            pub DATA: Arr<UInt8, 2> = [1, 2]
            pub X: UInt32 = crc8(DATA)
            pub @_const fx crc8: (buf: View<UInt8>) UInt32 { return 7 }
            pub struct S { pub n: Int32 = limit() }
            pub fx limit: () Int32 { return 1 }
            pub fx other: () Int32 { return 2 }
            """
        )
        val h = CppWriter.normalize(emitted.header)
        val s = CppWriter.normalize(emitted.source ?: fail("no source"))
        // crc8 is evaluated by X: its prototype and constexpr body precede X; limit is only named by
        // a default member initializer: its prototype precedes S and its body stays in the .cxx.
        // Hoisted prototypes come first, then hoisted bodies, then the values in their own order.
        assertContains(
            h,
            "  [[nodiscard]] constexpr std::uint32_t crc8(kira::View<std::uint8_t> buf);\n  [[nodiscard]] std::int32_t limit();\n\n  constexpr std::uint32_t crc8([[maybe_unused]] kira::View<std::uint8_t> buf)\n  {\n      return {}; // body of crc8\n  }\n\n  inline constexpr std::array<std::uint8_t, 2> DATA = {1, 2};\n  inline constexpr std::uint32_t X = <FunctionCallExpr>;\n\n  struct S\n  {\n      std::int32_t n = <FunctionCallExpr>;\n  };\n\n  [[nodiscard]] std::int32_t other();\n}",
        )
        assertEquals(1, Regex("crc8\\(kira::View<std::uint8_t> buf\\);").findAll(h).count(), "one prototype:\n$h")
        assertEquals(1, Regex("std::int32_t limit\\(\\);").findAll(h).count(), "one prototype:\n$h")
        assertContains(s, "  std::int32_t limit()\n  {\n      return {}; // body of limit\n  }")
        assertTrue(!s.contains("crc8"), "a constexpr body lives in the header only:\n$s")
    }

    @Test
    fun aHoistedPrototypeFollowsTheStructItsDefaultArgumentConstructsOrReturns() {
        // g++ and clang reject `std::int32_t limit(const Pt& p = ORIGIN);` before `ORIGIN` (and
        // `struct Pt`) is defined: a default argument is checked where it is declared, so the
        // constant it names, of a struct type, must be complete. (D48 makes a default a literal
        // or a pub constant, so `Pt {}` or a call cannot sit there; a field default can hold either.)
        val h = header(
            """
            pub struct S { pub n: Int32 = limit() }
            pub fx limit: (p: Pt = ORIGIN) Int32 { return 1 }
            pub ORIGIN: Pt = Pt {}
            pub struct Pt { pub x: Int32 = 0 }
            """
        )
        assertContains(h, "  struct Pt\n  {\n      std::int32_t x = 0;\n  };\n\n  inline constexpr Pt ORIGIN = Pt{};\n  [[nodiscard]] std::int32_t limit(const Pt& p = ORIGIN);\n\n  struct S\n  {\n      std::int32_t n = <FunctionCallExpr>;\n  };")
        val const = header(
            """
            pub X: Int32 = pick()
            pub @_const fx pick: (p: Pt = ORIGIN) Int32 { return 1 }
            pub ORIGIN: Pt = Pt {}
            pub struct Pt { pub x: Int32 = 0 }
            """
        )
        assertContains(const, "  struct Pt\n  {\n      std::int32_t x = 0;\n  };\n\n  inline constexpr Pt ORIGIN = Pt{};\n  [[nodiscard]] constexpr std::int32_t pick(const Pt& p = ORIGIN);\n\n  constexpr std::int32_t pick([[maybe_unused]] const Pt& p)\n  {\n      return {}; // body of pick\n  }\n\n  inline constexpr std::int32_t X = <FunctionCallExpr>;")
        val call = header(
            """
            pub struct S { pub p: Pt = origin() }
            pub fx origin: () Pt { return Pt {} }
            pub struct Pt { pub x: Int32 = 0 }
            """
        )
        // origin's prototype may return the still-incomplete Pt; the field default that calls it waits for Pt
        assertContains(call, "  [[nodiscard]] Pt origin();\n\n  struct Pt\n  {\n      std::int32_t x = 0;\n  };\n\n  struct S\n  {\n      Pt p = <FunctionCallExpr>;\n  };")
        // (A prototype hoisted with a parameter of a later struct's type and no default cannot be
        // written any more: `limit()` with `p: Pt` undefaulted is types.call.missing-arg, and every
        // legal way of naming limit in a default, D48, completes Pt first. origin() above is the
        // forward-declaration-suffices case that remains.)
    }

    @Test
    fun aCyclicModuleEmittedAfterAModuleThatNamesItStillReportsItsCycle() {
        // The backend emits modules in URI order, so app:a comes first and spells ::z::A, which
        // builds lib:z's placement before lib:z's own emission; the cycle it finds must reach
        // lib:z's diagnostics all the same, or `kira --target cpp` writes a header C++ rejects
        // and exits 0.
        val z = DeclTestSupport.module("lib:z", "pub struct A { pub bs: Deque<B> = Deque<B> {} }\npub struct B { pub items: Deque<A> = Deque<A> {} }")
        val a = DeclTestSupport.module("app:a", "use \"lib:z\"\n\npub struct H { pub a: A = A {} }")
        val emitted = DeclTestSupport.emit(a, z, parts = fakeBodies)
        assertTrue(!emitted.module("app:a").hasErrors, emitted.render(emitted.diagnostics("app:a")))
        val cycle = emitted.diagnostics("lib:z").filter { it.code == CppPlacement.DECL_CYCLE_CODE }
        assertEquals(2, cycle.size, emitted.render(emitted.diagnostics("lib:z")))
        assertTrue(emitted.module("lib:z").hasErrors)
        // the same for a by-value cycle
        val zv = DeclTestSupport.module("lib:z", "pub struct A { pub b: Maybe<B> = null }\npub struct B { pub a: Maybe<A> = null }")
        val byValue = DeclTestSupport.emit(a, zv, parts = fakeBodies)
        assertEquals(2, byValue.diagnostics("lib:z").count { it.code == CppPlacement.STRUCT_CYCLE_CODE }, byValue.render(byValue.diagnostics("lib:z")))
        // and the errors are lib:z's, at lib:z's declarations, reported once
        assertTrue(byValue.diagnostics("lib:z").all { it.file?.replace('\\', '/')?.endsWith("src/lib/z.kira") == true }, byValue.render(byValue.diagnostics("lib:z")))
        assertEquals(0, byValue.diagnostics("app:a").count { it.code == CppPlacement.STRUCT_CYCLE_CODE })
    }

    @Test
    fun anInt32MinimumIsSpelledThroughNumericLimits() {
        // MSVC /W4 /WX: `kira::Maybe<std::int32_t> A = -2147483648;` is C4244 inside <optional>,
        // since 2147483648 is already `long long` and std::optional's converting constructor
        // narrows it; R2 spells INT_MIN-style literals through std::numeric_limits.
        val h = header(
            """
            pub A: Maybe<Int32> = -2147483648
            pub B: Int32 = -2147483648
            pub C: Maybe<Int32> = -2147483647
            pub struct W { pub a: Maybe<Int32> = -2147483648 }
            pub fx g: (x: Maybe<Int32> = -2147483648) Void;
            """
        )
        assertContains(
            h,
            "inline constexpr kira::Maybe<std::int32_t> A = std::numeric_limits<std::int32_t>::min();",
            "inline constexpr std::int32_t B = std::numeric_limits<std::int32_t>::min();",
            "inline constexpr kira::Maybe<std::int32_t> C = -2147483647;",
            "      kira::Maybe<std::int32_t> a = std::numeric_limits<std::int32_t>::min();",
            "void g(const kira::Maybe<std::int32_t>& x = std::numeric_limits<std::int32_t>::min());",
        )
    }

    @Test
    fun aFloatEnumNumbersItsEntriesAndValueOfGivesTheValueWhereUsed() {
        val src = """pub enum Ratio: Float32 { HALF = 0.5, FULL = 1.0 }
            pub enum Wide: Float64 { W_A = 2.5 }"""
        val h = header(src)
        assertContains(h, "  enum class Ratio : std::int32_t\n  {\n      HALF = 0,\n      FULL = 1,\n  };", "  enum class Wide : std::int32_t\n  {\n      W_A = 0,\n  };")
        assertTrue(!h.contains("valueOf"), "valueOf is emitted only where used:\n$h")
        val used = header(src, usageOf = { ctx -> CppUsage.of(valueOf = setOf(ctx.symbol.members["Ratio"] as EnumSymbol, ctx.symbol.members["Wide"] as EnumSymbol)) })
        assertContains(
            used,
            "  [[nodiscard]] constexpr float valueOf(Ratio v)\n  {\n      switch(v)\n      {\n          case Ratio::HALF:\n              return 0.5f;\n          case Ratio::FULL:\n              return 1.0f;\n      }\n      return 0.0f;\n  }",
            "  [[nodiscard]] constexpr double valueOf(Wide v)\n  {\n      switch(v)\n      {\n          case Wide::W_A:\n              return 2.5;\n      }\n      return 0.0;\n  }",
        )
    }

    @Test
    fun comparingAGenericStructInstanceMarksItsArgument() {
        val (_, ctx) = emit(
            """
            pub struct Pt { pub x: Int32 = 0 }
            pub struct Pair<T> { pub a: T pub b: T }
            pub fx same: (a: Pair<Pt>, b: Pair<Pt>) Bool { return a == b }
            """
        )
        val pt = ctx.symbol.members["Pt"] as ClassSymbol
        val pair = ctx.symbol.members["Pair"] as ClassSymbol
        net.exoad.kira.compiler.analysis.types.AstTree.walk(ctx.module.ast) { node ->
            if (node is BinaryExpr) {
                ctx.model.types[node.leftExpr] = KType.Nominal(pair, listOf(TypeArg.Ty(KType.Nominal(pt))))
                ctx.model.types[node.rightExpr] = KType.Nominal(pair, listOf(TypeArg.Ty(KType.Nominal(pt))))
            }
        }
        val usage = CppUsage.scan(ctx.program)
        assertTrue(usage.needsEquality(pair) && usage.needsEquality(pt), "Pair<Pt> == Pair<Pt> compares Pair and Pt")
    }
}
