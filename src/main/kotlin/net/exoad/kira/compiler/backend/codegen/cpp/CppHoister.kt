package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr

/**
 * Left-to-right evaluation where C++ leaves the order open (R19, D33). Kira evaluates a
 * call's receiver and arguments, and the operands of an operator, left to right; C++ does not
 * sequence a call's arguments, nor the operands of `+`, `==` and the other arithmetic,
 * comparison and bitwise operators (gcc and clang measurably disagree [B-M8]), and it runs
 * the right side of `=` before the left. C++17 does sequence `&&`, `||` and `?:`, and the left
 * operand of a shift before the right, so those need nothing.
 *
 * [lower] spills the operands of one call, operator or assignment, in source order, inside an
 * immediately invoked lambda, when one of them is [IMPURE] and another is not [PURE] (the
 * rule `Effect`'s KDoc states, and EffectsPass fills the model by):
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
 * - A value operand ([Operand.place] false) that is not PURE is copied into a typed temporary
 *   (`const T t0_`, never `auto`, which would keep a `kira::at` reference): an impure one so
 *   it runs in order, a [READS] one so it is read before a later sibling's effect (`sub(ticks,
 *   next())`, `"${ticks}:${next()}"`).
 * - A place operand (a `mut` argument, an assignment's target, the receiver a `mut fx` or a
 *   member-style binding writes through) is never copied (the judges' B5 flaw: a place copied
 *   into a temporary is written in the copy). Its identity is computed in order instead,
 *   bound by reference (`std::int32_t& r0_ = kira::at(q, nextSize());`) when the
 *   sub-expressions that locate it are not PURE ([placeRank]); a plain variable's identity
 *   never changes, so it stays where it is.
 * - An operand's rank is `TypedModel.effects` (EffectsPass, W2.5). Where the model has no
 *   entry (before the merge, or for a node no pass visited), [rank] approximates the same
 *   three values: IMPURE for a call whose own entry is absent (absent means impure) unless
 *   the callee is a stdlib binding marked `pure: true` or a function EffectsPass proved pure,
 *   for an assignment, a `throw`, a `try` or a trace; READS for a read of a `mut` global, a
 *   parameter passed by reference (`mut`, or a struct, `Str`, container or class the design
 *   passes by `const&`), a field, or an element of a view; PURE otherwise. A lambda's body is
 *   not evaluated where the lambda is written, so it does not count.
 * - A temporary's name is reserved before its initializer is written, so a nested spill in the
 *   initializer never declares the same name inside it (`-Wshadow`).
 */
class CppHoister(private val lower: CppLowering) {
    /**
     * One operand, in source order. [expr] is null for a piece that is no expression (an
     * interpolation's text). A [place] is bound by reference when it must be ordered, a value
     * is copied; [mutable] drops the `const` (a `mut` argument's `T&`, a receiver a method
     * writes).
     */
    class Operand(val expr: Expr?, val place: Boolean, val mutable: Boolean, val emit: () -> CppEx) {
        /** A value operand, copied into a `const` temporary when it must be ordered. */
        constructor(expr: Expr?, emit: () -> CppEx) : this(expr, place = false, mutable = false, emit)
    }

    /**
     * [build] over the operands' texts, spilling them first when D33 needs it, or when [force]
     * says a non-PURE place among them must be located exactly once (a compound assignment
     * that names its target twice). [result] types the IIFE.
     */
    fun lower(ops: List<Operand>, result: KType, force: Boolean = false, build: (List<CppEx>) -> CppEx): CppEx {
        if (!force && !needsSpill(ops)) {
            return build(ops.map { it.emit() })
        }
        return spill(ops, result, build)
    }

    /** Whether one operand is [IMPURE] and another is not [PURE] (design R19, D33; `Effect`). */
    fun needsSpill(ops: List<Operand>): Boolean {
        if (ops.size < 2) {
            return false
        }
        val ranks = ops.map { rankOf(it) }
        return ranks.any { it == IMPURE } && ranks.count { it != PURE } >= 2
    }

    /** The rank of [op]: how its place is located for a place operand, its evaluation for a value. */
    fun rankOf(op: Operand): Int {
        val e = op.expr ?: return PURE
        return if (op.place) placeRank(e) else rank(e)
    }

    private fun spill(ops: List<Operand>, result: KType, build: (List<CppEx>) -> CppEx): CppEx {
        val state = lower.state
        val ctx = lower.ctx
        return state.block {
            val lines = mutableListOf<String>()
            val texts = ops.map { op ->
                val e = op.expr
                val t = e?.let { lower.model.typeOrNull(it) }
                if (e == null || rankOf(op) == PURE || t == null || t == KType.Void || t == KType.Never) {
                    op.emit()
                } else if (op.place) {
                    val name = state.fresh("r")
                    val init = op.emit()
                    lines += "${if (op.mutable) "" else "const "}${ctx.spell(t, Pos.VALUE, e)}& $name = ${lower.wrap(init, CppPrec.ASSIGN)};"
                    CppEx(name, CppPrec.PRIMARY)
                } else {
                    val name = state.fresh("t")
                    val init = op.emit()
                    lines += "${if (op.mutable) "" else "const "}${ctx.spell(t, Pos.VALUE, e)} $name = ${lower.wrap(init, CppPrec.ASSIGN)};"
                    CppEx(name, CppPrec.PRIMARY)
                }
            }
            val final = build(texts)
            val void = result == KType.Void || result == KType.Never
            lines += if (void) "${final.text};" else "return ${final.text};"
            iife(result, lines)
        }
    }

    /** [lines] (statements, the last a `return` unless [result] is Void) as an immediately invoked lambda. */
    fun iife(result: KType, lines: List<String>): CppEx {
        val capture = if (lower.state.inBody) "[&]" else "[]"
        val ret = lower.ctx.spell(if (result == KType.Never) KType.Void else result, Pos.RETURN)
        return CppEx("$capture() -> $ret\n{\n${indent(lines)}\n}()", CppPrec.POSTFIX)
    }

    /** [PURE], [READS] or [IMPURE] for evaluating [e]: the model's answer, else [scan]'s approximation. */
    fun rank(e: Expr): Int {
        lower.model.effects[e]?.let { return rankOf(it) }
        return scan(e)
    }

    /**
     * The rank of locating the place [e]: a variable, `this` or a field of a value place has a
     * fixed identity (PURE); a field through a reference depends on the reference's value; an
     * element on its index and on its container (the container's value when it is a view or a
     * reference, its identity when it is a value).
     */
    fun placeRank(e: Expr): Int = when (e) {
        is Identifier -> if (e is IntrinsicExpr) rank(e) else PURE
        is ThisExpr -> PURE
        is MemberAccessExpr -> when (lower.model.member(e)) {
            is MemberRef.Field -> if (lower.isPointerLike(lower.typeOf(e.origin))) rank(e.origin) else placeRank(e.origin)
            else -> rank(e)
        }
        is ArrayIndexExpr -> {
            val ct = lower.model.typeOrNull(e.originExpr)
            val container = if (ct != null && (lower.isPointerLike(ct) || isView(ct))) rank(e.originExpr) else placeRank(e.originExpr)
            maxOf(container, rank(e.indexExpr))
        }
        else -> rank(e)
    }

    private fun rankOf(effect: Effect): Int = when (effect) {
        Effect.PURE -> PURE
        Effect.IMPURE -> IMPURE
        else -> READS
    }

    /** The approximation of `Effect` for [root] where the model has no entry (the class KDoc). */
    private fun scan(root: Expr): Int {
        var best = PURE
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        val seen = java.util.IdentityHashMap<ASTNode, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null) {
                continue
            }
            if (node !== root && node is Expr) {
                lower.model.effects[node]?.let {
                    best = maxOf(best, rankOf(it))
                    if (best == IMPURE) return IMPURE
                    continue
                }
            }
            when (node) {
                is LambdaExpr -> continue
                is FunctionCallExpr -> if (!isPureCall(node)) return IMPURE
                is IntrinsicExpr -> if (node.intrinsicKey.name == "_trace_") return IMPURE
                is ThrowExpr, is TryExpr, is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr -> return IMPURE
                is Identifier -> if (readsShared(node)) best = READS
                is MemberAccessExpr -> {
                    // The member's own name is a field or method name, not a read of anything; a
                    // field through `this` or a reference is shared state, a local struct's is not.
                    val origin = node.origin
                    if (lower.model.member(node) is MemberRef.Field && (origin is ThisExpr || lower.model.typeOrNull(origin)?.let { lower.isPointerLike(it) } == true)) {
                        best = READS
                    }
                    stack.addLast(origin)
                    (node.member as? FunctionCallExpr)?.let { stack.addLast(it) }
                    continue
                }
                is ArrayIndexExpr -> if (lower.model.typeOrNull(node.originExpr)?.let { isView(it) } == true) best = READS
                is Expr -> lower.model.opCalls[node]?.fn?.let { if (lower.model.effect(it) != Effect.PURE) return IMPURE }
                else -> {}
            }
            AstTree.children(node).forEach { stack.addLast(it) }
        }
        return best
    }

    /** Whether the name [id] reads state a sibling's effect could change (`Effect.READS`). */
    private fun readsShared(id: Identifier): Boolean = when (val sym = lower.model.symbolOf(id)) {
        is GlobalSymbol -> sym.isMut
        is FieldSymbol -> true
        is ParamSymbol -> sym.byRef || byConstRef(sym.type)
        else -> false
    }

    /** A parameter type the design passes by `const&` (5.1): a struct, a `Str`, a container, a class, a type parameter. */
    private fun byConstRef(t: KType): Boolean = when (t) {
        KType.Str, is KType.Param -> true
        is KType.Nominal -> t.sym is ClassSymbol || t.sym is TraitSymbol
        else -> false
    }

    private fun isView(t: KType): Boolean = CppBindingTable.magicName(t).let { it == "View" || it == "MutView" }

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
