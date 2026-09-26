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
 * An operand is one of two kinds, and the kind decides what "in order" means for it:
 *
 * - A [Operand.Value] is what C++ holds by value: a scalar, a `Char`, a `Bool`, an enum, a
 *   view, a class or trait handle, or any fresh result (a call, a construction, an operator).
 *   When it is not PURE it is copied into a typed temporary (`const T t0_`, never `auto`,
 *   which would keep a `kira::at` reference): an impure one so it runs in order, a [READS] one
 *   so it is read before a later sibling's effect (`sub(ticks, next())`, `"${ticks}:${next()}"`).
 * - A [Operand.Place] is a location C++ reaches through a path: a variable, a field of it, an
 *   element of it. Its [Operand.Place.parts] are the values along the path (an index, the
 *   handle or view a step goes through), each an operand in its own right; they are ordered
 *   like any value, and the path is applied to them at the end, where the call or assignment
 *   uses it (`kira::at(q, t0_)` for `q[nextSize()]`). A place is never copied and never held
 *   by reference across a sibling's effect: a copy would be written instead of the place (the
 *   judges' B5 flaw) or lent from by a view the call returns (a use after free at the end of
 *   the lambda); a reference held while a sibling reallocates the container would dangle. A
 *   variable's own identity never changes, so a plain place has no parts and stays where it
 *   is. Every `mut` argument, assignment target and receiver a method writes is a place, and
 *   so is any value C++ passes by `const&` (a `Str`, a struct, a container, a type parameter:
 *   design 5.1) whenever it is read at a place: the call reads the object itself, as every
 *   unspilled call already does, and a view it lends points at the caller's storage.
 * - An operand's rank is `TypedModel.effects` (EffectsPass, W2.5). Where the model has no
 *   entry (before the merge, or for a node no pass visited), [rank] approximates the same
 *   three values: IMPURE for a call whose own entry is absent (absent means impure) unless
 *   the callee is a stdlib binding marked `pure: true` or a function EffectsPass proved pure,
 *   for an assignment, a `throw`, a `try` or a trace; READS for a read of a `mut` global, a
 *   parameter passed by reference (`mut`, or a struct, `Str`, container or class the design
 *   passes by `const&`), a field, or an element of a view; PURE otherwise. A lambda's body is
 *   not evaluated where the lambda is written, so it does not count. A place's rank is the
 *   highest of its parts' (PURE when it has none): how its location is found, not what it
 *   holds.
 * - Whether to spill is decided over the leaves: every value operand, and every value part
 *   of a place, at any depth (`grid[nextSize()][nextSize()] = 5` holds two impure leaves in
 *   one place and must order them).
 * - A temporary's name is reserved before its initializer is written, so a nested spill in the
 *   initializer never declares the same name inside it (`-Wshadow`).
 */
class CppHoister(private val lower: CppLowering) {
    /** One operand, in source order. [expr] is null for a piece that is no expression (an interpolation's text). */
    sealed class Operand(val expr: Expr?) {
        /**
         * A value: copied into a typed temporary when it must be ordered (`const`, or a plain
         * `T t0_` when [mutable]: a receiver a member-style binding calls a non-const method on).
         */
        class Value(expr: Expr?, val mutable: Boolean = false, val emit: () -> CppEx) : Operand(expr)

        /**
         * A place: [parts] are the values along its path, in source order, and [build] applies
         * the path to their texts. A place with no parts is a variable, `this` or a field of
         * `this`: [build] spells it and nothing is ordered.
         */
        class Place(expr: Expr, val parts: List<Operand>, val build: (List<CppEx>) -> CppEx) : Operand(expr)
    }

    /**
     * [build] over the operands' texts, spilling them first when D33 needs it, or when [force]
     * says the parts of a place among them must be computed exactly once (a compound
     * assignment that names its target twice). [result] types the IIFE.
     */
    fun lower(ops: List<Operand>, result: KType, force: Boolean = false, build: (List<CppEx>) -> CppEx): CppEx {
        if (!force && !needsSpill(ops)) {
            return build(ops.map { text(it) })
        }
        return spill(ops, result, build)
    }

    /** The text of [op] where nothing needs ordering: a value as written, a place as its path over its parts' texts. */
    fun text(op: Operand): CppEx = when (op) {
        is Operand.Value -> op.emit()
        is Operand.Place -> op.build(op.parts.map { text(it) })
    }

    /** Whether one leaf is [IMPURE] and another is not [PURE] (design R19, D33; `Effect`). */
    fun needsSpill(ops: List<Operand>): Boolean {
        val ranks = leaves(ops)
        return ranks.any { it == IMPURE } && ranks.count { it != PURE } >= 2
    }

    /** The rank of [op]: a value's evaluation, or the highest of a place's parts. */
    fun rankOf(op: Operand): Int = when (op) {
        is Operand.Value -> op.expr?.let { rank(it) } ?: PURE
        is Operand.Place -> op.parts.maxOfOrNull { rankOf(it) } ?: PURE
    }

    private fun leaves(ops: List<Operand>): List<Int> = ops.flatMap { op ->
        when (op) {
            is Operand.Value -> listOf(rankOf(op))
            is Operand.Place -> leaves(op.parts)
        }
    }

    private fun spill(ops: List<Operand>, result: KType, build: (List<CppEx>) -> CppEx): CppEx = lower.state.block {
        val lines = mutableListOf<String>()
        val texts = ops.map { ordered(it, lines) }
        val final = build(texts)
        val void = result == KType.Void || result == KType.Never
        lines += if (void) "${final.text};" else "return ${final.text};"
        iife(result, lines)
    }

    /**
     * The text of [op] once its evaluation is ordered, the declarations that order it appended
     * to [lines]: a value that is not PURE is copied into a typed temporary, a place is its path
     * over its ordered parts. Called inside a [CppBodyState.block] that an [iife] closes.
     */
    fun ordered(op: Operand, lines: MutableList<String>): CppEx = when (op) {
        is Operand.Place -> op.build(op.parts.map { ordered(it, lines) })
        is Operand.Value -> {
            val e = op.expr
            val t = e?.let { lower.model.typeOrNull(it) }
            if (e == null || rank(e) == PURE || t == null || t == KType.Void || t == KType.Never) {
                op.emit()
            } else {
                val name = lower.state.fresh("t")
                val init = op.emit()
                lines += "${if (op.mutable) "" else "const "}${lower.ctx.spell(t, Pos.VALUE, e)} $name = ${lower.wrap(init, CppPrec.ASSIGN)};"
                CppEx(name, CppPrec.PRIMARY)
            }
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
