package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr

/**
 * Left-to-right evaluation where C++ leaves the order open (R19, D33). Kira evaluates call
 * arguments and the operands of an operator left to right; C++ does not sequence a call's
 * arguments, nor the operands of `+`, `==` and the other arithmetic, comparison and bitwise
 * operators (gcc and clang measurably disagree [B-M8]). C++17 does sequence a member call's
 * object before its arguments, the left operand of `<<` and `>>` before the right, and `&&`,
 * `||` and `?:`, so those need nothing.
 *
 * [lower] spills the operands of one call or operator that hold an impure call into typed
 * temporaries, in source order, inside an immediately invoked lambda, when two or more of them
 * do:
 *
 * ```
 * [&]() -> std::int32_t
 * {
 *     const std::int32_t t0_ = next();
 *     const std::int32_t t1_ = next();
 *     return sub(t0_, t1_);
 * }()
 * ```
 *
 * - An operand's effect is `TypedModel.effects` (EffectsPass, W2.5): only an IMPURE operand
 *   counts and is spilled; one that only reads (READS: a class field, a `mut` global, a view's
 *   contents) is a place and stays where it is. Where the model has no entry for an operand,
 *   the operand is impure when it contains a call whose own entry is absent (absent means
 *   impure), unless the callee is a stdlib binding marked `pure: true` or a function
 *   EffectsPass proved pure; an operand holding no call holds no impure call. A lambda's body
 *   is not evaluated where the lambda is written, so it does not count.
 * - A `mut` argument and a receiver a binding writes through are never spilled (the judges'
 *   B5 flaw: a place copied into a temporary is written in the copy). Temporaries are typed
 *   (never `auto`, which would keep a `kira::at` reference).
 * - A temporary's name is reserved before its initializer is written, so a nested spill in the
 *   initializer never declares the same name inside it (`-Wshadow`).
 */
class CppHoister(private val lower: CppLowering) {
    /**
     * One operand, in source order. [expr] is null for a piece that is no expression (an
     * interpolation's text); [spillable] is false for a place that must stay one.
     */
    class Operand(val expr: Expr?, val spillable: Boolean, val emit: () -> CppEx)

    /** [build] over the operands' texts, spilling them first when D33 needs it. [result] types the IIFE. */
    fun lower(ops: List<Operand>, result: KType, build: (List<CppEx>) -> CppEx): CppEx {
        if (!needsSpill(ops)) {
            return build(ops.map { it.emit() })
        }
        return spill(ops, result, build)
    }

    /**
     * Whether two or more operands hold an impure call (design R19, D33). An operand that only
     * reads shared state ([READS]: a class field, a `mut` global) is left where it is: it is a
     * place, and places are never spilled; ExclusivityPass (W2.5) refuses the one order a
     * sibling's write could change (`rules.exclusivity.order`).
     */
    fun needsSpill(ops: List<Operand>): Boolean {
        if (ops.size < 2) {
            return false
        }
        return ops.count { op -> op.expr?.let { rank(it) } == IMPURE } >= 2
    }

    private fun spill(ops: List<Operand>, result: KType, build: (List<CppEx>) -> CppEx): CppEx {
        val state = lower.state
        val ctx = lower.ctx
        return state.block {
            val lines = mutableListOf<String>()
            val texts = ops.map { op ->
                val e = op.expr
                val t = e?.let { lower.model.typeOrNull(it) }
                if (e == null || !op.spillable || rank(e) != IMPURE || t == null || t == KType.Void || t == KType.Never) {
                    op.emit()
                } else {
                    val name = state.fresh("t")
                    val init = op.emit()
                    lines += "const ${ctx.spell(t, Pos.VALUE, e)} $name = ${lower.wrap(init, CppPrec.ASSIGN)};"
                    CppEx(name, CppPrec.PRIMARY)
                }
            }
            val final = build(texts)
            val void = result == KType.Void || result == KType.Never
            lines += if (void) "${final.text};" else "return ${final.text};"
            val capture = if (state.inBody) "[&]" else "[]"
            val ret = ctx.spell(if (result == KType.Never) KType.Void else result, Pos.RETURN)
            CppEx("$capture() -> $ret\n{\n${indent(lines)}\n}()", CppPrec.POSTFIX)
        }
    }

    /** [PURE], [READS] or [IMPURE] for [e]: the model's answer, else whether it holds an impure call. */
    fun rank(e: Expr): Int {
        lower.model.effects[e]?.let { return rankOf(it) }
        return if (holdsImpureCall(e)) IMPURE else PURE
    }

    private fun rankOf(effect: Effect): Int = when (effect) {
        Effect.PURE -> PURE
        Effect.IMPURE -> IMPURE
        else -> READS
    }

    private fun holdsImpureCall(root: Expr): Boolean {
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        val seen = java.util.IdentityHashMap<ASTNode, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null) {
                continue
            }
            when (node) {
                is LambdaExpr -> continue
                is FunctionCallExpr -> if (!isPureCall(node)) return true
                is IntrinsicExpr -> if (node.intrinsicKey.name == "_trace_") return true
                is ThrowExpr, is TryExpr, is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr -> return true
                is Expr -> lower.model.opCalls[node]?.fn?.let { if (lower.model.effect(it) != Effect.PURE) return true }
                else -> {}
            }
            AstTree.children(node).forEach { stack.addLast(it) }
        }
        return false
    }

    private fun isPureCall(c: FunctionCallExpr): Boolean {
        lower.model.effects[c]?.let { return rankOf(it) == PURE }
        val rc = lower.model.call(c) ?: return false
        val fn = rc.fn ?: return false
        return when (rc.kind) {
            CallKind.MAGIC -> lower.bindingFor(fn, rc.receiver?.let { lower.model.typeOrNull(it) })?.second?.pure == true
            CallKind.FREE, CallKind.METHOD -> lower.model.effect(fn) == Effect.PURE
            else -> false
        }
    }

    companion object {
        const val PURE = 0
        const val READS = 1
        const val IMPURE = 2

        /** Every line of [lines] (each possibly several) indented four spaces. */
        fun indent(lines: List<String>): String =
            lines.flatMap { it.split('\n') }.joinToString("\n") { if (it.isEmpty()) it else "    $it" }
    }
}
