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
 * Operator intrinsics: the names that make operators overloadable.
 *
 * W2.9 (1.3.1) makes the *member* form the one form (the spec's own shape):
 * `pub fx @_op_add_: (other: V2) V2 { ... }` inside `class V2`, found by
 * ordinary member lookup and callable explicitly too (`a.@_op_add_(b)`).
 * [memberName] gives that table, plus [GET] and [SET] for indexing
 * (`a[i]` / `a[i] = v`), which have no free form.
 *
 * The pre-W2.9 free form ([binaryName], [unaryName]; `fx @op_add: (a: T, b: T) T`
 * at module level) stays registered for C and JS (user decision 2, last
 * bullet): accepted with the `ops.free-form` deprecation warning, never on
 * `--target cpp`, never documented.
 *
 * Every entry is a real [CompilerIntrinsic] (so the parser and the registry
 * treat the name as known) but it is not a marker: it never modifies a
 * declaration by itself. It is the **name** an overload is declared under,
 * and the name a non-primitive operator expression desugars to.
 *
 * Only the operators listed here are overloadable. Things that are syntax
 * rather than operators -- member access `.`, range `..`, type checks
 * `is`/`as`, `&&`/`||` (short-circuiting, so no method call can replace them,
 * 1.3.1) -- deliberately have no entry and stay non-overloadable.
 *
 * Naming rule: a free name starts with `op_` and uses underscores to
 * separate the operator words (never a bare `@add`); a member name is
 * `_op_<word>_`, underscored on both ends (1.3.1).
 */
object OperatorIntrinsics {
    /** Concrete intrinsic instance for one operator name, free or member. */
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

    /** `a[i]` (1.3.1: `@_op_get_`, arity 1). No free form ever existed for indexing. */
    const val GET = "_op_get_"

    /** `a[i] = v` (1.3.1: `@_op_set_`, arity 2, `Void`). No free form ever existed for indexing. */
    const val SET = "_op_set_"

    /** Binary operator -> the pre-W2.9 free `@op_*` intrinsic name, or null when not overloadable. */
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

    /** Unary operator -> the pre-W2.9 free `@op_*` intrinsic name, or null when not overloadable. */
    fun unaryName(op: UnaryOp): String? {
        return when (op) {
            UnaryOp.NEG -> "op_neg"
            UnaryOp.POS -> "op_pos"
            UnaryOp.NOT -> "op_not"
            UnaryOp.BIT_NOT -> "op_bitnot"
        }
    }

    /**
     * Binary operator -> the member `@_op_*_` intrinsic name (1.3.1's table), or null for `&&`,
     * `||` and the syntax forms, which are never methods.
     */
    fun memberName(op: BinaryOp): String? {
        return when (op) {
            BinaryOp.ADD -> "_op_add_"
            BinaryOp.SUB -> "_op_sub_"
            BinaryOp.MUL -> "_op_mul_"
            BinaryOp.DIV -> "_op_div_"
            BinaryOp.MOD -> "_op_mod_"
            BinaryOp.EQUALS -> "_op_eq_"
            BinaryOp.NOT_EQUAL -> "_op_neq_"
            BinaryOp.LESS_THAN -> "_op_lt_"
            BinaryOp.GREATER_THAN -> "_op_gt_"
            BinaryOp.LESS_THAN_OR_EQUAL -> "_op_lte_"
            BinaryOp.GREATER_THAN_OR_EQUAL -> "_op_gte_"
            BinaryOp.CONJUNCTIVE_AND -> "_op_bitand_"
            BinaryOp.CONJUNCTIVE_OR -> "_op_bitor_"
            BinaryOp.XOR -> "_op_xor_"
            BinaryOp.SHL -> "_op_shl_"
            BinaryOp.SHR -> "_op_shr_"
            BinaryOp.USHR -> "_op_ushr_"
            // `&&` / `||` short-circuit (1.3.1: no method call can), so they take Bool operands
            // only and are never overloadable. `#` has no member form (free-only, legacy).
            BinaryOp.AND,
            BinaryOp.OR,
            BinaryOp.HASH_MARK,
            BinaryOp.CONJUNCTIVE_DOT,
            BinaryOp.RANGE,
            BinaryOp.TYPE_CHECK,
            BinaryOp.TYPE_CAST -> null
        }
    }

    /** Unary operator -> the member `@_op_*_` intrinsic name (1.3.1's table). */
    fun memberName(op: UnaryOp): String? {
        return when (op) {
            UnaryOp.NEG -> "_op_neg_"
            UnaryOp.POS -> "_op_pos_"
            UnaryOp.NOT -> "_op_not_"
            UnaryOp.BIT_NOT -> "_op_bitnot_"
        }
    }

    /**
     * The member name and arity (0 for unary, 1 for binary) [freeName]'s operator would take in
     * the one form, or null when it has none (`op_hash`, `op_and`, `op_or`: 1.3.1 gives these no
     * member form). Used only to word the `ops.free-form` warning.
     */
    fun freeToMember(freeName: String): Pair<String, Int>? {
        BinaryOp.entries.firstOrNull { binaryName(it) == freeName }?.let { bin ->
            return (memberName(bin) ?: return null) to 1
        }
        UnaryOp.entries.firstOrNull { unaryName(it) == freeName }?.let { un ->
            return (memberName(un) ?: return null) to 0
        }
        return null
    }

    /** Every free operator intrinsic instance, registered in [IntrinsicRegistry]. */
    val all: List<OperatorIntrinsic> by lazy {
        val names = linkedSetOf<String>()
        BinaryOp.entries.forEach { binaryName(it)?.let { n -> names.add(n) } }
        UnaryOp.entries.forEach { unaryName(it)?.let { n -> names.add(n) } }
        names.map { OperatorIntrinsic(it) }
    }

    /** Every member operator intrinsic instance (1.3.1), registered in [IntrinsicRegistry]. */
    val allMembers: List<OperatorIntrinsic> by lazy {
        val names = linkedSetOf<String>()
        BinaryOp.entries.forEach { memberName(it)?.let { n -> names.add(n) } }
        UnaryOp.entries.forEach { memberName(it)?.let { n -> names.add(n) } }
        names.add(GET)
        names.add(SET)
        names.map { OperatorIntrinsic(it) }
    }

    private val freeNames: Set<String> by lazy { all.map { it.name }.toSet() }
    private val memberNames: Set<String> by lazy { allMembers.map { it.name }.toSet() }

    /**
     * True when [name] (an [net.exoad.kira.compiler.frontend.lexer.Token.Type.INTRINSIC_IDENTIFIER]'s
     * content, without the `@`) is a registered operator intrinsic, free or member. Used by
     * `KiraParser.parsePostfix` to decide whether `.@name` parses as a member (1.3.2), rather
     * than falling through to `parseIdentifier`'s ordinary error.
     */
    fun isOperatorName(name: String): Boolean = name in freeNames || name in memberNames

    /**
     * True when [name] is the pre-W2.9 free form (`op_add`, ...), never the member form. Used
     * by `KiraSemanticAnalyzer` to warn `ops.free-form` on a module-level `@op_*` declaration
     * (1.3.5).
     */
    fun isFreeOperatorName(name: String): Boolean = name in freeNames

    /**
     * True when [name] is a member form name ([memberName], [GET] or [SET]), never the pre-W2.9
     * free form. Used by `DeclarationCollector.function` to refuse a module-level declaration
     * spelled with the member form (`@_op_add_`), which would otherwise silently become a "free
     * operator" under a name neither backend's free-operator lowering ever emits (1.3.1, 1.3.5).
     */
    fun isMemberOperatorName(name: String): Boolean = name in memberNames
}
