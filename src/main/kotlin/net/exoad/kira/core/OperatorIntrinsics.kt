package net.exoad.kira.core

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.source.SourceContext

/**
 * Operator intrinsics: the `@op_*` family that makes operators overloadable.
 *
 * Every entry is a real [CompilerIntrinsic] (so the parser and the registry
 * treat `@op_add` as a known intrinsic name) but it is not a marker: it never
 * modifies a declaration. It is the **name** a user overload is declared
 * under (`fx @op_add: (a: Point, b: Point) Point`) and the name a
 * non-primitive operator expression desugars to in the backends.
 *
 * Only the operators listed here are overloadable. Things that are syntax
 * rather than operators -- member access `.`, range `..`, type checks
 * `is`/`as` -- deliberately have no entry and stay non-overloadable.
 *
 * Naming rule: every name starts with `op_` and uses underscores to separate
 * the operator words (never a bare `@add`).
 */
object OperatorIntrinsics {
    /** Concrete intrinsic instance for one `@op_*` name. */
    class OperatorIntrinsic(name: String) : CompilerIntrinsic(
        name,
        setOf(FunctionDecl::class, Identifier::class)
    ) {
        override fun validate(
            invocation: IntrinsicExpr,
            compilationUnit: CompilationUnit,
            context: SourceContext
        ) {
            // Operator intrinsics are names, not markers; nothing to validate.
        }

        override fun apply(
            invocation: IntrinsicExpr,
            target: ASTNode,
            compilationUnit: CompilationUnit,
            context: SourceContext
        ): ASTNode {
            return NoExpr
        }
    }

    /** Binary operator -> `@op_*` intrinsic name, or null when not overloadable. */
    fun binaryName(op: BinaryOp): String? {
        return when (op) {
            BinaryOp.ADD -> "op_add"
            BinaryOp.SUB -> "op_sub"
            BinaryOp.MUL -> "op_mul"
            BinaryOp.DIV -> "op_div"
            BinaryOp.MOD -> "op_mod"
            BinaryOp.HASH_MARK -> "op_hash"
            BinaryOp.EQUALS -> "op_eq"
            BinaryOp.NOT_EQUAL -> "op_neq"
            BinaryOp.GREATER_THAN_OR_EQUAL -> "op_ge"
            BinaryOp.LESS_THAN_OR_EQUAL -> "op_le"
            BinaryOp.GREATER_THAN -> "op_gt"
            BinaryOp.LESS_THAN -> "op_lt"
            BinaryOp.AND -> "op_and"
            BinaryOp.OR -> "op_or"
            BinaryOp.SHR -> "op_shr"
            BinaryOp.SHL -> "op_shl"
            BinaryOp.USHR -> "op_ushr"
            BinaryOp.XOR -> "op_xor"
            BinaryOp.CONJUNCTIVE_OR -> "op_bitor"
            BinaryOp.CONJUNCTIVE_AND -> "op_bitand"
            // Syntax, not overloadable operators.
            BinaryOp.CONJUNCTIVE_DOT,
            BinaryOp.RANGE,
            BinaryOp.TYPE_CHECK,
            BinaryOp.TYPE_CAST -> null
        }
    }

    /** Unary operator -> `@op_*` intrinsic name, or null when not overloadable. */
    fun unaryName(op: UnaryOp): String? {
        return when (op) {
            UnaryOp.NEG -> "op_neg"
            UnaryOp.POS -> "op_pos"
            UnaryOp.NOT -> "op_not"
            UnaryOp.BIT_NOT -> "op_bitnot"
        }
    }

    /** Every operator intrinsic instance, registered in [IntrinsicRegistry]. */
    val all: List<OperatorIntrinsic> by lazy {
        val names = linkedSetOf<String>()
        BinaryOp.entries.forEach { binaryName(it)?.let { n -> names.add(n) } }
        UnaryOp.entries.forEach { unaryName(it)?.let { n -> names.add(n) } }
        names.map { OperatorIntrinsic(it) }
    }
}
