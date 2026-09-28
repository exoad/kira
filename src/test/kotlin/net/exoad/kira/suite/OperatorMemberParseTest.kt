package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.core.OperatorIntrinsics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `w2-9-1-parse`: the member operator form (1.3.1, 1.3.2) and `final` (1.8), parsed. Phase A/B
 * collection is [net.exoad.kira.types.OperatorMemberCollectTest]; the typer's own resolution of
 * an operator call is `w2-9-7-ops-typer`'s.
 */
class OperatorMemberParseTest {

    private fun parse(body: String): RootASTNode {
        val source = "module \"test:opparse\"\n" + body.trimIndent()
        val result = TestCompileSupport.compileSnippet(source, "tests/opparse.kira", runSemantic = false)
        return result.sourceContext.ast as RootASTNode
    }

    private fun decls(ast: RootASTNode): List<Any> =
        ast.statements.map { (it as Statement).expr }.filter { it !is ModuleDecl }

    private inline fun <reified T> classLike(ast: RootASTNode): T = decls(ast).filterIsInstance<T>().single()

    @Test
    fun memberOperatorParsesInAClassWithMutAndOverride() {
        val ast = parse(
            """
            pub class V2 {
                pub x: Float32 = 0.0
                pub fx @_op_add_: (other: V2) V2 { return V2 { x + other.x } }
            }
            pub class Box {
                pub v: Int32 = 0
                mut pub fx @_op_set_: (i: Int32, value: Int32) Void { v = value }
            }
            """
        )
        val v2 = decls(ast).filterIsInstance<ClassDecl>()[0]
        val add = v2.members.filterIsInstance<FunctionDecl>().single { it.isIntrinsicOverload() }
        val addName = assertIs<IntrinsicExpr>(add.name)
        assertEquals("_op_add_", addName.intrinsicKey.name)

        val box = decls(ast).filterIsInstance<ClassDecl>()[1]
        val set = box.members.filterIsInstance<FunctionDecl>().single { it.isIntrinsicOverload() }
        val setName = assertIs<IntrinsicExpr>(set.name)
        assertEquals("_op_set_", setName.intrinsicKey.name)
        assertTrue(Modifier.MUTABLE in set.modifiers, "@_op_set_ must parse as mut fx: ${set.modifiers}")
    }

    @Test
    fun overrideMemberOperatorParses() {
        val ast = parse(
            """
            pub class Base {
                pub fx @_op_eq_: (other: Base) Bool { return true }
            }
            pub class Leaf: Base {
                override pub fx @_op_eq_: (other: Base) Bool { return false }
            }
            """
        )
        val leaf = decls(ast).filterIsInstance<ClassDecl>()[1]
        val eq = leaf.members.filterIsInstance<FunctionDecl>().single { it.isIntrinsicOverload() }
        assertTrue(Modifier.OVERRIDE in eq.modifiers)
        assertEquals("_op_eq_", assertIs<IntrinsicExpr>(eq.name).intrinsicKey.name)
    }

    @Test
    fun memberOperatorParsesInATrait() {
        val ast = parse(
            """
            pub trait Ordered<T> {
                pub fx @_op_lt_: (other: T) Bool;
            }
            """
        )
        val trait = classLike<TraitDecl>(ast)
        val lt = trait.members.single()
        assertEquals("_op_lt_", assertIs<IntrinsicExpr>(lt.name).intrinsicKey.name)
    }

    @Test
    fun dotMemberOperatorParsesAsACall() {
        val ast = parse(
            """
            fx apply: (a: Int32, i: Int32) Void {
                a.@_op_get_(i)
                a.@_op_add_(i)
            }
            """
        )
        val fn = classLike<FunctionDecl>(ast)
        val stmts = fn.def.body!!
        val get = assertIs<FunctionCallExpr>((stmts[0] as Statement).expr)
        val getTarget = assertIs<MemberAccessExpr>(get.name)
        assertEquals("_op_get_", assertIs<IntrinsicExpr>(getTarget.member).intrinsicKey.name)
        assertEquals("a", assertIs<Identifier>(getTarget.origin).value)

        val add = assertIs<FunctionCallExpr>((stmts[1] as Statement).expr)
        val addTarget = assertIs<MemberAccessExpr>(add.name)
        assertEquals("_op_add_", assertIs<IntrinsicExpr>(addTarget.member).intrinsicKey.name)
    }

    /**
     * `a.@op_add(b)` (the pre-W2.9 free name, called with member syntax): [M5]'s second failure
     * -- it panicked in `parsePostfix` because `parseIdentifier` cannot consume an
     * `INTRINSIC_IDENTIFIER`. `OperatorIntrinsics.isOperatorName` covers the free table too, so
     * this now parses; whether it means anything is the typer's question (`w2-9-7-ops-typer`).
     */
    @Test
    fun dotFreeFormOperatorAlsoParsesAsACall() {
        val ast = parse(
            """
            fx apply: (a: Int32, b: Int32) Void {
                a.@op_add(b)
            }
            """
        )
        val fn = classLike<FunctionDecl>(ast)
        val call = assertIs<FunctionCallExpr>((fn.def.body!![0] as Statement).expr)
        val target = assertIs<MemberAccessExpr>(call.name)
        assertEquals("op_add", assertIs<IntrinsicExpr>(target.member).intrinsicKey.name)
    }

    @Test
    fun dotOfAnUnknownIntrinsicStaysAnOrdinaryParseError() {
        // Not an operator name: falls through to `parseIdentifier`, which cannot consume an
        // INTRINSIC_IDENTIFIER token either, so this is still a parse error (unchanged by this
        // package): only operator intrinsics get the new member treatment.
        assertThrows<DiagnosticsException> {
            parse(
                """
                fx apply: (a: Int32) Void {
                    a.@_trace_(a)
                }
                """
            )
        }
    }

    @Test
    fun finalClassModifierParses() {
        val ast = parse("pub final class Reply { pub x: Int32 = 0 }")
        val reply = classLike<ClassDecl>(ast)
        assertTrue(Modifier.FINAL in reply.modifiers)
    }

    @Test
    fun finalOnATraitIsParseFinal() {
        val e = assertThrows<DiagnosticsException> { parse("final trait Shape { }") }
        assertEquals("parse.final", e.tag)
    }

    @Test
    fun finalOnAFunctionIsParseFinal() {
        val e = assertThrows<DiagnosticsException> { parse("final fx f: () Void { }") }
        assertEquals("parse.final", e.tag)
    }

    @Test
    fun finalOnAFieldIsParseFinal() {
        val e = assertThrows<DiagnosticsException> { parse("final x: Int32 = 0") }
        assertEquals("parse.final", e.tag)
    }

    // 1.3.1's whole table: every member name declares in a class and in a trait.
    //
    // w2-9-1-parse round 8, significant issue #4: the two tests below used to iterate
    // `OperatorIntrinsics.allMembers.map { it.name }` -- the implementation's own generated
    // list -- so a mutant that renames or drops one entry of `memberName` (V20: `_op_lte_` ->
    // `_op_le_`; V21: `BinaryOp.USHR` -> `null`; V22: `UnaryOp.POS` -> `null`) just changes what
    // "every member name" means, and a test that only asks "does each of *whatever this list
    // is* parse" never notices. This is 1.3.1's table (20-revision.md / the brief's BUILD step
    // 1) copied verbatim: 23 names, independent of `OperatorIntrinsics`.
    private val theOperatorTable1_3_1 = listOf(
        "_op_add_", "_op_sub_", "_op_mul_", "_op_div_", "_op_mod_",
        "_op_eq_", "_op_neq_",
        "_op_lt_", "_op_gt_", "_op_lte_", "_op_gte_",
        "_op_neg_",
        "_op_get_", "_op_set_",
        "_op_bitand_", "_op_bitor_", "_op_xor_",
        "_op_shl_", "_op_shr_", "_op_ushr_",
        "_op_bitnot_", "_op_not_", "_op_pos_",
    )

    // Literal too (not `OperatorIntrinsics.memberName(...)`), for the same reason as the table
    // above: only the signature template `signatureFor` picks depends on these, but a corrupted
    // `memberName` must not also corrupt the *input* an independent test feeds the parser.
    private val zeroArity = setOf("_op_neg_", "_op_pos_", "_op_not_", "_op_bitnot_")
    private val setArity = setOf("_op_set_")

    private fun signatureFor(name: String): String = when {
        name in zeroArity -> "()"
        name in setArity -> "(i: Int32, value: Int32)"
        else -> "(other: V2)"
    }

    @Test
    fun everyMemberNameParsesInAClass() {
        assertEquals(23, theOperatorTable1_3_1.size, "1.3.1's table has 23 names")
        for (name in theOperatorTable1_3_1) {
            val ast = parse("pub class V2 { pub fx @$name: ${signatureFor(name)} Void { } }")
            val cls = classLike<ClassDecl>(ast)
            val m = cls.members.filterIsInstance<FunctionDecl>().single()
            assertEquals(name, assertIs<IntrinsicExpr>(m.name).intrinsicKey.name, "class member: $name")
        }
    }

    @Test
    fun everyMemberNameParsesInATrait() {
        assertEquals(23, theOperatorTable1_3_1.size, "1.3.1's table has 23 names")
        for (name in theOperatorTable1_3_1) {
            val ast = parse("pub trait Ops { pub fx @$name: ${signatureFor(name)} Void; }")
            val trait = classLike<TraitDecl>(ast)
            val m = trait.members.single()
            assertEquals(name, assertIs<IntrinsicExpr>(m.name).intrinsicKey.name, "trait member: $name")
        }
    }

    @Test
    fun theLiteralTableMatchesWhatOperatorIntrinsicsActuallyRegisters() {
        // The other half of significant issue #4: the literal list above must still agree with
        // `OperatorIntrinsics.allMembers` -- so a renamed or dropped entry (V20/V21/V22) is
        // caught by *this* comparison, even though the two tests above no longer depend on it.
        assertEquals(
            theOperatorTable1_3_1.toSet(),
            OperatorIntrinsics.allMembers.map { it.name }.toSet(),
            "OperatorIntrinsics.allMembers must be exactly 1.3.1's 23 names",
        )
    }

    /** Captures `net.exoad.kira`'s java.util.logging records raised while running [block]. */
    private fun captureWarnings(block: () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger("net.exoad.kira")
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return records
    }

    @Test
    fun aFreeFormOperatorWarnsOnceAtModuleLevel() {
        val records = captureWarnings {
            TestCompileSupport.compileSnippet(
                """
                module "test:opparse.warn1"

                pub class Point {
                    require pub x: Int32
                }
                pub fx @op_add: (a: Point, b: Point) Point {
                    return Point { a.x + b.x }
                }
                """.trimIndent(),
                "tests/opwarn1.kira",
                runSemantic = true,
            )
        }
        val warnings = records.filter { it.message.contains("ops.free-form") }
        assertEquals(1, warnings.size, "expected exactly one ops.free-form warning, got:\n${records.joinToString("\n") { it.message }}")
        assertTrue(warnings.single().message.contains("@op_add"), warnings.single().message)
    }

    @Test
    fun aMemberOperatorNeverWarnsOpsFreeForm() {
        val records = captureWarnings {
            TestCompileSupport.compileSnippet(
                """
                module "test:opparse.warn2"

                pub class V2 {
                    pub x: Float32 = 0.0
                    pub fx @_op_add_: (other: V2) V2 { return V2 { x } }
                }
                """.trimIndent(),
                "tests/opwarn2.kira",
                runSemantic = true,
            )
        }
        assertTrue(records.none { it.message.contains("ops.free-form") }, records.joinToString("\n") { it.message })
    }
}
