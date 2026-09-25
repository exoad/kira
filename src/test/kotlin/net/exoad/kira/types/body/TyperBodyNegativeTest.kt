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
}
