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
}
