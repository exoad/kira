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
    fun freeTableNamesAllStartWithOpAndUseUnderscores() {
        OperatorIntrinsics.all.forEach { intrinsic ->
            assertTrue(
                intrinsic.name.startsWith("op_"),
                "free operator intrinsic must start with op_: ${intrinsic.name}"
            )
            assertTrue(
                intrinsic.name.count { it == '_' } >= 1,
                "free operator intrinsic must separate words with underscores: ${intrinsic.name}"
            )
        }
    }

    @Test
    fun memberOperatorTableIsExactlyThe1_3_1List() {
        // w2-9-1-parse round 8, significant issue #4: `memberTableNamesAreUnderscoredOnBothEnds`
        // below, and the two `OperatorMemberParseTest` parse sweeps, all iterate
        // `OperatorIntrinsics.allMembers` -- the implementation's own generated list -- so a
        // mutant that renames one entry (V20: `_op_lte_` -> `_op_le_`) or maps one to null (V21:
        // `BinaryOp.USHR`; V22: `UnaryOp.POS`) survives every one of them: `allMembers` is built
        // FROM `memberName`, so it just contains whatever the corrupted table produces, and nothing
        // compares it against an independent source. This hardcodes 20-revision.md 1.3.1 / the
        // brief's BUILD step 1's 23 names verbatim and checks each maps back to its operator.
        val table: List<Pair<String, () -> String?>> = listOf(
            "_op_add_" to { OperatorIntrinsics.memberName(BinaryOp.ADD) },
            "_op_sub_" to { OperatorIntrinsics.memberName(BinaryOp.SUB) },
            "_op_mul_" to { OperatorIntrinsics.memberName(BinaryOp.MUL) },
            "_op_div_" to { OperatorIntrinsics.memberName(BinaryOp.DIV) },
            "_op_mod_" to { OperatorIntrinsics.memberName(BinaryOp.MOD) },
            "_op_eq_" to { OperatorIntrinsics.memberName(BinaryOp.EQUALS) },
            "_op_neq_" to { OperatorIntrinsics.memberName(BinaryOp.NOT_EQUAL) },
            "_op_lt_" to { OperatorIntrinsics.memberName(BinaryOp.LESS_THAN) },
            "_op_gt_" to { OperatorIntrinsics.memberName(BinaryOp.GREATER_THAN) },
            "_op_lte_" to { OperatorIntrinsics.memberName(BinaryOp.LESS_THAN_OR_EQUAL) },
            "_op_gte_" to { OperatorIntrinsics.memberName(BinaryOp.GREATER_THAN_OR_EQUAL) },
            "_op_neg_" to { OperatorIntrinsics.memberName(UnaryOp.NEG) },
            "_op_get_" to { OperatorIntrinsics.GET },
            "_op_set_" to { OperatorIntrinsics.SET },
            "_op_bitand_" to { OperatorIntrinsics.memberName(BinaryOp.CONJUNCTIVE_AND) },
            "_op_bitor_" to { OperatorIntrinsics.memberName(BinaryOp.CONJUNCTIVE_OR) },
            "_op_xor_" to { OperatorIntrinsics.memberName(BinaryOp.XOR) },
            "_op_shl_" to { OperatorIntrinsics.memberName(BinaryOp.SHL) },
            "_op_shr_" to { OperatorIntrinsics.memberName(BinaryOp.SHR) },
            "_op_ushr_" to { OperatorIntrinsics.memberName(BinaryOp.USHR) },
            "_op_bitnot_" to { OperatorIntrinsics.memberName(UnaryOp.BIT_NOT) },
            "_op_not_" to { OperatorIntrinsics.memberName(UnaryOp.NOT) },
            "_op_pos_" to { OperatorIntrinsics.memberName(UnaryOp.POS) },
        )
        assertEquals(23, table.size, "1.3.1's table has 23 names")
        for ((expected, actual) in table) {
            assertEquals(expected, actual(), "1.3.1's member name for $expected")
        }
        assertEquals(
            table.map { it.first }.toSet(),
            OperatorIntrinsics.allMembers.map { it.name }.toSet(),
            "OperatorIntrinsics.allMembers must be exactly these 23 names, nothing more or fewer",
        )
    }

    @Test
    fun memberTableNamesAreUnderscoredOnBothEnds() {
        OperatorIntrinsics.allMembers.forEach { intrinsic ->
            assertTrue(
                intrinsic.name.startsWith("_op_") && intrinsic.name.endsWith("_"),
                "member operator intrinsic must be _op_<word>_: ${intrinsic.name}"
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
    fun aModuleLevelMemberFormNameFailsTheSemanticPassOnCAndJS() {
        // w2-9-1-parse significant issue #1: `--target c` and `--target js` run
        // `KiraSemanticAnalyzer`, never `KiraTyper`/`DeclarationCollector`. A module-level
        // `@_op_add_` (the *member* spelling) must fail here too, or `Main.kt`'s "backend emit
        // only after a clean semantic pass" gate never trips and the C/JS emitters run anyway,
        // each calling a function named `op_add` that this declaration never defines.
        val badModule = """
            pub class V2 {
                pub x: Float32 = 0.0
            }
            pub fx @_op_add_: (a: V2, b: V2) V2 {
                return V2 { a.x + b.x }
            }
        """
        val result = TestCompileSupport.compileSnippet(
            source = wrap(badModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(!semantics.isHealthy, "a module-level @_op_add_ must fail the semantic pass, not silently pass")
        assertTrue(
            semantics.diagnostics.any { it.message.contains("_op_add_") },
            "expected a diagnostic naming '_op_add_', got: ${semantics.diagnostics.map { it.message }}"
        )
    }

    @Test
    fun aModuleLevelMemberFormStubFailsTheSemanticPassOnCAndJS() {
        // w2-9-1-parse round 2, significant issue #1: the test above uses a *body*, and
        // `KiraSemanticAnalyzer.visitFunctionDecl` used to return on `functionDecl.isStub()`
        // before ever reaching the ops.member-scope pump, so a body-less module-level
        // `@_op_add_` (a plain declaration, no `{ ... }`) passed this pass clean on both C and
        // JS. `kira --target c`/`--target js` exited 0 with 'Done' and no diagnostic; the C
        // output called `op_add(a, b)` (gcc: implicit declaration of function 'op_add') and the
        // JS threw `ReferenceError: op_add is not defined`. The stub must fail here exactly like
        // the version with a body.
        val badStubModule = """
            pub class V2 {
                pub x: Float32 = 0.0
            }
            pub fx @_op_add_: (a: V2, b: V2) V2;
        """
        val result = TestCompileSupport.compileSnippet(
            source = wrap(badStubModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(!semantics.isHealthy, "a body-less module-level @_op_add_ must fail the semantic pass too")
        assertTrue(
            semantics.diagnostics.any { it.message.contains("_op_add_") },
            "expected a diagnostic naming '_op_add_', got: ${semantics.diagnostics.map { it.message }}"
        )
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
