package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import java.util.Collections
import java.util.IdentityHashMap

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
 * An operand is one of three kinds, and the kind decides what "in order" means for it:
 *
 * - A [Operand.Value] is what C++ holds by value: a scalar, a `Char`, a `Bool`, an enum, a
 *   view, a class or trait handle, or any fresh result (a call, a construction, an operator).
 *   When it is not PURE it is copied into a typed temporary (`const T t0_`, never `auto`,
 *   which would keep a `kira::at` reference): an impure one so it runs in order, a [READS] one
 *   so it is read before a later sibling's effect (`sub(ticks, next())`, `"${ticks}:${next()}"`).
 * - A [Operand.Place] is a location C++ reaches through a path: a variable, a field of it, an
 *   element of it. Its [Operand.Place.parts] are the values along the path (an index, the
 *   handle a step goes through), each an operand in its own right; they are ordered like any
 *   value, and the path is applied to them at the end, where the call or assignment uses it
 *   (`kira::at(q, t0_)` for `q[nextSize()]`). A place is never held by reference across a
 *   sibling's effect: a reference held while a sibling reallocates the container would
 *   dangle. A variable's own identity never changes, so a plain place has no parts and stays
 *   where it is. Every `mut` argument, assignment target and receiver a method writes is a place.
 * - A value C++ passes by `const&` (a `Str`, a struct, a container, a type parameter: design
 *   5.1) read at a place is a [PlaceMode.SNAPSHOT] place: D33 reads it before a sibling's
 *   effect, and C++ would hand the callee the object as the effect left it
 *   (`show(gs, changeS())` printed the new `gs`), so where it is not PURE it is copied like a
 *   value, its path ordered first (`const kira::Str t0_ = gs;`).
 * - A place a view is formed of is [PlaceMode.LENT] (design 30, E1): a `List` or `Arr` place
 *   the typer converts to a `View` for a parameter (`Coercion.ToView`), or the receiver of a
 *   method whose result is second-class (`gl.from(k)`, `bag.head(k)`: the return rule lets
 *   such a result point only into its receiver and its view arguments). It is never copied: a
 *   copy would be what the view points into, and a sibling's write of an element would not be
 *   seen through it. It is a leaf of its own (its read rank), its path applied where the call
 *   uses it, after every sibling. The rule (design 30 3.3, ViewPass in W2.5) refuses a sibling
 *   in the view's span that may move or replace the place, so the view made there is the one
 *   Kira's left to right makes.
 * - A written place is read by the callee, never at the call. An assignment's target
 *   ([PlaceMode.PATH]) is written after its value runs (C++17 sequences the right side of `=`
 *   first): its parts are its only leaves. A `mut` argument or the receiver a `mut fx` writes
 *   ([PlaceMode.BOUND]) is a reference C++ may bind before a sibling runs, so each element
 *   step through a container that reallocates (a `List`, a `Map`) is a leaf too, at the rank
 *   of reading that container: a sibling that may grow it is spilled first and the path is
 *   applied after it (`gll[0].add(growGll())` pushed into freed storage on every compiler).
 * - A [Operand.Chain] is a call whose result is second-class (design 30 1.1: a `View`, a
 *   `MutView`, a `CStr`) used as an operand or a path step. Its receiver and arguments are
 *   leaves of the enclosing call's spill, and the call is made over their ordered texts, so a
 *   view is formed only inside the lambda that holds what it points into (E2, E3): the owner of
 *   a view of a temporary is spilled into an owning typed temporary (`const
 *   kira::List<std::int32_t> t0_ = makeList();`, then `kira::view(t0_)`, never `const
 *   kira::View<std::int32_t> t0_ = kira::view(makeList());`, whose list dies with the
 *   declaration), and no lambda returns a view into a temporary of its own: the spill is
 *   opened at the chain's root, the nearest call whose result is first-class. A chain call
 *   with an effect of its own is copied there after its parts, into a `const kira::View<T> tN_`
 *   that points into an earlier temporary of the same lambda or a place.
 * - An operand's rank: IMPURE where `TypedModel.effects` (EffectsPass, W2.5) says so; else
 *   [scan]'s answer, since the model's PURE says the evaluation has no effect, not that it
 *   reads nothing a sibling writes. The scan: IMPURE for a call whose own entry is absent
 *   (absent means impure) unless the callee is a stdlib binding marked `pure: true` or a
 *   function EffectsPass proved pure, dispatched statically, with a body (a virtual, trait,
 *   `Fx` or extern call, and a call handed a `MutView` it may write through, is IMPURE
 *   whatever an entry says: decision 4b read literally), for a construction whose class has
 *   an `initially` or `finally` block or a left-out field's default that is IMPURE, for an
 *   assignment, a `throw`, a `try` or a trace; READS
 *   for a read of a `mut` global, a parameter passed by reference (`mut`, or a struct, `Str`,
 *   container or class the design passes by `const&`), a field, an element of a view, or a
 *   pure call that reads through a view or a handle (`v.get(0)`, `readU16Le(pkt, 0)`); PURE
 *   otherwise. Taking a view reads no element. A lambda's body is not evaluated where the
 *   lambda is written, so it does not count. A place's rank is the highest of its parts'
 *   (PURE when it has none): how its location is found, not what it holds.
 * - A local no sibling can reach is PURE, except where a sibling writes it: through a `mut`
 *   argument, as the receiver of a `mut fx`, as the source of a `MutView` handed to a call, or
 *   as an assignment's target. Among such operands ([writtenBy]) a read of it is READS, so it
 *   is read before the write (`sub(x, inc(mut x))` printed 14 on gcc and 4 on clang).
 * - Whether to spill is decided over the leaves: every value operand, every snapshot or lent
 *   place, every value part of a written place, at any depth (`grid[nextSize()][nextSize()]
 *   = 5` holds two impure leaves in one place and must order them), a bound place's steps, and
 *   every part of a chain with the chain's own call.
 * - Views are second-class (decision 4b, design 30): which view is allowed where, and which
 *   write a view's span may hold, is ViewPass's (W2.5), and this lowers what it allows without
 *   refusing any. A second-class value in a position the rule never allows reaching the
 *   emitter is a checker's bug, `cpp.internal` ([internalView]). One shape design 30 2.1 lets
 *   through is not lowered, `cpp.unsupported` ([unsupportedView]), and is left to ViewPass to
 *   refuse as it refuses a `for` range with a TEMP origin (KI-13): a view of a temporary inside
 *   an if-expression's branch that D33 must order against a sibling, since the branch's owner
 *   cannot be spilled unconditionally.
 * - A temporary's name is reserved before its initializer is written, so a nested spill in the
 *   initializer never declares the same name inside it (`-Wshadow`).
 */
class CppHoister(private val lower: CppLowering) {
    /** How a [Operand.Place] is ordered among its siblings (the class KDoc). */
    enum class PlaceMode {
        /**
         * Written after its siblings ran (an assignment's target: C++17 runs the right side of
         * `=` and `op=` first), or only located: never read at the call; its parts are its leaves.
         */
        PATH,

        /**
         * Written through a reference C++ may bind before a sibling runs (a `mut` argument,
         * whose binding is unsequenced against the other arguments, or the receiver a `mut fx`
         * writes, the object expression sequenced before the arguments): its parts are its
         * leaves, and so is each element step through storage that reallocates (a `List`, a
         * `Map`), at the rank of reading that container, so a sibling that may move it is spilled
         * first and the path is applied after it (`gll[0].add(growGll())` pushed into freed storage).
         */
        BOUND,

        /** Read as a value no view is formed of: copied into a typed temporary, its path first, before a sibling's effect. */
        SNAPSHOT,

        /** A place a view is formed of (E1): never copied; a leaf of its own, its path applied after every sibling. */
        LENT,
    }

    /** One operand, in source order. [expr] is null for a piece that is no expression (an interpolation's text, an implicit `this`). */
    sealed class Operand(val expr: Expr?) {
        /**
         * A value: copied into a typed temporary when it must be ordered (`const`, or a plain
         * `T t0_` when [mutable]: a receiver a member-style binding calls a non-const method on).
         */
        class Value(expr: Expr?, val mutable: Boolean = false, val emit: () -> CppEx) : Operand(expr)

        /**
         * A place: [parts] are the values along its path, in source order, and [build] applies
         * the path to their texts. A place with no parts is a variable, `this` or a field of
         * `this`: [build] spells it and nothing is ordered. [mode] says how it is ordered. A
         * place with no expression of its own (an implicit `this`) carries its [type] and the
         * [rank] of reading it.
         */
        class Place(
            expr: Expr?,
            val parts: List<Operand>,
            val mode: PlaceMode = PlaceMode.PATH,
            val type: KType? = null,
            val rank: Int? = null,
            val build: (List<CppEx>) -> CppEx,
        ) : Operand(expr)

        /**
         * A call whose result is second-class, as an operand (the class KDoc, E2 and E3):
         * [parts] are its receiver and arguments, [build] makes the call over their texts,
         * [rank] is the call's own, apart from its parts (copied after them when it is not
         * PURE), and [force] says the call names a part twice ([lower]'s `force`).
         */
        class Chain(expr: Expr, val parts: List<Operand>, val rank: Int, val force: Boolean, val build: (List<CppEx>) -> CppEx) : Operand(expr)
    }

    /** The call [chain] is turning into a [Operand.Chain]: its own [lower] hands over its operands instead of lowering them. */
    private class Capture(val node: Expr) {
        var parts: List<Operand>? = null
        var force = false
        var build: ((List<CppEx>) -> CppEx)? = null
    }

    private var capturing: Capture? = null

    /**
     * [build] over the operands' texts, spilling them first when D33 needs it, or when [force]
     * says the parts of a place among them must be computed exactly once (a compound
     * assignment that names its target twice, a binding that repeats `{self}`). [result] types
     * the IIFE. [node] is the call [build] writes, when it is one: a [chain] of it takes the
     * operands here instead.
     */
    fun lower(
        ops: List<Operand>,
        result: KType,
        force: Boolean = false,
        node: Expr? = null,
        build: (List<CppEx>) -> CppEx,
    ): CppEx {
        val capture = capturing
        if (capture != null && node != null && capture.node === node && capture.build == null) {
            capture.parts = ops
            capture.force = force
            capture.build = build
            return CppEx("", CppPrec.PRIMARY)
        }
        val written = writtenBy(ops)
        if (!force && !forced(ops) && !needsSpill(ops, written)) {
            return build(ops.map { text(it) })
        }
        return spill(ops, result, written, node, build)
    }

    /**
     * [e], a call whose result is second-class, as a [Operand.Chain] (the class KDoc): [emit]
     * spells it, and the call's own [lower], at [node] ([callNode]), hands over its operands.
     * A call that reaches no [lower] of [node] (a literal's view, `kira::lit`, or a call
     * refused by name) is a value of [emit]'s text.
     */
    fun chain(e: Expr, node: Expr, emit: () -> CppEx): Operand {
        val saved = capturing
        val capture = Capture(node)
        capturing = capture
        val text = try {
            emit()
        } finally {
            capturing = saved
        }
        val build = capture.build ?: return Operand.Value(e) { text }
        return Operand.Chain(e, capture.parts.orEmpty(), ownRank(node), capture.force, build)
    }

    /** The call node [lower] is given for [e] (the call of `a.f()` is `f()`), or null when [e] is no call. */
    fun callNode(e: Expr): Expr? = when (e) {
        is FunctionCallExpr -> e.takeIf { lower.model.call(it) != null }
        is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.takeIf { lower.model.call(it) != null }
        else -> e.takeIf { lower.model.opCall(it) != null }
    }

    /** The text of [op] where nothing needs ordering: a value as written, a place as its path over its parts' texts, a chain's call over its parts'. */
    fun text(op: Operand): CppEx = when (op) {
        is Operand.Value -> op.emit()
        is Operand.Place -> op.build(op.parts.map { text(it) })
        is Operand.Chain -> op.build(op.parts.map { text(it) })
    }

    /**
     * Whether one leaf is [IMPURE] and another is not [PURE] (design R19, D33; `Effect`). A
     * snapshot or lent place is a leaf of its own; a written place is its parts' leaves; a
     * chain its parts' and its own call's. [written] is [writtenBy] of the operands.
     */
    fun needsSpill(ops: List<Operand>, written: Set<Symbol> = writtenBy(ops)): Boolean {
        val ranks = leaves(ops, written)
        return ranks.any { it == IMPURE } && ranks.count { it != PURE } >= 2
    }

    /** Whether a chain among [ops], at any depth, names an impure part twice ([Operand.Chain.force]): it must be spilled. */
    private fun forced(ops: List<Operand>): Boolean = ops.any { op ->
        when (op) {
            is Operand.Value -> false
            is Operand.Place -> forced(op.parts)
            is Operand.Chain -> op.force || forced(op.parts)
        }
    }

    /** The rank of [op]: a value's evaluation, the highest of a place's parts (how its location is found), a chain's call and parts. */
    fun rankOf(op: Operand, written: Set<Symbol> = emptySet()): Int = when (op) {
        is Operand.Value -> op.expr?.let { rank(it, written) } ?: PURE
        is Operand.Place -> op.parts.maxOfOrNull { rankOf(it, written) } ?: PURE
        is Operand.Chain -> maxOf(op.rank, op.parts.maxOfOrNull { rankOf(it, written) } ?: PURE)
    }

    /** The rank of reading the snapshot or lent place [op] as a value. */
    private fun readRank(op: Operand.Place, written: Set<Symbol>): Int = op.rank ?: op.expr?.let { rank(it, written) } ?: PURE

    private fun leaves(ops: List<Operand>, written: Set<Symbol>): List<Int> = ops.flatMap { op ->
        when (op) {
            is Operand.Value -> listOf(rankOf(op, written))
            is Operand.Place -> when (op.mode) {
                PlaceMode.PATH -> leaves(op.parts, written)
                PlaceMode.BOUND -> leaves(op.parts, written) + stepRanks(op, written)
                PlaceMode.SNAPSHOT, PlaceMode.LENT -> listOf(readRank(op, written))
            }
            is Operand.Chain -> leaves(op.parts, written) + op.rank
        }
    }

    /**
     * The rank of reading each container the place [op] steps into an element of, where that
     * container reallocates its elements (a `List`, a `Map`: [reallocates]): a reference
     * bound to the element dangles once a sibling moves it. An `Arr`'s elements never move,
     * and a local no sibling reaches is PURE.
     */
    private fun stepRanks(op: Operand.Place, written: Set<Symbol>): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(p: Operand.Place) {
            val e = p.expr
            if (e is ArrayIndexExpr && lower.model.typeOrNull(e.originExpr)?.let { reallocates(it) } == true) {
                out += rank(e.originExpr, written)
            }
            p.parts.forEach { if (it is Operand.Place) walk(it) }
        }
        walk(op)
        return out
    }

    /** The highest of [stepRanks] of [op] (PURE for a value or a place with no such step). */
    fun stepRank(op: Operand, written: Set<Symbol> = emptySet()): Int =
        (op as? Operand.Place)?.let { stepRanks(it, written).maxOrNull() } ?: PURE

    private fun spill(ops: List<Operand>, result: KType, written: Set<Symbol>, node: Expr?, build: (List<CppEx>) -> CppEx): CppEx = lower.state.block {
        if (node != null && isSecondClass(result) && viewsTemporary(node)) {
            // E3: the lambda would return a view into its own temporary. A chain as an operand
            // never opens one (its root does); a return, a loop or a local holding one is
            // refused before this; what is left is a branch of an if-expression.
            unsupportedView(node, "a view of a temporary as an if-expression's branch whose operands are ordered (D33): a lambda would return it past its owner (call it in an if statement instead)")
        }
        val lines = mutableListOf<String>()
        val texts = ops.map { ordered(it, lines, written) }
        val final = build(texts)
        val void = result == KType.Void || result == KType.Never
        lines += if (void) "${final.text};" else "return ${final.text};"
        iife(result, lines)
    }

    /**
     * The text of [op] once its evaluation is ordered, the declarations that order it appended
     * to [lines]: a value that is not PURE is copied into a typed temporary, a place is its path
     * over its ordered parts, and a snapshot place is that path copied. A lent place is its
     * path, applied where the call uses it, after every sibling. A chain is its call over its
     * ordered parts, copied after them when its own call is not PURE; then every owner among
     * its parts ([keep]: a value of any type [mayKeepAlive] says may hold what the view points
     * into, at any depth) is copied too, even a PURE one (`kira::List<std::int32_t>{5, 6, 7}`,
     * or `Wrap{...}` holding the only `Ref` to the list its `items()` views), since the view
     * named after them points into it or through it, and a temporary of the declaration would
     * die with it (E2). Called
     * inside a [CppBodyState.block] that an [iife] closes; [written] is [writtenBy] of the
     * operands.
     */
    fun ordered(op: Operand, lines: MutableList<String>, written: Set<Symbol> = emptySet(), keep: Boolean = false): CppEx = when (op) {
        is Operand.Place -> {
            // The parts first, in source order; a snapshot copies the path over them.
            val texts = op.parts.map { ordered(it, lines, written, keep) }
            if (op.mode == PlaceMode.SNAPSHOT) copied(op.expr, false, { op.build(texts) }, lines, written, op.type, op.rank) else op.build(texts)
        }
        is Operand.Value -> {
            val e = op.expr
            if (e != null && isSecondClass(lower.model.typeOrNull(e)) && rank(e, written) != PURE && viewsTemporary(e)) {
                // E4: a `?:` that views a temporary in a branch would be copied into a
                // kira::View whose owner dies with the declaration.
                unsupportedView(e, "a view of a temporary in an if-expression's branch, ordered against a sibling (D33) (call it in an if statement instead, or pass the if-expression where nothing beside it has an effect)")
            }
            val owner = keep && e != null && lower.model.typeOrNull(e)?.let { mayKeepAlive(it) } == true
            copied(e, op.mutable, op.emit, lines, written, rank = if (owner) IMPURE else null)
        }
        is Operand.Chain -> {
            val named = op.rank != PURE
            val texts = op.parts.map { ordered(it, lines, written, keep || named) }
            copied(op.expr, false, { op.build(texts) }, lines, written, rank = op.rank)
        }
    }

    /** [emit] copied into a typed temporary declared in [lines] when [e] (of [type], at [rank]) is not PURE, else its text as written. */
    private fun copied(
        e: Expr?,
        mutable: Boolean,
        emit: () -> CppEx,
        lines: MutableList<String>,
        written: Set<Symbol>,
        type: KType? = null,
        rank: Int? = null,
    ): CppEx {
        val t = type ?: e?.let { lower.model.typeOrNull(it) }
        val r = rank ?: e?.let { rank(it, written) } ?: PURE
        if (r == PURE || t == null || t == KType.Void || t == KType.Never) {
            return emit()
        }
        val name = lower.state.fresh("t")
        val init = emit()
        lines += "${if (mutable) "" else "const "}${lower.ctx.spell(t, Pos.VALUE, e)} $name = ${lower.wrap(init, CppPrec.ASSIGN)};"
        return CppEx(name, CppPrec.PRIMARY)
    }

    /** [lines] (statements, the last a `return` unless [result] is Void) as an immediately invoked lambda. */
    fun iife(result: KType, lines: List<String>): CppEx {
        val capture = if (lower.state.inBody) "[&]" else "[]"
        val ret = lower.ctx.spell(if (result == KType.Never) KType.Void else result, Pos.RETURN)
        return CppEx("$capture() -> $ret\n{\n${indent(lines)}\n}()", CppPrec.POSTFIX)
    }

    /** The rank of the call [node] itself, apart from its operands: PURE for a binding or a function EffectsPass calls pure (a view it returns reads no element), else IMPURE. */
    private fun ownRank(node: Expr): Int {
        val rc = (node as? FunctionCallExpr)?.let { lower.model.call(it) } ?: lower.model.opCall(node) ?: return IMPURE
        val fn = rc.fn ?: return IMPURE
        return when (rc.kind) {
            CallKind.MAGIC -> if (!handsMutView(rc) && lower.bindingFor(fn, rc.receiver?.let { lower.model.typeOrNull(it) })?.second?.pure == true) PURE else IMPURE
            else -> if (provedPure(rc)) PURE else IMPURE
        }
    }

    // ---- views: what the lowering must keep alive (design 30, 2.3 and 3.4) ----------------------

    /**
     * Whether the second-class value [e] may point into storage a C++ temporary of its own
     * expression owns: design 30's TEMP origin (2.3), a view of a call's result, a construction,
     * an interpolated `Str` or a `Str` constant made a `kira::Str`, or of a place such a value
     * owns ([freshRoot]: `makeRef().value`). A view parameter, a view of a variable's storage and
     * a literal's view (`kira::lit`) are not. A call's second-class result points only into its
     * receiver and its second-class arguments (the return rule, design 30 2.2), so only those
     * are followed.
     */
    fun viewsTemporary(e: Expr): Boolean = owners(e).any { ownedByTemporary(it) }

    /**
     * Whether the second-class value [e] may point into storage the return rule (design 30 2.2)
     * keeps a returned view from, other than a temporary's: a local's (`xs.view()` of a local
     * `xs`, freed as the function returns) or a parameter's that is no view (`xs.from(at)` with
     * `xs: List<Int32>`, which may be bound to the caller's temporary: `tail(makeList(), 2)`
     * returned that way printed 1009792083 on gcc where clang and msvc printed 13300). A view
     * parameter's, the receiver's and a literal's are allowed.
     */
    fun viewsForeignStorage(e: Expr): Boolean = owners(e).any { x ->
        when (val sym = if (lower.isPlaceExpr(x)) rootSymbol(x) else null) {
            is LocalSymbol -> true
            is ParamSymbol -> !isSecondClass(sym.type)
            else -> false
        }
    }

    /**
     * What the second-class value [e] may point into: each value or place a view is formed of
     * (converted, or the first-class receiver of a call whose result is second-class), and each
     * view that is no call (a view parameter), through the receivers and view arguments of its
     * calls. A literal's view (`kira::lit`) points into none.
     */
    private fun owners(e: Expr): List<Expr> {
        val model = lower.model
        val c = model.coercion(e)
        if (c is Coercion.ToView && !isSecondClass(c.from)) {
            return listOf(e)
        }
        if (!isSecondClass(model.typeOrNull(e))) {
            return emptyList()
        }
        if (e is IfExpr) {
            return listOfNotNull(lower.branchValue(e.thenBranch), lower.branchValue(e.elseBranch)).flatMap { owners(it) }
        }
        val rc = callOf(e) ?: return listOf(e)
        if (lower.isLiteralView(rc)) {
            return emptyList()
        }
        val out = mutableListOf<Expr>()
        rc.receiver?.let { r -> if (isSecondClass(model.typeOrNull(r))) out += owners(r) else out += r }
        rc.args.forEach { a ->
            if (a is ArgBinding.Given && (isSecondClass(model.typeOrNull(a.expr)) || model.coercion(a.expr) is Coercion.ToView)) {
                out += owners(a.expr)
            }
        }
        return out
    }

    /** Whether the storage of [x], an owner a view is formed of, is a temporary's ([isTemporary]); `this` and a literal are not. */
    private fun ownedByTemporary(x: Expr): Boolean = x !is ThisExpr && x !is StringLiteral && isTemporary(x)

    private fun callOf(e: Expr): ResolvedCall? = when (e) {
        is FunctionCallExpr -> lower.model.call(e)
        is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { lower.model.call(it) }
        else -> lower.model.opCall(e)
    }

    /**
     * The fresh value that owns the storage of the place [x], or null when [x] is found through
     * a variable, a parameter, a global or `this`. The typer records a field of any value as a
     * place (`Place.Field` with no receiver), so `makeRef().value`, `makeMaybe().value` and
     * `makeBag().items[0]` are places, whose storage a temporary owns: the `kira::Rc` of the
     * fresh `Ref`, the fresh `kira::Maybe`, the fresh struct, each destroyed at the end of the
     * full expression. The walk goes up the fields and elements of the path; the first step
     * that is no place is that value. A step through a view (or an `Unsafe` or a `Weak`) ends
     * the walk with null: a view points wherever it was made, not into the value that holds it.
     * A handle (a `Ref`, a class object) is not such a step: the fresh handle may be the only
     * owner of its object, which is taken to die with it.
     */
    fun freshRoot(x: Expr): Expr? {
        val model = lower.model
        var cur = x
        while (true) {
            val next = when (cur) {
                is MemberAccessExpr -> if (model.member(cur) is MemberRef.Field) cur.origin else return null
                is ArrayIndexExpr -> cur.originExpr
                else -> return null
            }
            if (CppBindingTable.magicName(model.typeOrNull(next)) in NON_OWNING) {
                return null
            }
            if (!lower.isPlaceExpr(next)) {
                return next
            }
            cur = next
        }
    }

    /**
     * Whether [x], an operand that owns or reaches storage, is a temporary that dies with its
     * full expression: a fresh value, a copy the lowering makes of a place (a `Str` constant's
     * `kira::Str`, the `kira::Maybe` a `WrapSome` place becomes), or a place whose storage a
     * fresh value owns ([freshRoot]).
     */
    private fun isTemporary(x: Expr): Boolean = !lower.isPlaceExpr(x) || lower.isCopiedPlace(x) || freshRoot(x) != null

    /**
     * Whether the C++ of the `for` range [e] may be a reference into a temporary. `for(x : r)`
     * binds `r` to a reference, which keeps a temporary alive only when `r` is that temporary
     * itself, and never one `r` is found inside: `kira::at(makeLists(), 0)` and
     * `makeRef()->value` both dangled before the first step (gcc's `-Wdangling-reference`,
     * MSVC's ASan a heap-use-after-free). A place found through a variable, a parameter, a
     * global or `this` is no such reference, and neither is a value C++ returns by value (a
     * user function's result, a construction, a literal). A place whose object a fresh value
     * owns ([freshRoot]), an element of a fresh value, and a stdlib call given a temporary (its
     * binding may return a reference into it: `kira::at`, `Result.unwrap`) may be; so may
     * anything else. This is C++'s range-for trap before C++23, not a view rule: a view range
     * has only a view parameter's or a literal's storage (design 30 2.1, RANGE). The loop copies
     * such a range in its own range expression, while the temporary lives (CppStmtEmitter),
     * which is what Kira's value semantics read.
     */
    fun rangeMayDangle(e: Expr): Boolean {
        val model = lower.model
        if (lower.isPlaceExpr(e)) {
            return freshRoot(e) != null
        }
        fun call(rc: ResolvedCall): Boolean = when (rc.kind) {
            CallKind.MAGIC -> (listOfNotNull(rc.receiver) + rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }).any { x ->
                val xt = model.typeOrNull(x)
                xt == null || (mayKeepAlive(xt) && isTemporary(x))
            }
            else -> false
        }
        return when (e) {
            is FunctionCallExpr -> model.call(e)?.let { call(it) } ?: true
            is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { c -> model.call(c)?.let { call(it) } } ?: true
            is ObjectInitExpr, is ArrayLiteral, is InterpolatedStringLiteral, is StringLiteral -> false
            is IfExpr -> listOfNotNull(lower.branchValue(e.thenBranch), lower.branchValue(e.elseBranch)).any { rangeMayDangle(it) }
            else -> model.opCall(e)?.let { call(it) } ?: true
        }
    }

    // ---- what the emitter reports --------------------------------------------------------------

    /** The nodes already reported, so one construct is reported once. */
    private val reported: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * Reports `cpp.internal` at [node] (once): [what] is a second-class value where design 30's
     * rule allows none. ViewPass (W2.5) refuses it first, as `rules.view.[code]`, so reaching
     * the emitter is a checker's bug, never a program to lower.
     */
    fun internalView(node: ASTNode, what: String, code: String) {
        if (reported.add(node)) {
            lower.ctx.diag(
                node,
                CppModuleEmitterFactory.INTERNAL_CODE,
                "$what, and a view is only ever an argument, a receiver or a return (decision 4b): ViewPass refuses this as rules.view.$code before the emitter runs",
            )
        }
    }

    /** Reports `cpp.unsupported` at [node] (once): a lowering of a view the rule allows that is not made yet ([construct]). */
    fun unsupportedView(node: ASTNode, construct: String) {
        if (reported.add(node)) {
            lower.ctx.unsupported(node, construct)
        }
    }

    /** Reports `cpp.unsupported` at [node] (once) with [message]: an order D33 needs that no lowering gives (a StrBuf piece's receiver bound before its hole). */
    fun refuseOrder(node: ASTNode, message: String) {
        if (reported.add(node)) {
            lower.ctx.diag(node, CppModuleEmitterFactory.UNSUPPORTED_CODE, message)
        }
    }

    // ---- ranks ---------------------------------------------------------------------------------

    /**
     * [PURE], [READS] or [IMPURE] for evaluating [e]: IMPURE where the model says so, else
     * [scan]'s answer (a model's PURE says the evaluation has no effect, which is not that it
     * reads nothing a sibling changes: EffectsPass calls a function that reads a global pure);
     * a PURE read of a local a sibling writes ([written]) is READS.
     */
    fun rank(e: Expr, written: Set<Symbol> = emptySet()): Int {
        val base = if (lower.model.effects[e] == Effect.IMPURE) IMPURE else scan(e)
        return if (base == PURE && written.isNotEmpty() && readsAny(e, written)) READS else base
    }

    /**
     * The variables the operands [ops] write while they are evaluated, outside any lambda: the
     * root of a call's `mut` argument, of a `MutView` a call is handed (not converted to a
     * `View`: `writeU32(p.from(4), v)` writes `p`, the named write of design 30 3.2), of the
     * receiver of a `mut fx`, and of an assignment's target, at any depth. The operands' own
     * call writes nothing while they are evaluated (its callee runs after), so a written place
     * among [ops] adds nothing. A `MutView` is never held (decision 4b), so none is lent from a
     * variable anywhere but at the call it is handed to.
     */
    fun writtenBy(ops: List<Operand>): Set<Symbol> {
        val out: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
        fun collect(op: Operand) {
            op.expr?.let { writes(it, out) }
            when (op) {
                is Operand.Place -> op.parts.forEach { collect(it) }
                is Operand.Chain -> op.parts.forEach { collect(it) }
                is Operand.Value -> {}
            }
        }
        ops.forEach { collect(it) }
        return out
    }

    /** Adds to [out] what [root] writes ([writtenBy]). */
    private fun writes(root: Expr, out: MutableSet<Symbol>) {
        val model = lower.model
        fun through(e: Expr) {
            val lentFrom = lentMutView(e)
            if (lentFrom != null) {
                // `p.from(4)` handed on writes p.
                through(lentFrom)
                return
            }
            rootSymbol(e)?.let { out += it }
        }
        fun call(rc: ResolvedCall) {
            rc.args.forEach { a ->
                if (a !is ArgBinding.Given) {
                    return@forEach
                }
                val handedMutView = CppBindingTable.magicName(model.typeOrNull(a.expr)) == "MutView" && model.coercion(a.expr) !is Coercion.ToView
                if (a.byRef || handedMutView) {
                    through(a.expr)
                }
            }
            val receiver = rc.receiver ?: return
            if (rc.fn?.isMutMethod == true) {
                through(receiver)
            }
        }
        outsideLambdas(root) { node ->
            when (node) {
                is FunctionCallExpr -> model.call(node)?.let { call(it) }
                is AssignmentExpr -> through(node.target)
                is PlaceAssignmentExpr -> through(node.target)
                is CompoundAssignmentExpr -> through(node.left)
                is Expr -> model.opCalls[node]?.let { call(it) }
                else -> {}
            }
        }
    }

    /** Every node under [root], [root] included, except inside a lambda (its body is not evaluated where it is written). */
    private fun outsideLambdas(root: ASTNode, visit: (ASTNode) -> Unit) {
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        val seen = IdentityHashMap<ASTNode, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null || node is LambdaExpr) {
                continue
            }
            visit(node)
            AstTree.children(node).forEach { stack.addLast(it) }
        }
    }

    /** The receiver a call lends the `MutView` [e] from (`p` for `p.from(4)`, `v` for `v.from(4)`), or null when [e] is no such call. */
    private fun lentMutView(e: Expr): Expr? {
        val call = when (e) {
            is FunctionCallExpr -> e
            is MemberAccessExpr -> e.member as? FunctionCallExpr
            else -> null
        } ?: return null
        return lower.model.call(call)?.takeIf { CppBindingTable.magicName(it.returnType) == "MutView" }?.receiver
    }

    /** The variable a place [e] is found through (`xs` for `xs[i].v`), or null. */
    private fun rootSymbol(e: Expr): Symbol? = when (e) {
        is Identifier -> lower.model.symbolOf(e)
        is MemberAccessExpr -> if (lower.model.member(e) is MemberRef.Field) rootSymbol(e.origin) else null
        is ArrayIndexExpr -> rootSymbol(e.originExpr)
        else -> null
    }

    /** Whether [e] reads one of [symbols], outside any lambda. */
    private fun readsAny(e: Expr, symbols: Set<Symbol>): Boolean {
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(e)
        val seen = IdentityHashMap<ASTNode, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null || node is LambdaExpr) {
                continue
            }
            if (node is Identifier && lower.model.symbolOf(node)?.let { it in symbols } == true) {
                return true
            }
            AstTree.children(node).forEach { stack.addLast(it) }
        }
        return false
    }

    private fun rankOf(effect: Effect): Int = when (effect) {
        Effect.PURE -> PURE
        Effect.IMPURE -> IMPURE
        else -> READS
    }

    /**
     * The rank of [root] (the class KDoc): IMPURE at a call the model or the binding table does
     * not call pure, an assignment, a `throw`, a `try` or a trace; READS at a read of state a
     * sibling's effect could change, which includes a pure call that reads through a view or a
     * handle it is given (`v.get(0)`, `readU16Le(pkt, 0)`: the storage behind it is shared,
     * though the name is a parameter). Taking a view (a pure call whose result is one,
     * `p.from(4)`) reads no element.
     */
    private fun scan(root: Expr): Int {
        var best = PURE
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        val seen = IdentityHashMap<ASTNode, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen.put(node, true) != null) {
                continue
            }
            // The model's IMPURE is the answer; its PURE says only that there is no effect, and
            // the reads below are still looked for.
            if (node !== root && node is Expr && lower.model.effects[node] == Effect.IMPURE) {
                return IMPURE
            }
            when (node) {
                is LambdaExpr -> continue
                is FunctionCallExpr -> {
                    if (!isPureCall(node)) return IMPURE
                    lower.model.call(node)?.let { rc ->
                        if (!isSecondClass(rc.returnType) && readsThrough(rc)) {
                            best = maxOf(best, READS)
                        }
                    }
                }
                is IntrinsicExpr -> if (node.intrinsicKey.name == "_trace_") return IMPURE
                is ThrowExpr, is TryExpr, is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr -> return IMPURE
                is ObjectInitExpr -> {
                    val own = constructionRank(node)
                    if (own == IMPURE) return IMPURE
                    best = maxOf(best, own)
                }
                is Identifier -> if (readsShared(node)) best = READS
                // A struct's `this` is the receiver C++ holds by reference: a read of it as a
                // value (`peek(this, bump())`) is shared state a sibling `mut fx` changes. A
                // class's `this` is a handle, whose identity no effect changes.
                is ThisExpr -> if (lower.model.typeOrNull(node)?.let { lower.heldByReference(it) } == true) best = READS
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
                is ArrayIndexExpr -> if (isView(lower.model.typeOrNull(node.originExpr))) best = READS
                is Expr -> lower.model.opCalls[node]?.let { rc ->
                    if (rc.fn != null && !provedPure(rc)) return IMPURE
                    if (!isSecondClass(rc.returnType) && readsThrough(rc)) best = maxOf(best, READS)
                }
                else -> {}
            }
            AstTree.children(node).forEach { stack.addLast(it) }
        }
        return best
    }

    /**
     * Whether the pure call [rc] reads through a view or a handle among its operands (`v.get(0)`,
     * `readU16Le(pkt, 0)`, a pure method on a class): what it reads is storage a sibling may
     * write, whatever variable names the view.
     */
    private fun readsThrough(rc: ResolvedCall): Boolean {
        val operands = listOfNotNull(rc.receiver) + rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }
        return operands.any { x ->
            val t = lower.model.typeOrNull(x) ?: return@any false
            val converted = lower.model.coercion(x) as? Coercion.ToView
            isSecondClass(t) || lower.isPointerLike(t) || (converted != null && isSecondClass(converted.from))
        }
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

    private fun isPureCall(c: FunctionCallExpr): Boolean {
        val rc = lower.model.call(c)
        if (rc != null && (rc.kind == CallKind.VIRTUAL || rc.kind == CallKind.TRAIT || rc.kind == CallKind.FN_VALUE || rc.kind == CallKind.EXTERN || handsMutView(rc))) {
            // It may run any body, or writes through what it is handed ([provedPure]), whatever the model's entry says.
            return false
        }
        lower.model.effects[c]?.let { return rankOf(it) == PURE }
        rc ?: return false
        val fn = rc.fn ?: return false
        return when (rc.kind) {
            CallKind.MAGIC -> lower.bindingFor(fn, rc.receiver?.let { lower.model.typeOrNull(it) })?.second?.pure == true
            else -> provedPure(rc)
        }
    }

    /**
     * Whether the call [rc] of a user function is proved pure: EffectsPass calls its callee
     * PURE, the callee has a body, the call runs that body and no other, and it is handed no
     * `MutView` ([handsMutView]). A call through a vtable, a trait or an `Fx` value, an extern,
     * a bodiless prototype and a construction through call syntax may run any body, so each is
     * impure whatever a table says (decision 4b, read literally: what is not proved pure is
     * impure).
     */
    private fun provedPure(rc: ResolvedCall): Boolean {
        val fn = rc.fn ?: return false
        return (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD || rc.kind == CallKind.OP_OVERLOAD) &&
            fn.body != null && !fn.isVirtual && fn.foreign == null && !handsMutView(rc) && lower.model.effect(fn) == Effect.PURE
    }

    /**
     * Whether the call [rc] is handed a `MutView` (not one converted to a `View`), or its callee
     * declares a `MutView` parameter: the callee may write through it, and a write through a
     * `MutView` of a place is a write to that place (decision 4b, read literally), which an
     * EffectsPass that counts only its own writes would call pure (`sub(p[0], zero(p.from(0)))`
     * left unordered printed 8 on gcc and MSVC, where Kira gives 1 - 1 = 0).
     */
    private fun handsMutView(rc: ResolvedCall): Boolean =
        rc.fn?.params?.any { CppBindingTable.magicName(it.type) == "MutView" } == true ||
            rc.args.any { a -> a is ArgBinding.Given && CppBindingTable.magicName(lower.model.typeOrNull(a.expr)) == "MutView" && lower.model.coercion(a.expr) !is Coercion.ToView }

    /** The classes whose construction [constructionRank] is scanning, so a default that constructs its own class ends the walk (IMPURE). */
    private val constructing: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * The rank of what the construction [e] runs beyond the fields it writes: IMPURE when it is
     * not resolved, or when its class or a superclass has an `initially` or a `finally` block
     * (a temporary's is run where the full expression drops it); else the highest [scan] of the
     * defaults of the fields it leaves out, which C++ evaluates at the construction (a struct's
     * default member initializer, a class constructor's default argument).
     */
    private fun constructionRank(e: ObjectInitExpr): Int {
        val ri = lower.model.init(e) ?: return IMPURE
        val cls = ri.cls ?: return IMPURE
        if (!constructing.add(cls)) {
            return IMPURE
        }
        try {
            var c: ClassSymbol? = cls
            val seen: MutableSet<ClassSymbol> = Collections.newSetFromMap(IdentityHashMap())
            while (c != null && seen.add(c)) {
                if (c.initially != null || c.finally != null) {
                    return IMPURE
                }
                c = c.superclass?.sym as? ClassSymbol
            }
            var best = PURE
            ri.fields.forEach { f ->
                if (f is FieldInit.Default) {
                    val d = f.field.default ?: return@forEach
                    val r = scan(d)
                    if (r == IMPURE) {
                        return IMPURE
                    }
                    best = maxOf(best, r)
                }
            }
            return best
        } finally {
            constructing.remove(cls)
        }
    }

    companion object {
        const val PURE = 0
        const val READS = 1
        const val IMPURE = 2

        /** The second-class types (design 30, 1.1): a value of one points into storage it does not own. */
        private val SECOND_CLASS = setOf("View", "MutView", "CStr", "Unsafe")

        /** The magic classes whose value points at storage it does not own: a step through one ends [freshRoot]'s walk. */
        private val NON_OWNING = setOf("View", "MutView", "Unsafe", "Weak")

        /** The magic classes whose value is a pointer to storage elsewhere: a copy points where the original did. */
        private val POINTERS = setOf("View", "MutView", "CStr", "Ref", "Weak", "Unsafe")

        /** The magic classes whose value owns storage a view can be taken into. */
        private val OWNERS = setOf("StrBuf", "Arr", "List", "Map", "Set", "Stack", "Queue", "Deque")

        private val REALLOCATING = setOf("List", "Map", "Set", "Stack", "Queue", "Deque")

        /** Whether [t] is second-class: a `View`, `MutView`, `CStr` or `Unsafe` (design 30, 1.1). */
        fun isSecondClass(t: KType?): Boolean = CppBindingTable.magicName(t) in SECOND_CLASS

        /** Whether [t] is a `View` or a `MutView`. */
        fun isView(t: KType?): Boolean = CppBindingTable.magicName(t).let { it == "View" || it == "MutView" }

        /**
         * Whether [t] holds a second-class type without being one (design 30, 1.2, where it is
         * ill-formed): as a type argument of anything but an `Fx` signature (`Maybe<View<Char>>`,
         * `List<CStr>`, `Ref<View<Int32>>`) or as a field of a class, at any depth.
         */
        fun holdsSecondClass(t: KType): Boolean = holds(t, Collections.newSetFromMap(IdentityHashMap()))

        private fun holds(t: KType, seen: MutableSet<ClassSymbol>): Boolean {
            val n = t as? KType.Nominal ?: return false
            val sym = n.sym as? ClassSymbol ?: return false
            if (!seen.add(sym)) {
                return false
            }
            val inArgs = n.typeArgs().any { isSecondClass(it) || holds(it, seen) }
            if (inArgs || sym.kind == ClassKind.MAGIC) {
                seen.remove(sym)
                return inArgs
            }
            val substitution = sym.typeParams.zip(n.typeArgs()).toMap()
            val inFields = sym.fields.any { f -> f.type.substitute(substitution).let { isSecondClass(it) || holds(it, seen) } } ||
                sym.superclass?.let { holds(it.substitute(substitution), seen) } == true
            seen.remove(sym)
            return inFields
        }

        /**
         * Whether a copy of a value of type [t] owns storage a view can be taken into: a `Str`,
         * a `StrBuf`, an `Arr`, a `List`, `Map`, `Set`, `Stack`, `Queue` or `Deque`, a `Maybe`,
         * tuple or `Result` holding one, a struct with such a field at any depth, or a type
         * parameter (unknown). A scalar, an enum, a view, a pointer, a class or trait handle and
         * an `Fx` value (whose captures are its own) are not.
         */
        fun ownsStorage(t: KType): Boolean = owns(t, Collections.newSetFromMap(IdentityHashMap()))

        private fun owns(t: KType, seen: MutableSet<ClassSymbol>): Boolean = when (t) {
            KType.Str, is KType.Param -> true
            is KType.Fn -> false
            is KType.Nominal -> when (val sym = t.sym) {
                is ClassSymbol -> when (sym.kind) {
                    ClassKind.CLASS, ClassKind.OPAQUE -> false
                    ClassKind.STRUCT -> if (!seen.add(sym)) {
                        false
                    } else {
                        val substitution = sym.typeParams.zip(t.typeArgs()).toMap()
                        sym.fields.any { owns(it.type.substitute(substitution), seen) } ||
                            sym.superclass?.let { owns(it.substitute(substitution), seen) } == true
                    }
                    ClassKind.MAGIC -> when (sym.name) {
                        in POINTERS, "Fx" -> false
                        in OWNERS -> true
                        else -> t.typeArgs().any { owns(it, seen) }
                    }
                }
                else -> false
            }
            else -> false
        }

        /**
         * Whether a fresh value of type [t] may be what keeps alive the storage a view formed
         * from it points into, directly or through a handle it holds (E2): every type but a
         * scalar, an enum and a second-class value (a view points wherever it was made, and a
         * copy of it keeps nothing alive). A value class (a struct, and after W2.9 an immutable
         * class) counts whatever its fields: `Wrap{.r = std::make_shared<...>(...)}` holds the
         * only `Ref` to the list its `items()` returns a view of, though [ownsStorage] finds no
         * storage in it (a `Ref`, class or trait field owns none by value), and the view copied
         * out of the declaration that made it printed 823559285 on gcc where Kira gives 3601 (a
         * heap-use-after-free under MSVC's ASan). Deciding which fields may reach storage is the
         * provenance analysis decision 4b avoids; anything not proved inert is an owner.
         */
        fun mayKeepAlive(t: KType): Boolean = when (t) {
            is KType.Scalar, KType.Void, KType.Never, KType.NullT, KType.Error -> false
            is KType.Nominal -> t.sym !is EnumSymbol && !isSecondClass(t)
            KType.Str, is KType.Param, is KType.Fn -> true
        }

        /**
         * Whether a container of type [t] moves its elements when it grows or is rebuilt, so a
         * reference bound to one of them dangles after a sibling's effect: a `List`, a `Map`, a
         * `Set`, a `Stack`, a `Queue`, a `Deque`, a `Str`, or a type parameter (unknown). An
         * `Arr` keeps its elements in place, and so does a `StrBuf`; a view owns no elements.
         */
        fun reallocates(t: KType): Boolean = when (t) {
            KType.Str, is KType.Param -> true
            is KType.Nominal -> CppBindingTable.magicName(t) in REALLOCATING
            else -> false
        }

        /** Every line of [lines] (each possibly several) indented four spaces. */
        fun indent(lines: List<String>): String =
            lines.flatMap { it.split('\n') }.joinToString("\n") { if (it.isEmpty()) it else "    $it" }
    }
}

/**
 * Whether an `Fx` parameter must be a `kira::Fn` rather than a template parameter (design 5.1).
 * `TypedModel.fxEscapes` (EscapePass, W2.5) answers where it has an entry; where it has none
 * this approximates the pass (KI-2 of docs/cpp-known-issues/w2-3-emit-exprs.md): the parameter
 * does not escape when the body mentions it only as the callee of a call
 * (`each(buf.slice(at, n))`), outside any lambda, in a non-virtual, non-trait method. A
 * parameter of a function used as a value (`g: Fx<...> = applyTo`, a `FnRef`) escapes whatever
 * the model says, since a template is not a value `kira::Fn` can hold, and so does one with a
 * default (no template argument is deduced from a default).
 */
class CppEscapes(private val model: TypedModel) {
    private val fx = IdentityHashMap<ParamSymbol, Boolean>()

    /** Every function used as a value somewhere in the program (a `FnRef` coercion). */
    private val usedAsValue: Set<FnSymbol> by lazy {
        val out: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
        model.coercions.values.forEach { if (it is Coercion.FnRef) out += it.fn }
        out
    }

    /** Whether the `Fx` parameter [p] escapes its function (the class KDoc). */
    fun fxEscapes(p: ParamSymbol): Boolean {
        val fn = p.fn
        if (p.default != null || (fn != null && fn in usedAsValue)) {
            return true
        }
        return model.fxEscapes[p] ?: fx.getOrPut(p) { scanFx(p) }
    }

    private fun scanFx(p: ParamSymbol): Boolean {
        val fn = p.fn ?: return true
        val body = fn.body ?: return true
        if (fn.isVirtual || fn.owner is TraitSymbol) {
            return true
        }
        val callees: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
        var escapes = false
        fun visit(node: ASTNode, inLambda: Boolean) {
            if (escapes) {
                return
            }
            if (node is FunctionCallExpr && !inLambda && model.call(node)?.kind == CallKind.FN_VALUE) {
                callees.add(node.name)
            }
            if (node is Identifier && model.symbolOf(node) === p && (inLambda || node !in callees)) {
                escapes = true
                return
            }
            val inner = inLambda || node is LambdaExpr
            AstTree.children(node).forEach { visit(it, inner) }
        }
        body.forEach { visit(it, false) }
        return escapes
    }
}
