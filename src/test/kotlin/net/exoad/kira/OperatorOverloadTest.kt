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

    // --- the member form (w2-9-4-ops-cjs): `pub fx @_op_add_: (other: V) V` in the class -------------

    private val memberModule = """
        pub class V2 {
            require pub x: Int32
            require pub y: Int32

            pub fx @_op_add_: (other: V2) V2 {
                return V2 { x + other.x, y + other.y }
            }

            pub fx @_op_neg_: () V2 {
                return V2 { 0 - x, 0 - y }
            }

            pub fx @_op_eq_: (other: V2) Bool {
                return x == other.x && y == other.y
            }
        }

        fx main: () Void {
            mut p1: V2 = V2 { 1, 2 }
            p2: V2 = V2 { 3, 4 }
            p3: V2 = p1 + p2
            n: V2 = -p1
            p1 += p2
            e: V2 = p3.@_op_add_(p2)
            trace(p3.x)
            trace(p3.y)
            trace(n.x)
            trace(p1.x)
            trace(e.y)
            if p1 == p3 {
                trace("same")
            }
            if p1 != n {
                trace("apart")
            }
        }
    """

    private val memberExpected = "4\n6\n-1\n4\n10\nsame\napart\n"

    @Test
    fun memberOperatorsLowerAsMethodCallsInC() {
        val generated = TestCompileSupport.transpileSnippetToC(
            source = wrap(memberModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        // The member lowers to the class's method (`Class_method`), never to a free `op_add`.
        assertTrue(generated.contains("V2__op_add_(p1, p2)"), generated)
        assertTrue(generated.contains("V2__op_neg_(p1)"), generated)
        assertTrue(!generated.contains("op_add(p1"), "no free op_add call: $generated")
        // `p1 += p2` is `p1 = p1.op(p2)`, stored through the ARC helper.
        assertTrue(generated.contains("kira_rc_store_owned"), generated)
        // The explicit call is the same method.
        assertTrue(generated.contains("V2__op_add_(p3, p2)"), generated)
        // `==` is the declared member; `!=` without `@_op_neq_` is its negation.
        assertTrue(generated.contains("V2__op_eq_(p1, p3)"), generated)
        assertTrue(generated.contains("(!V2__op_eq_(p1, n))"), generated)
    }

    @Test
    fun memberOperatorsLowerAsMethodCallsInJS() {
        val generated = TestCompileSupport.transpileSnippetToJS(
            source = wrap(memberModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        assertTrue(generated.contains("p1._op_add_(p2)"), generated)
        assertTrue(generated.contains("p1._op_neg_()"), generated)
        assertTrue(generated.contains("p1 = p1._op_add_(p2)"), generated)
        assertTrue(generated.contains("p3._op_add_(p2)"), generated)
        assertTrue(generated.contains("p1._op_eq_(p3)"), generated)
        assertTrue(generated.contains("(!p1._op_eq_(n))"), generated)
        assertTrue(!generated.contains("op_add(p1"), "no free op_add call: $generated")
    }

    @Test
    fun memberOperatorsCompileAndRunInC() {
        val cCompiler = TestCompileSupport.findCCompiler()
        assumeTrue(cCompiler != null, "needs a C compiler")

        val generated = TestCompileSupport.transpileSnippetToC(
            source = wrap(memberModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        val result = TestCompileSupport.compileAndRunC(generated, cCompiler!!)
        assertEquals(0, result.compileResult.exitCode, result.compileResult.stderr)
        assertNotNull(result.runResult)
        assertEquals(memberExpected, result.runResult!!.stdout)
    }

    @Test
    fun memberOperatorsRunInJS() {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "needs node")

        val generated = TestCompileSupport.transpileSnippetToJS(
            source = wrap(memberModule),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        val result = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(memberExpected, result.stdout)
    }

    @Test
    fun theMemberFormWinsAndTheFreeFormStillLowersBesideIt() {
        // A class with a member `+` and another with only the free form, in one program: each keeps
        // its own lowering.
        val both = """
            pub class A {
                require pub ax: Int32
                pub fx @_op_add_: (o: A) A { return A { ax + o.ax } }
            }
            pub class B {
                require pub bx: Int32
            }
            pub fx @op_add: (a: B, b: B) B {
                return B { a.bx + b.bx }
            }
            fx main: () Void {
                a1: A = A { 1 }
                b1: B = B { 2 }
                a2: A = a1 + a1
                b2: B = b1 + b1
                trace(a2.ax)
                trace(b2.bx)
            }
        """
        val c = TestCompileSupport.transpileSnippetToC(
            source = wrap(both),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        assertTrue(c.contains("A__op_add_(a1, a1)"), c)
        assertTrue(c.contains("op_add(b1, b1)"), c)
        val js = TestCompileSupport.transpileSnippetToJS(
            source = wrap(both),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        assertTrue(js.contains("a1._op_add_(a1)"), js)
        assertTrue(js.contains("op_add(b1, b1)"), js)
    }

    @Test
    fun anImmutableClassWithoutAnEqSynthesizesOneAndAMutableOneComparesIdentity() {
        val module = """
            pub class P {
                require pub px: Int32
            }
            pub class M {
                require pub mut mx: Int32
            }
            fx main: () Void {
                p1: P = P { 1 }
                p2: P = P { 1 }
                m1: M = M { 1 }
                m2: M = M { 1 }
                if p1 == p2 {
                    trace("p")
                }
                if m1 == m2 {
                    trace("m")
                }
            }
        """
        val c = TestCompileSupport.transpileSnippetToC(
            source = wrap(module),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        // The immutable class gets a generated `P__op_eq_` over its fields; the mutable one is the pointer compare.
        assertTrue(c.contains("Bool P__op_eq_(P* this, P* other)"), c)
        assertTrue(c.contains("P__op_eq_(p1, p2)"), c)
        assertTrue(c.contains("(m1 == m2)"), c)
        assertTrue(!c.contains("M__op_eq_"), c)
        val js = TestCompileSupport.transpileSnippetToJS(
            source = wrap(module),
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )
        assertTrue(js.contains("p1._op_eq_(p2)"), js)
        assertTrue(js.contains("(m1 === m2)"), js)
    }
}
