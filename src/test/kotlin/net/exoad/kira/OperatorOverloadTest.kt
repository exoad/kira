package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.core.IntrinsicRegistry
import net.exoad.kira.core.OperatorIntrinsics
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Operator overloading through the `@op_*` intrinsic family.
 *
 * Rules pinned here:
 *  - Operator intrinsics are real, registry-known names (`@op_add`, ...) that
 *    start with `op_` and use underscores between words -- never a bare
 *    `@add`.
 *  - Only operators with a table entry are overloadable; syntax (`is`, `as`,
 *    `.`, `..`) has none.
 *  - A non-primitive operand desugars the operator into a call to the op_*
 *    function in both backends; primitives keep the native operator.
 */
class OperatorOverloadTest {
    private val moduleUri = "test:ops.sample"

    private fun wrap(body: String): String =
        TestCompileSupport.wrapModule(moduleUri, body)

    private val overloadModule = """
        pub class Point {
            require pub x: Int32
            require pub y: Int32
        }

        pub fx @op_add: (a: Point, b: Point) Point {
            return Point { a.x + b.x, a.y + b.y }
        }

        pub fx @op_neg: (a: Point) Point {
            return Point { -a.x, -a.y }
        }

        pub fx @op_eq: (a: Point, b: Point) Bool {
            return a.x == b.x && a.y == b.y
        }

        fx main: () Void {
            p1: Point = Point { 1, 2 }
            p2: Point = Point { 3, 4 }
            p3: Point = p1 + p2
            n: Point = -p1
            p1 += p2
            trace(p3.x)
            trace(p3.y)
            trace(n.x)
            trace(p1.x)
        }
    """

    @Test
    fun registryRecognizesOperatorIntrinsics() {
        assertNotNull(IntrinsicRegistry.find("op_add"))
        assertNotNull(IntrinsicRegistry.find("op_sub"))
        assertNotNull(IntrinsicRegistry.find("op_mul"))
        assertNotNull(IntrinsicRegistry.find("op_neg"))
        assertNotNull(IntrinsicRegistry.find("op_bitnot"))
        assertNull(IntrinsicRegistry.find("op_bogus"))
    }

    @Test
    fun tableNamesAllStartWithOpAndUseUnderscores() {
        OperatorIntrinsics.all.forEach { intrinsic ->
            assertTrue(
                intrinsic.name.startsWith("op_"),
                "operator intrinsic must start with op_: ${intrinsic.name}"
            )
            assertTrue(
                intrinsic.name.count { it == '_' } >= 1,
                "operator intrinsic must separate words with underscores: ${intrinsic.name}"
            )
        }
    }

    @Test
    fun nonOperatorSyntaxIsNotOverloadable() {
        assertNull(OperatorIntrinsics.binaryName(BinaryOp.CONJUNCTIVE_DOT))
        assertNull(OperatorIntrinsics.binaryName(BinaryOp.RANGE))
        assertNull(OperatorIntrinsics.binaryName(BinaryOp.TYPE_CHECK))
        assertNull(OperatorIntrinsics.binaryName(BinaryOp.TYPE_CAST))
    }

    @Test
    fun operatorToNameMappingIsCanonical() {
        assertEquals("op_add", OperatorIntrinsics.binaryName(BinaryOp.ADD))
        assertEquals("op_sub", OperatorIntrinsics.binaryName(BinaryOp.SUB))
        assertEquals("op_eq", OperatorIntrinsics.binaryName(BinaryOp.EQUALS))
        assertEquals("op_ge", OperatorIntrinsics.binaryName(BinaryOp.GREATER_THAN_OR_EQUAL))
        assertEquals("op_ushr", OperatorIntrinsics.binaryName(BinaryOp.USHR))
        assertEquals("op_bitor", OperatorIntrinsics.binaryName(BinaryOp.CONJUNCTIVE_OR))
        assertEquals("op_neg", OperatorIntrinsics.unaryName(UnaryOp.NEG))
        assertEquals("op_not", OperatorIntrinsics.unaryName(UnaryOp.NOT))
        assertEquals("op_bitnot", OperatorIntrinsics.unaryName(UnaryOp.BIT_NOT))
    }

    @Test
    fun overloadedOperatorsDesugarInC() {
        val generated = TestCompileSupport.transpileSnippetToC(
            source = wrap(overloadModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        // User overload declarations lower to plain functions named op_*.
        assertTrue(generated.contains("op_add("), generated)
        assertTrue(generated.contains("op_neg("), generated)
        // Non-primitive operator expressions call the overload.
        assertTrue(generated.contains("op_add(p1, p2)"), generated)
        assertTrue(generated.contains("op_neg(p1)"), generated)
        // Primitives keep the native C operator inside the overload body
        // (params are pointers, so field reads lower to a->x).
        assertTrue(generated.contains("a->x + b->x"), generated)
    }

    @Test
    fun compoundAssignmentDesugarsThroughOverloadInC() {
        val generated = TestCompileSupport.transpileSnippetToC(
            source = wrap(overloadModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        // p1 += p2 -> p1 = op_add(p1, p2); Point is an ARC class so the store
        // routes through the owned store helper.
        assertTrue(generated.contains("kira_rc_store_owned"), generated)
        assertTrue(generated.contains("op_add(p1, p2)"), generated)
    }

    @Test
    fun overloadedOperatorsDesugarInJS() {
        val generated = TestCompileSupport.transpileSnippetToJS(
            source = wrap(overloadModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        assertTrue(generated.contains("function op_add("), generated)
        assertTrue(generated.contains("op_add(p1, p2)"), generated)
        assertTrue(generated.contains("op_neg(p1)"), generated)
        assertTrue(generated.contains("p1 = op_add(p1, p2)"), generated)
        // Primitives keep the native JS operator inside the overload body.
        assertTrue(generated.contains("a.x + b.x"), generated)
    }

    @Test
    fun overloadedOperatorsCompileAndRun() {
        val cCompiler = TestCompileSupport.findCCompiler()
        assumeTrue(cCompiler != null, "needs a C compiler")

        val generated = TestCompileSupport.transpileSnippetToC(
            source = wrap(overloadModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        val result = TestCompileSupport.compileAndRunC(generated, cCompiler!!)
        assertEquals(0, result.compileResult.exitCode, result.compileResult.stderr)
        assertNotNull(result.runResult)
        assertEquals("4\n6\n-1\n4\n", result.runResult!!.stdout)
    }
}
