package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refusals that the fixtures cannot state: an AST the parser never builds (an if-expression
 * without else), and the exact diagnostics of whole small programs.
 */
class TyperBodyNegativeTest {
    @Test
    fun anIfExpressionWithoutElseIsRefused() {
        // The parser refuses `x: Int32 = if c { 1 }` itself; a hand-built AST reaches the typer.
        val unit = TyperTestSupport.unitOf()
        val ifExpr = IfExpr(Identifier("c"), listOf(Statement(IntegerLiteral(1))), emptyList())
        TyperTestSupport.astModule(
            unit, "test:noelse",
            TyperTestSupport.fn(
                "pick", listOf(TyperTestSupport.param("c", TyperTestSupport.ty("Bool"))), TyperTestSupport.ty("Int32"),
                listOf(ReturnStatement(ifExpr)),
            ),
        )
        val p = KiraTyper.run(unit, TyperMode.STRICT)
        assertEquals(listOf("types.if.no-else"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertEquals(false, p.model.ifShape[ifExpr])
    }

    @Test
    fun aLiteralOutOfRangeForAConstantIsPhaseCsToReport() {
        // Phase B leaves it unfolded (TyperDeclDiagnosticsTest); phase C says why.
        val p = snippet("X: UInt8 = 256")
        assertEquals(listOf("types.literal.range"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertEquals("256 does not fit UInt8, which holds 0..255.", p.diagnostics.single().message)
    }

    @Test
    fun aMutableGlobalArrLiteralHasItsCountChecked() {
        // Phase B never folds a mut global's initializer, so the Arr<T, N> count is checked here.
        val p = snippet("pub mut BUF: Arr<UInt8, 2> = [1, 2, 3]")
        assertEquals(listOf("types.const.arr-size"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }

    @Test
    fun anArrLiteralInABodyHasItsCountChecked() {
        val p = snippet("pub fx f: () Void {\n    a: Arr<UInt8, 4> = [1, 2]\n}")
        assertEquals(listOf("types.const.arr-size"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }

    @Test
    fun aNonConstantMutableGlobalIsRefusedAndAConstantOneIsNot() {
        val bad = snippet("fx seed: () Int32 {\n    return 4\n}\npub mut state: Int32 = seed()")
        assertEquals(listOf("types.global.mut-init"), bad.diagnostics.map { it.code }, TyperTestSupport.render(bad))
        val good = snippet("pub LIMIT: Int32 = 4\npub mut state: Int32 = LIMIT * 2\npub mut xs: Arr<Int32> = [1, 2]")
        TyperTestSupport.expectNoErrors(good)
    }

    @Test
    fun aStrConstantMustFoldToALiteral() {
        val p = snippet("fx name: () Str {\n    return \"n\"\n}\npub A: Str = \"a\" + \"b\"\npub B: Str = name()")
        assertEquals(listOf("types.const.str-literal"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }

    @Test
    fun aNamedArgumentThatNamesNoParameterSaysWhichExist() {
        val p = snippet("fx f: (a: Int32, b: Int32) Int32 {\n    return a\n}\npub fx g: () Int32 {\n    return f(a = 1, z = 2)\n}")
        val d = TyperTestSupport.expectDiagnostic(p, "types.call.unknown-named")
        assertEquals("'f' has no parameter named 'z'. Its parameters are: a, b.", d.message)
        assertEquals(1, p.diagnostics.size, TyperTestSupport.render(p))
    }

    @Test
    fun anErrorTypedOperandDoesNotCascade() {
        val p = snippet("pub fx f: () Int32 {\n    x: Int32 = missing + 1\n    return x * 2\n}")
        assertEquals(listOf("types.name.unknown"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }

    @Test
    fun aRunTimeBindingIsNamedWhereverItSitsInTheExpression() {
        // The reason is the offending leaf, not only a top-level call: under an operator, in a
        // mut global's initializer, and in a static assert's condition.
        val p = snippet("pub SCALED: Float64 = sqrt(2.0) * 2.0\npub mut SEED: Float64 = 1.0 + sqrt(4.0)\n@_static_assert(sqrt(4.0) == 2.0, \"two\")")
        assertEquals(
            listOf("types.const.not-constant", "types.global.mut-init", "types.static-assert.not-constant"),
            p.diagnostics.map { it.code }.sorted(),
            TyperTestSupport.render(p),
        )
        for (d in p.diagnostics) {
            assertTrue(d.message.contains("('sqrt' runs at run time: its C++ binding is not constexpr)"), d.message)
        }
        assertEquals(
            "A static assert's condition must be known at compile time: constants, literals, operators and @_const calls; " +
                "sqrt(4.0) == 2.0 is not ('sqrt' runs at run time: its C++ binding is not constexpr).",
            p.diagnostics.single { it.code == "types.static-assert.not-constant" }.message,
        )
    }

    @Test
    fun aGlobalOfARunTimeTypeIsNoConstantOperand() {
        // W2.2 spells DYN `inline const kira::List<...>` (a std::vector is no literal type on the
        // gcc 11.4 floor) and DSIZE `inline constexpr`: g++ refuses the pair, so the typer does.
        val p = snippet("pub DYN: Arr<Int32> = [1, 2, 3]\npub DSIZE: Size = DYN.size()\n@_static_assert(DYN.size() == 3, \"three\")")
        assertEquals(listOf("types.const.not-constant", "types.static-assert.not-constant"), p.diagnostics.map { it.code }.sorted(), TyperTestSupport.render(p))
        assertEquals(
            "'DSIZE' is a module constant, so its value is fixed at compile time; DYN.size() is not " +
                "('DYN' is built at run time: Arr<Int32> is no literal type in C++). Declare it `mut` for state, or compute it where it is used.",
            p.diagnostics.single { it.code == "types.const.not-constant" }.message,
        )
        // A construction of such a type is no constant operand either, however constant its fields.
        val q = snippet("pub struct Bag {\n    pub items: List<Int32> = []\n    pub n: Int32 = 1\n}\npub BAG: Bag = Bag { }\npub TMP: Int32 = Bag { }.n")
        assertEquals(listOf("types.const.not-constant"), q.diagnostics.map { it.code }, TyperTestSupport.render(q))
        assertTrue(q.diagnostics.single().message.contains("(Bag { } is built at run time: Bag is no literal type in C++)"), q.diagnostics.single().message)
    }

    @Test
    fun aGlobalOfARunTimeTypeStillStartsFromLiteralsInNoOrder() {
        // D49 asks only that no initialization order can matter: literals, nested literals, a
        // StrBuf, a tuple of literal fields and their reads are fine.
        val p = snippet(
            "pub LIMIT: Int32 = 4\npub mut state: Int32 = LIMIT * 2\npub mut xs: Arr<Int32> = [1, 2]\npub mut ys: List<List<Int32>> = [[1], [2]]\n" +
                "pub mut buf: StrBuf<16> = StrBuf<16> { }\npub mut m: Maybe<Int32> = null\n" +
                "pub P: Tuple2<Int32, Bool> = Tuple2<Int32, Bool> { 1, true }\npub PF: Int32 = P.first",
        )
        TyperTestSupport.expectNoErrors(p)
    }

    @Test
    fun aCyclicBoundReachesNoMemberAndDoesNotOverflow() {
        // Phase B lets `<T: T>` and `<T: U, U: T>` through; the member walk ends at the cycle
        // instead of recursing to a StackOverflowError reported as types.expr.too-deep.
        val p = snippet(
            "pub trait Shape {\n    pub fx area: () Float64;\n}\npub fx cyclic<T: T>: (s: T) Float64 {\n    return s.area()\n}\n" +
                "pub fx mutual<T: U, U: T>: (s: T, u: U) Float64 {\n    return s.area() + u.area()\n}",
        )
        assertEquals(List(3) { "types.member.unknown" }, p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertEquals(
            "T is a type parameter whose bound T is a type parameter leading back to it, so no member is reachable on it; " +
                "bound it with the trait that has 'area'.",
            p.diagnostics.first().message,
        )
    }

    @Test
    fun aMutFxOnAClassBoundedParameterInALambdaWritesAReference() {
        // Only Named and its subclasses satisfy `T: Named`, and every one is an Rc: the lambda's
        // capture of n shares the object, as `n: Named` does. Through a chain of bounds too.
        val p = snippet(
            "pub class Named {\n    require pub name: Str\n    pub mut hits: Int32 = 0\n    pub mut fx hit: () Void {\n        hits += 1\n    }\n}\n" +
                "pub fx lam<T: Named>: (n: T) Fx<Tuple0, Void> {\n    return fx() Void {\n        n.hit()\n    }\n}\n" +
                "pub fx viaU<T: U, U: Named>: (n: T) Fx<Tuple0, Void> {\n    return fx() Void {\n        n.hit()\n    }\n}",
        )
        TyperTestSupport.expectNoErrors(p)
        // A trait bound may be satisfied by a struct, which a lambda copies: still refused.
        val q = snippet(
            "pub trait Counter {\n    pub mut fx bump: () Void;\n}\n" +
                "pub fx lam<T: Counter>: (n: T) Fx<Tuple0, Void> {\n    return fx() Void {\n        n.bump()\n    }\n}",
        )
        assertEquals(listOf("types.lambda.assign-capture"), q.diagnostics.map { it.code }, TyperTestSupport.render(q))
    }

    @Test
    fun aConstructionRunsTheDefaultsItLeavesOut() {
        // `W{}` in C++ runs the default member initializer `n = seed()`: g++ refuses
        // `inline constexpr W K = W{};` with 'call to non-constexpr function seed()'.
        val p = snippet(
            "fx seed: () Int32 {\n    return 4\n}\npub struct W {\n    pub n: Int32 = seed()\n}\npub K: W = W { }\npub KN: Int32 = K.n\npub G: W = W { 5 }",
        )
        assertEquals(listOf("types.const.not-constant"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertEquals(
            "'K' is a module constant, so its value is fixed at compile time; W { } is not " +
                "(the default of field 'n', seed(), runs at run time). Declare it `mut` for state, or compute it where it is used.",
            p.diagnostics.single().message,
        )
    }

    @Test
    fun aMutGlobalWhoseDefaultedFieldReadsAnotherModulesRunTimeGlobalIsRefused() {
        // The D49 hazard itself: two TUs, and `inline W2 G = W2{};` in one reads DYN in the
        // other during static initialization, in whichever order the C++ runtime picks.
        val p = TyperTestSupport.type(
            TyperTestSupport.module("test:lib", "pub DYN: Arr<Int32> = [1, 2, 3]"),
            TyperTestSupport.module(
                "test:main",
                "use \"test:lib\"\n\npub struct W2 {\n    pub n: Size = lib.DYN.size()\n}\npub mut G: W2 = W2 { }\npub mut H: Size = lib.DYN.size()\n" +
                    "pub struct Bag {\n    pub items: List<Int32> = []\n    pub n: Size = lib.DYN.size()\n}\npub mut GB: Bag = Bag { }\npub mut OK: Bag = Bag { n = 3 }",
            ),
        )
        assertEquals(List(3) { "types.global.mut-init" }, p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        val g = p.diagnostics.first()
        assertEquals(
            "A module-level mut variable starts from a constant expression (D49), so no static-initialization order " +
                "can matter; 'G' starts from W2 { } (the default of field 'n' is lib.DYN.size() " +
                "('DYN' is built at run time: Arr<Int32> is no literal type in C++)).",
            g.message,
        )
        assertTrue(p.diagnostics[2].message.contains("'GB' starts from Bag { } (the default of field 'n' is lib.DYN.size()"), p.diagnostics[2].message)
    }

    @Test
    fun aDefaultIsTypedBeforeTheGlobalThatRunsIt() {
        // Declarations come in source order; the global here precedes the struct whose default
        // it runs, and the struct comes from a later module still. A constant default keeps
        // the construction constant either way.
        val p = TyperTestSupport.type(
            TyperTestSupport.module("test:main", "use \"test:lib\"\n\npub K: W = W { }\npub KN: Int32 = K.n\n@_static_assert(K.n == 7, \"seven\")"),
            TyperTestSupport.module("test:lib", "pub SEVEN: Int32 = 7\npub struct W {\n    pub n: Int32 = SEVEN\n}"),
        )
        TyperTestSupport.expectNoErrors(p)
    }

    @Test
    fun aConstCallPassesTheDefaultItLeavesOut() {
        val p = snippet(
            "pub DYN: Arr<Int32> = [1, 2, 3]\npub @_const fx firstOf: (xs: Arr<Int32> = DYN) Int32 {\n    return xs.get(0)\n}\n" +
                "pub @_const fx addTo: (a: Int32, b: Int32 = 2) Int32 {\n    return a + b\n}\npub FD: Int32 = firstOf()\npub AK: Int32 = addTo(1)",
        )
        // ConstEligibilityPass (W2.5) also refuses the Arr<Int32> parameter of an @_const function: a std::vector is no literal type.
        assertEquals(listOf("types.const.not-constant", "rules.const.type"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertTrue(
            p.diagnostics.first().message.contains("firstOf() is not (the default of 'xs' is DYN ('DYN' is built at run time: Arr<Int32> is no literal type in C++))"),
            p.diagnostics.first().message,
        )
    }

    @Test
    fun anOperatorOverloadIsACallAndConstantOnlyWhenConst() {
        // g++: 'call to non-constexpr function V2 operator+(V2, V2)'; a @_const one is constexpr.
        val p = snippet(
            "pub struct V2 {\n    pub x: Int32 = 0\n}\npub fx @op_add: (a: V2, b: V2) V2 {\n    return V2 { a.x + b.x }\n}\n" +
                "pub fx @op_eq: (a: V2, b: V2) Bool {\n    return a.x == b.x\n}\npub @_const fx @op_sub: (a: V2, b: V2) V2 {\n    return V2 { a.x - b.x }\n}\n" +
                "pub A: V2 = V2 { 1 }\npub B: V2 = A + A\npub BX: Int32 = (A + A).x\npub EQ: Bool = A == A\n@_static_assert(A == A, \"eq\")\n" +
                "pub D: V2 = A - A\npub DX: Int32 = (A - A).x\n@_static_assert((A - A).x == 0, \"sub\")",
        )
        assertEquals(
            listOf("types.const.not-constant", "types.const.not-constant", "types.const.not-constant", "types.static-assert.not-constant"),
            p.diagnostics.map { it.code },
            TyperTestSupport.render(p),
        )
        assertEquals(
            "'B' is a module constant, so its value is fixed at compile time; A + A is not " +
                "('@op_add' runs at run time: it is not @_const). Declare it `mut` for state, or compute it where it is used.",
            p.diagnostics.first().message,
        )
        assertTrue(p.diagnostics.last().message.endsWith("A == A is not ('@op_eq' runs at run time: it is not @_const)."), p.diagnostics.last().message)
    }

    @Test
    fun indexingAStrIsNoConstant() {
        // R15 lowers s[i] to kira::str::at(s, i), which is not constexpr (rt.hxx).
        val p = snippet("pub SEP: Str = \",\"\npub SC: Char = SEP[0]\npub mut MC: Char = SEP[0]\n@_static_assert(SEP[0] == ',', \"sep\")")
        assertEquals(
            listOf("types.const.not-constant", "types.global.mut-init", "types.static-assert.not-constant"),
            p.diagnostics.map { it.code },
            TyperTestSupport.render(p),
        )
        p.diagnostics.forEach { d ->
            assertTrue(d.message.contains("(indexing a Str runs at run time: kira::str::at is not constexpr)"), d.message)
        }
    }

    @Test
    fun aSelfContainingStructIsNoLiteralTypeAndDoesNotOverflow() {
        // Through a Maybe, through two structs, and through a fixed array: each is refused as
        // a run-time value, not reported as an internal StackOverflowError.
        val p = snippet(
            "pub struct Node {\n    pub v: Int32 = 0\n    pub next: Maybe<Node> = null\n}\npub N0: Node = Node { }\npub mut HEAD: Node = Node { }\npub NV: Int32 = N0.v\n" +
                "pub struct NA {\n    pub b: Maybe<NB> = null\n}\npub struct NB {\n    pub a: Maybe<NA> = null\n}\npub NA0: NA = NA { }\npub NAB: Maybe<NB> = NA0.b\n" +
                "pub struct Tree {\n    pub kids: Arr<Tree, 2>\n}\npub T0: Maybe<Tree> = null\npub T1: Maybe<Tree> = T0",
        )
        assertEquals(List(3) { "types.const.not-constant" }, p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertTrue(p.diagnostics[0].message.contains("('N0' is built at run time: Node is no literal type in C++)"), p.diagnostics[0].message)
        assertTrue(p.diagnostics[2].message.contains("('T0' is built at run time: Maybe<Tree> is no literal type in C++)"), p.diagnostics[2].message)
    }

    @Test
    fun aGenericStructInstanceIsLiteralByItsArguments() {
        val p = snippet(
            "pub struct Box<T> {\n    pub v: T\n}\npub BX: Box<Int32> = Box<Int32> { 1 }\npub BV: Int32 = BX.v\n" +
                "pub BB: Box<Box<Int32>> = Box<Box<Int32>> { BX }\npub BBV: Int32 = BB.v.v\n@_static_assert(BB.v.v == 1, \"one\")\n" +
                "pub BL: Box<List<Int32>> = Box<List<Int32>> { [] }\npub BLN: Size = BL.v.size()",
        )
        assertEquals(listOf("types.const.not-constant"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        // The first leaf named is List.size's binding (not constexpr on a std::vector); BL itself is a run-time value too.
        assertTrue(p.diagnostics.single().message.contains("BL.v.size() is not ('size' runs at run time: its C++ binding is not constexpr)"), p.diagnostics.single().message)
        val q = snippet("pub struct Box<T> {\n    pub v: T\n}\npub BL: Box<List<Int32>> = Box<List<Int32>> { [] }\npub BLV: List<Int32> = BL.v")
        assertEquals(listOf("types.const.not-constant"), q.diagnostics.map { it.code }, TyperTestSupport.render(q))
        assertTrue(q.diagnostics.single().message.contains("BL.v is not ('BL' is built at run time: Box<List<Int32>> is no literal type in C++)"), q.diagnostics.single().message)
    }

    @Test
    fun anEmptyContainerConstructionStartsAMutGlobalLikeAnArrayLiteral() {
        val p = snippet(
            "pub mut XS: List<Int32> = List<Int32> { }\npub mut YS: List<Int32> = []\npub mut M: Map<Str, Int32> = Map<Str, Int32> { }\n" +
                "pub mut S: Set<Int32> = Set<Int32> { }\npub class C {\n    pub mut n: Int32 = 0\n}\npub mut OBJ: C = C { }",
        )
        assertEquals(listOf("types.global.mut-init"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
        assertTrue(p.diagnostics.single().message.contains("'OBJ' starts from C { }."), p.diagnostics.single().message)
    }
}
