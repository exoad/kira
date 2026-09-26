package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnParam
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
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
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
 *   judges' B5 flaw) or lent from by a view that outlives the call (a use after free at the
 *   end of the lambda); a reference held while a sibling reallocates the container would
 *   dangle. A variable's own identity never changes, so a plain place has no parts and stays
 *   where it is. Every `mut` argument, assignment target and receiver a method writes is a place.
 * - A value C++ passes by `const&` (a `Str`, a struct, a container, a type parameter: design
 *   5.1) read at a place is a [PlaceMode.SNAPSHOT] place: D33 reads it before a sibling's
 *   effect, and C++ would hand the callee the object as the effect left it
 *   (`show(gs, changeS())` printed the new `gs`), so where it is not PURE it is copied like a
 *   value, its path ordered first (`const kira::Str t0_ = gs;`). A `List` or `Arr` place the
 *   typer converts to a `View` for a parameter that keeps no view (`sizeOf(gl, grow())`,
 *   `sizeOf` only reading `v.size()`) is copied the same way, and the view points into the
 *   copy for the length of the call.
 * - The exception is an operand the call may lend from ([PlaceMode.LENT]): a view the callee
 *   takes into it (the `View` the typer converts it to, or one the body derives from a
 *   `const&` parameter with `from`, `slice` or `view`) can leave the call through the result
 *   (`tail(xs, nextSize())` returning `View<Int32>`, `Win { v = gl, k = nextSize() }` whose
 *   field `v` is one), through a `mut` argument or receiver it writes (`w.attach(gl,
 *   nextSize())` storing `src` in `w.v`), through a generic parameter instantiated with a
 *   view (`keep<View<Int32>>(mut h, gl, nextSize())` storing `x` in `h.v`), or through
 *   anything else it writes; a copy the view points into dies with the lambda (gcc printed
 *   garbage, MSVC's ASan a heap-use-after-free), so such an operand is never copied. It is a
 *   leaf of its own (its read rank), so an impure sibling is spilled before it, and its path
 *   is applied where the call uses it, after every sibling: the view is made of the place as
 *   the siblings' effects left it, on every compiler (a view built in the call's argument
 *   list was unsequenced against its siblings: `sizeOf(gl, grow())` printed 4 on gcc and 3
 *   on clang), and never before a sibling that reallocates what it points into. Whether a
 *   lent operand should see its sibling's effect at all is the open D33/D44 question in
 *   docs/cpp-known-issues/w2-3-emit-exprs.md; this is the documented behaviour. The decision
 *   is per operand, made where the parameter it feeds is known ([lentArgument],
 *   [lentReceiver], [CppLending.lends] for a construction's field or an assignment's target):
 *   a stdlib binding lends only through its result and its view parameters (its C++ is
 *   known), a user function through what [CppEscapes]'s scan of its body says (and escapes
 *   wherever `EscapePass`, W2.5, says so too).
 * - A written place is read by the callee, never at the call. An assignment's target
 *   ([PlaceMode.PATH]) is written after its value runs (C++17 sequences the right side of `=`
 *   first): its parts are its only leaves. A `mut` argument or the receiver a `mut fx` writes
 *   ([PlaceMode.BOUND]) is a reference C++ may bind before a sibling runs, so each element
 *   step through a container that reallocates (a `List`, a `Map`) is a leaf too, at the rank
 *   of reading that container: a sibling that may grow it is spilled first and the path is
 *   applied after it (`gll[0].add(growGll())` pushed into freed storage on every compiler).
 * - An operand's rank: IMPURE where `TypedModel.effects` (EffectsPass, W2.5) says so; else
 *   [scan]'s answer, since the model's PURE says the evaluation has no effect, not that it
 *   reads nothing a sibling writes. The scan: IMPURE for a call whose own entry is absent
 *   (absent means impure) unless the callee is a stdlib binding marked `pure: true` or a
 *   function EffectsPass proved pure, for an assignment, a `throw`, a `try` or a trace; READS
 *   for a read of a `mut` global, a parameter passed by reference (`mut`, or a struct, `Str`,
 *   container or class the design passes by `const&`), a field, an element of a view, a pure
 *   call that reads through a view or a handle (`v.get(0)`, `readU16Le(pkt, 0)`), or a local
 *   some `MutView` is lent from anywhere (a closure or a container may write through it);
 *   PURE otherwise. Taking a view reads no element. A lambda's body is not evaluated where the
 *   lambda is written, so it does not count. A place's rank is the highest of its parts'
 *   (PURE when it has none): how its location is found, not what it holds.
 * - A local no sibling can reach is PURE, except where a sibling writes it: through a `mut`
 *   argument, as the receiver of a `mut fx`, as the source of a `MutView`, or as an
 *   assignment's target. Among such operands ([writtenBy]) a read of it is READS, so it is
 *   read before the write (`sub(x, inc(mut x))` printed 14 on gcc and 4 on clang).
 * - Whether to spill is decided over the leaves: every value operand, every snapshot or lent
 *   place, every value part of a written place, at any depth (`grid[nextSize()][nextSize()]
 *   = 5` holds two impure leaves in one place and must order them), and a bound place's steps.
 * - A spill that would leave a view dangling is refused (`cpp.view-lifetime`, [checkViews]):
 *   a fresh operand the call lends from, a value holding a view into a temporary, and a view
 *   into moving storage made before a later operand's effect. So is a view into a temporary
 *   kept past its statement ([refuseTemporaryView], [refuseKeptTemporary],
 *   [refuseKeptReceiver], [refuseKeptDefault]). A temporary is also one the lowering makes
 *   where the source names none ([Source.Temporary]): the `kira::Str` a `const&` parameter
 *   binds its `Str` default as (D48), and the `kira::Maybe` copy of a place the typer wraps
 *   for a `Maybe` parameter. Storage moves when the viewed value's own type says so or when a
 *   step of its path does: an `Arr` element of a `List`, the object behind a `Ref` ([storageMoves]).
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

        /** Read as a value the call cannot lend from: copied into a typed temporary, its path first, before a sibling's effect. */
        SNAPSHOT,

        /** Read by a call that may lend from it: never copied; a leaf of its own, its path applied after every sibling. */
        LENT,
    }

    /** One operand, in source order. [expr] is null for a piece that is no expression (an interpolation's text, an implicit `this`). */
    sealed class Operand(val expr: Expr?) {
        /**
         * A value: copied into a typed temporary when it must be ordered (`const`, or a plain
         * `T t0_` when [mutable]: a receiver a member-style binding calls a non-const method on).
         * [lent] marks a fresh value that owns storage (a call's result, a construction, an
         * interpolated `Str`, a `Str` constant C++ converts to a `kira::Str`) which the call may
         * keep a view into: it cannot be ordered against a sibling, since the view would point
         * into a temporary of the ordering lambda, and [lower] refuses the call ([checkViews]).
         */
        class Value(expr: Expr?, val mutable: Boolean = false, val lent: Boolean = false, val emit: () -> CppEx) : Operand(expr)

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
    }

    private val escapes = CppEscapes(lower.model, lower::heldByReference)

    /**
     * [build] over the operands' texts, spilling them first when D33 needs it, or when [force]
     * says the parts of a place among them must be computed exactly once (a compound
     * assignment that names its target twice, a binding that repeats `{self}`). [result] types
     * the IIFE. [call] is the call [build] writes, at [node], when it is one: a default it binds
     * as a temporary would die inside the lambda ([checkDefaults]).
     */
    fun lower(
        ops: List<Operand>,
        result: KType,
        force: Boolean = false,
        call: ResolvedCall? = null,
        node: Expr? = null,
        build: (List<CppEx>) -> CppEx,
    ): CppEx {
        val written = writtenBy(ops)
        if (!force && !needsSpill(ops, written)) {
            return build(ops.map { text(it) })
        }
        if (call != null && node != null) {
            checkDefaults(call, node, result)
        }
        return spill(ops, result, written, build)
    }

    /** The text of [op] where nothing needs ordering: a value as written, a place as its path over its parts' texts. */
    fun text(op: Operand): CppEx = when (op) {
        is Operand.Value -> op.emit()
        is Operand.Place -> op.build(op.parts.map { text(it) })
    }

    /**
     * Whether one leaf is [IMPURE] and another is not [PURE] (design R19, D33; `Effect`). A
     * snapshot or lent place is a leaf of its own; a written place is its parts' leaves.
     * [written] is [writtenBy] of the operands.
     */
    fun needsSpill(ops: List<Operand>, written: Set<Symbol> = writtenBy(ops)): Boolean {
        val ranks = leaves(ops, written)
        return ranks.any { it == IMPURE } && ranks.count { it != PURE } >= 2
    }

    /** The rank of [op]: a value's evaluation, or the highest of a place's parts (how its location is found). */
    fun rankOf(op: Operand, written: Set<Symbol> = emptySet()): Int = when (op) {
        is Operand.Value -> op.expr?.let { rank(it, written) } ?: PURE
        is Operand.Place -> op.parts.maxOfOrNull { rankOf(it, written) } ?: PURE
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
        }
    }

    /**
     * The rank of reading each container the place [op] steps into an element of, where that
     * container reallocates its elements (a `List`, a `Map`: [CppLending.reallocates]): a
     * reference bound to the element dangles once a sibling moves it. An `Arr`'s elements never
     * move, and a local no sibling reaches is PURE.
     */
    private fun stepRanks(op: Operand.Place, written: Set<Symbol>): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(p: Operand.Place) {
            val e = p.expr
            if (e is ArrayIndexExpr && lower.model.typeOrNull(e.originExpr)?.let { CppLending.reallocates(it) } == true) {
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

    private fun spill(ops: List<Operand>, result: KType, written: Set<Symbol>, build: (List<CppEx>) -> CppEx): CppEx = lower.state.block {
        checkViews(ops, result, written)
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
     * path, applied where the call uses it, after every sibling. Called inside a
     * [CppBodyState.block] that an [iife] closes; [written] is [writtenBy] of the operands.
     */
    fun ordered(op: Operand, lines: MutableList<String>, written: Set<Symbol> = emptySet()): CppEx = when (op) {
        is Operand.Place -> {
            // The parts first, in source order; a snapshot copies the path over them.
            val texts = op.parts.map { ordered(it, lines, written) }
            if (op.mode == PlaceMode.SNAPSHOT) copied(op.expr, false, { op.build(texts) }, lines, written, op.type, op.rank) else op.build(texts)
        }
        is Operand.Value -> copied(op.expr, op.mutable, op.emit, lines, written)
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

    // ---- views the ordering could leave dangling -----------------------------------------------

    /** One step of a spill in evaluation order: a value (copied when not PURE) or the read of a snapshot or lent place. */
    private class Step(val op: Operand, val rank: Int)

    private fun steps(ops: List<Operand>, written: Set<Symbol>, out: MutableList<Step>) {
        ops.forEach { op ->
            when (op) {
                is Operand.Value -> out += Step(op, rankOf(op, written))
                is Operand.Place -> {
                    steps(op.parts, written, out)
                    when (op.mode) {
                        PlaceMode.SNAPSHOT, PlaceMode.LENT -> out += Step(op, readRank(op, written))
                        PlaceMode.BOUND -> stepRanks(op, written).forEach { out += Step(op, it) }
                        PlaceMode.PATH -> {}
                    }
                }
            }
        }
    }

    /**
     * Refuses, before [spill] writes it, an ordering that would leave a view dangling (the
     * convergence policy: never a use after free). Each of these is memory-safe only in one
     * full expression, which the spill's lambda splits into several:
     *
     * - a fresh operand that owns storage ([Operand.Value.lent]: `tail(makeList(), nextSize())`)
     *   the call may lend from, where its result can hold a view: the view the lambda returns
     *   would point into the lambda's own temporary (a view the callee keeps elsewhere is
     *   [refuseKeptTemporary]'s, spilled or not; one it only uses during the call is safe);
     * - a value holding a view into a temporary it makes (`tail(makeList(), 1)` as an operand
     *   of `pick`, `tailS(1)` binding its `Str` default): copied, the temporary dies with the
     *   declaration; returned out of the lambda (a [result] that can hold a view), with its
     *   statement ([checkDefaults] is the same for the call the lambda returns);
     * - a value holding a view into storage a later operand's effect may move or free (a
     *   `List`, a `Str`, anything behind a handle, an `Arr` element of a `List`:
     *   [storageMoves]), copied before that effect (`total(gl.view(), grow())`,
     *   `total2(gla[0].view(), grow())`): made after it instead, it would be D33's order broken.
     *
     * The message names what to write instead: the temporary, or the later operand, in a local
     * first, or a defaulted parameter passed explicitly.
     */
    private fun checkViews(ops: List<Operand>, result: KType, written: Set<Symbol>) {
        val order = mutableListOf<Step>()
        steps(ops, written, order)
        order.forEachIndexed { i, step ->
            val op = step.op as? Operand.Value ?: return@forEachIndexed
            val e = op.expr ?: return@forEachIndexed
            if (op.lent && CppLending.lends(result)) {
                val temporary = lower.model.typeOrNull(e)?.let { owner(e, it) } as? Source.Temporary ?: Source.Temporary(e)
                refuse(e, "a view the call may keep into ${describe(temporary)}, a temporary, would point into a temporary of the lambda that orders the call's operands (D33): ${remedy(temporary)}")
                return@forEachIndexed
            }
            val t = lower.model.typeOrNull(e) ?: return@forEachIndexed
            if (!CppLending.lends(t)) {
                return@forEachIndexed
            }
            val from = sources(e)
            val temporary = from.firstOrNull { it is Source.Temporary } as Source.Temporary?
            if (temporary != null && (step.rank != PURE || CppLending.lends(result))) {
                refuse(e, "this value holds a view into ${describe(temporary)}, a temporary, which would not outlive the lambda that orders the call's operands (D33): ${remedy(temporary)}")
                return@forEachIndexed
            }
            val moving = from.firstOrNull { it is Source.Place && storageMoves(it.expr, it.type) }
            val later = order.drop(i + 1).firstOrNull { it.rank == IMPURE }
            if (step.rank != PURE && moving != null && later != null) {
                val x = later.op.expr
                val run = when {
                    x is FunctionCallExpr || (x is MemberAccessExpr && x.member is FunctionCallExpr) -> "the call behind ${describe(x)}"
                    x != null -> describe(x)
                    else -> "a later operand"
                }
                val store = x?.let { describe(it) } ?: "that operand"
                refuse(e, "this view into ${describe(moving.expr)} would be made before $run runs (D33), which may move or free the storage the view points into: store $store in a local first")
            }
        }
    }

    /**
     * Refuses, when [spill] would write it, the call [rc] (at [node]) whose result can hold a
     * view and which binds a default argument as a temporary ([defaultTemporaries]): the lambda
     * returns `f(t0_, t1_)`, and the default dies with that `return` while the view it may
     * hold leaves the lambda (`count(tailK(nextSize(), nextSize()))`, `s: Str = "..."`).
     */
    private fun checkDefaults(rc: ResolvedCall, node: Expr, result: KType) {
        if (!CppLending.lends(result)) {
            return
        }
        val temporary = defaultTemporaries(rc).firstOrNull() ?: return
        refuse(node, "this call's result may hold a view into ${describe(temporary)}, which would not outlive the lambda that orders the call's operands (D33): ${remedy(temporary)}")
    }

    /**
     * The defaults [rc] leaves to its callee that C++ binds as temporaries: a parameter whose
     * type owns storage ([CppLending.borrowable]: a `Str` default is a literal or a `Str`
     * constant, D48, which a `const kira::Str&` binds as a fresh `kira::Str`), unless the
     * default is a constant place of the parameter's own type, which the reference binds as it is.
     */
    fun defaultTemporaries(rc: ResolvedCall): List<Source.Temporary> = rc.args.mapNotNull { a ->
        val p = (a as? ArgBinding.Default)?.param ?: return@mapNotNull null
        val d = p.default ?: return@mapNotNull null
        val t = p.type.substitute(rc.substitution)
        when {
            !CppLending.borrowable(t) -> null
            lower.isPlaceExpr(d) && !lower.isCopiedPlace(d) && lower.model.typeOrNull(d) == t -> null
            else -> Source.Temporary(d, param = p)
        }
    }

    /**
     * Refuses the call [rc] (at [node]) when it leaves a parameter to a default C++ binds as a
     * temporary ([defaultTemporaries]) and the callee may keep a view into it past the call
     * ([keepsArgument]): `keepS()` storing `s.view()` in a global dangles once the statement ends.
     */
    fun refuseKeptDefault(rc: ResolvedCall, node: Expr) {
        defaultTemporaries(rc).forEach { temporary ->
            val p = temporary.param ?: return@forEach
            if (keepsParameter(rc, p.index, asView = false)) {
                val callee = rc.fn?.name?.let { "'$it'" } ?: if (rc.kind == CallKind.FN_VALUE) "the Fx value called here" else "the callee"
                refuse(node, "$callee may keep a view into ${describe(temporary)} past the call, and C++ destroys it at the end of the statement: ${remedy(temporary)}")
                return
            }
        }
    }

    /**
     * Whether the storage of the place [e], of type [t], can move or be freed by a sibling's
     * effect while a view into it lives: its own type's storage moves ([CppLending.moves]), or a
     * step on its path goes through storage that does: an element of a container that
     * reallocates (`gla[0]` in a `List<Arr<Int32, 3>>`), or a field of the object behind a
     * handle (`gr.value` in a `Ref`, a class's field), which a sibling may free by replacing
     * the handle. An `Arr` element and a struct field stay where their parent is; a step
     * through a view points wherever the view was made (a fact of its own statement, OD-3).
     */
    fun storageMoves(e: Expr, t: KType): Boolean {
        if (CppLending.moves(t)) {
            return true
        }
        val model = lower.model
        var cur: Expr = e
        while (true) {
            cur = when (cur) {
                is ArrayIndexExpr -> {
                    val ct = model.typeOrNull(cur.originExpr) ?: return true
                    when {
                        CppLending.reallocates(ct) -> return true
                        CppLending.isView(ct) -> return false
                        else -> cur.originExpr
                    }
                }
                is MemberAccessExpr -> {
                    if (model.member(cur) !is MemberRef.Field) {
                        return false
                    }
                    val ot = model.typeOrNull(cur.origin) ?: return true
                    if (lower.isPointerLike(ot) || CppBindingTable.magicName(ot) == "Maybe") {
                        return true
                    }
                    cur.origin
                }
                is ThisExpr -> return model.typeOrNull(cur)?.let { lower.isPointerLike(it) } ?: true
                else -> return false
            }
        }
    }

    /** Where a value that can hold a view may point: a place's storage, or a temporary made while it is evaluated. */
    sealed class Source(val expr: Expr) {
        /** The storage of the place [expr], of type [type] (a container, a `Str`, a struct, a handle's object). */
        class Place(expr: Expr, val type: KType) : Source(expr)

        /**
         * A fresh value that dies with its full expression: [expr] itself (a call's result, a
         * construction, a `Str` constant made a `kira::Str`); the `kira::Maybe` copy C++ makes of
         * the place [expr] for a `Maybe` parameter ([wrapped]: the typer's `WrapSome`); or the
         * C++ default argument of [param] (D48: a literal or a constant, [expr] its default),
         * which a `const&` parameter binds as a temporary made at the call.
         */
        class Temporary(expr: Expr, val wrapped: Boolean = false, val param: ParamSymbol? = null) : Source(expr)
    }

    /** What the temporary [s] is, for a message. */
    fun describe(s: Source.Temporary): String = when {
        s.param != null -> "the default of '${s.param.name}'"
        s.wrapped -> "the Maybe that C++ copies ${describe(s.expr)} into for the call"
        else -> describe(s.expr)
    }

    /** What to write instead of keeping a view into the temporary [s]. */
    fun remedy(s: Source.Temporary): String = when {
        s.param != null -> "pass '${s.param.name}' explicitly, from a local (C++ binds its default, ${describe(s.expr)}, as a temporary of the call)"
        s.wrapped -> "store ${describe(s.expr)} in a local of the Maybe type first"
        else -> "store ${describe(s.expr)} in a local first"
    }

    /**
     * What the value of [e] may hold a view into, conservatively: nothing when its type holds no
     * view or it is a place (a view held in a variable points wherever it was made, a fact of
     * its own statement); for a call whose result can hold one, every operand that owns or
     * reaches storage (a container, a `Str`, a struct, a class or trait handle, a type
     * parameter: the callee may derive the view from any), and every view it is handed; for a
     * construction or an array literal, what each view-holding field or element holds; for an
     * if-expression, what either branch holds. A `List` or `Arr` place converted to a `View` is
     * a view into that place.
     */
    fun sources(e: Expr): List<Source> {
        val out = mutableListOf<Source>()
        collectSources(e, out, IdentityHashMap())
        return out
    }

    private fun collectSources(e: Expr, out: MutableList<Source>, seen: IdentityHashMap<Expr, Boolean>) {
        if (seen.put(e, true) != null) {
            return
        }
        val model = lower.model
        val c = model.coercion(e)
        if (c is Coercion.ToView) {
            when {
                // A literal is kira::lit, a view of static storage.
                c.from == KType.Str -> {}
                CppLending.isView(c.from) -> valueSources(e, out, seen)
                else -> out += owner(e, c.from)
            }
            return
        }
        valueSources(e, out, seen)
    }

    private fun valueSources(e: Expr, out: MutableList<Source>, seen: IdentityHashMap<Expr, Boolean>) {
        val model = lower.model
        val t = model.typeOrNull(e) ?: return
        if (!CppLending.lends(t) || (lower.isPlaceExpr(e) && !lower.isCharPtr(e))) {
            return
        }
        when (e) {
            is FunctionCallExpr -> model.call(e)?.let { callSources(it, e, out, seen) }
            is MemberAccessExpr -> {
                val call = e.member as? FunctionCallExpr
                val rc = call?.let { model.call(it) }
                when {
                    rc != null -> callSources(rc, call, out, seen)
                    // A view field of a fresh value points where the value's field did.
                    model.member(e) is MemberRef.Field -> collectSources(e.origin, out, seen)
                }
            }
            is ArrayIndexExpr -> collectSources(e.originExpr, out, seen)
            is ObjectInitExpr -> model.init(e)?.let { ri ->
                ri.fields.forEach { f ->
                    if (f is FieldInit.Given && CppLending.lends(f.field.type.substitute(ri.substitution))) {
                        collectSources(f.expr, out, seen)
                    }
                }
            }
            is ArrayLiteral -> e.value.forEach { collectSources(it, out, seen) }
            is IfExpr -> listOfNotNull(lower.branchValue(e.thenBranch), lower.branchValue(e.elseBranch)).forEach { collectSources(it, out, seen) }
            else -> model.opCall(e)?.let { callSources(it, e, out, seen) }
        }
    }

    private fun callSources(rc: ResolvedCall, node: Expr, out: MutableList<Source>, seen: IdentityHashMap<Expr, Boolean>) {
        if (lower.isLiteralView(rc)) {
            // `"abc".view()` is kira::lit: static storage.
            return
        }
        val model = lower.model
        fun operand(x: Expr) {
            if (model.coercion(x) is Coercion.ToView) {
                collectSources(x, out, seen)
                return
            }
            val xt = model.typeOrNull(x) ?: return
            // A value may both hold views and own storage (a struct with a View and a List
            // field, a List<View<Int32>>): the result may point where its views do, or into it.
            if (CppLending.lends(xt)) {
                collectSources(x, out, seen)
            }
            if (CppLending.borrowable(xt) || lower.isPointerLike(xt)) {
                out += owner(x, xt)
            }
        }
        rc.receiver?.let { operand(it) }
        if (rc.receiver == null && rc.implicitThis) {
            // The implicit receiver (a struct's *this, a class's object): storage, never a temporary.
            lower.state.frame?.owner?.let { owner -> (owner as? ClassSymbol)?.let { out += Source.Place(node, it.selfType) } }
        }
        rc.args.forEach { a -> if (a is ArgBinding.Given) operand(a.expr) }
        // A default the callee's const& parameter binds as a temporary made at the call (D48).
        out += defaultTemporaries(rc)
    }

    /**
     * [x], of type [t], as what a view may point into: a place's storage, or a temporary (a
     * fresh value, a `Str` constant made a `kira::Str`, or the `kira::Maybe` copy of a place
     * the typer wraps for a `Maybe` parameter: `mview(xs)` binds `const kira::Maybe<List>&` to
     * a copy of `xs`, and `m.value.view()` points into the copy).
     */
    private fun owner(x: Expr, t: KType): Source = when {
        !lower.isPlaceExpr(x) -> Source.Temporary(x)
        lower.isCharPtr(x) -> Source.Temporary(x)
        lower.isCopiedPlace(x) -> Source.Temporary(x, wrapped = true)
        else -> Source.Place(x, t)
    }

    /** Whether [sources] of [e] hold a temporary: a view into it outlives the full expression that made it. */
    fun holdsTemporary(e: Expr): Source.Temporary? = sources(e).firstOrNull { it is Source.Temporary } as Source.Temporary?

    /**
     * Refuses [value] where it is kept past its full expression ([sink]: "the local 'v'", "the
     * returned value", "the loop") when it holds a view into a temporary (`v: View<Int32> =
     * makeList().view()`, `return tail(makeList(), 1)`, `for x in tail(makeList(), 1)`): C++
     * destroys the temporary at the end of the full expression. True when it refused.
     */
    fun refuseTemporaryView(value: Expr, sink: String): Boolean {
        if (!holdsViews(value)) {
            return false
        }
        val temporary = holdsTemporary(value) ?: return false
        refuse(value, "this value holds a view into ${describe(temporary)}, a temporary that C++ destroys at the end of the statement, but $sink outlives it: ${remedy(temporary)}")
        return true
    }

    /**
     * Refuses the value [value] of an if-expression's branch (its C++ is a lambda's `return`)
     * when it holds a view into a local of that branch ([locals]), which the lambda destroys as
     * it returns: `if c { l: List<Int32> = make(); l.view() } else { ... }`.
     */
    fun refuseBranchLocalView(value: Expr, locals: Set<Symbol>): Boolean {
        if (locals.isEmpty() || !holdsViews(value)) {
            return false
        }
        val self = if (lower.isPlaceExpr(value)) rootSymbol(value) else null
        val local = self?.takeIf { it in locals }?.let { value }
            ?: sources(value).firstOrNull { it is Source.Place && rootSymbol(it.expr)?.let { r -> r in locals } == true }?.expr
            ?: return false
        refuse(value, "this value may hold a view into ${describe(local)}, a local of the branch, which is destroyed as the if-expression's value leaves it: declare it before the if-expression")
        return true
    }

    /** Whether the value of [e] is, or can hold, a view (its type, or a `List`/`Arr` place converted to a `View`). */
    private fun holdsViews(e: Expr): Boolean =
        lower.model.typeOrNull(e)?.let { CppLending.lends(it) } == true || lower.model.coercion(e) is Coercion.ToView

    /**
     * Refuses the argument [arg] (parameter [i] of [rc]) when it is, or holds a view into, a
     * temporary the callee may keep a view into past the call ([keepsArgument]):
     * `ms.add(makeList().view())`, `stash(makeList())` storing `xs.from(0)` in a global.
     */
    fun refuseKeptTemporary(rc: ResolvedCall, i: Int, arg: Expr) {
        val t = lower.model.typeOrNull(arg) ?: return
        val fresh = CppLending.borrowable(t) && lower.model.coercion(arg) !is Coercion.ToView && (!lower.isPlaceExpr(arg) || lower.isCopiedPlace(arg)) && lentArgument(rc, i, arg)
        val temporary = if (fresh) owner(arg, t) as? Source.Temporary else if (CppLending.lends(t)) holdsTemporary(arg) else null
        if (temporary == null || !keepsArgument(rc, i, arg)) {
            return
        }
        val callee = rc.fn?.name?.let { "'$it'" } ?: if (rc.kind == CallKind.FN_VALUE) "the Fx value called here" else "the callee"
        refuse(arg, "$callee may keep a view into ${describe(temporary)}, a temporary, past the call, and C++ destroys it at the end of the statement: ${remedy(temporary)}")
    }

    /**
     * Refuses the receiver [receiver] of the user method [rc] when it is a temporary, or holds
     * a view into one, and the method may keep a view into it past the call: `makeBag().stash()`
     * with `stash` storing `items.view()` in a global printed garbage on gcc. A stdlib method
     * keeps nothing of its receiver, and a class or trait receiver is a handle.
     */
    fun refuseKeptReceiver(rc: ResolvedCall, receiver: Expr) {
        val t = lower.model.typeOrNull(receiver) ?: return
        val fresh = CppLending.borrowable(t) && (!lower.isPlaceExpr(receiver) || lower.isCopiedPlace(receiver))
        val temporary = if (fresh) owner(receiver, t) as? Source.Temporary else if (CppLending.lends(t)) holdsTemporary(receiver) else null
        temporary ?: return
        val keeps = when (rc.kind) {
            CallKind.PRINT, CallKind.MAGIC, CallKind.EXTERN -> false
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> rc.fn?.let { escapes.viewStored(it, null, false) } ?: true
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.FN_VALUE -> true
        }
        if (keeps) {
            val callee = rc.fn?.name?.let { "'$it'" } ?: "the method"
            refuse(receiver, "$callee may keep a view into its receiver, ${describe(temporary)}, a temporary, past the call, and C++ destroys it at the end of the statement: ${remedy(temporary)}")
        }
    }

    /**
     * Whether the call [rc] may keep a view its argument [arg] (parameter [i]) is, or can be
     * lent from, past the call ([keepsParameter]).
     */
    fun keepsArgument(rc: ResolvedCall, i: Int, arg: Expr): Boolean {
        val t = lower.model.typeOrNull(arg) ?: return true
        val asView = CppLending.lends(t) || lower.model.coercion(arg) is Coercion.ToView
        return keepsParameter(rc, i, asView)
    }

    /**
     * Whether the call [rc] may keep, past the call, a view its parameter [i] holds ([asView])
     * or one it takes into the object that parameter binds, where the caller's storage
     * outlives the statement: in a container of views it is a method of (a stdlib binding),
     * wherever the body of a user function stores it ([CppEscapes]), and, for a callee not
     * seen through (virtual, trait, an `Fx` value), in a `mut` argument that can hold a view
     * or wherever any body the call may run stores it ([CppEscapes.dispatchStores]: every
     * lambda and function used as a value, every method of that name). A view kept in a class
     * field or a global outlives a temporary; a local's storage outlives the statement, and
     * a view of it kept past the function is EscapePass's rule (design 3.4). An extern takes
     * a `Str` through `kira::ffi::in`, for the call.
     */
    fun keepsParameter(rc: ResolvedCall, i: Int, asView: Boolean): Boolean = when (rc.kind) {
        CallKind.PRINT, CallKind.EXTERN -> false
        CallKind.MAGIC -> rc.receiver?.let { lower.model.typeOrNull(it) }?.let { CppLending.lends(it) } == true
        CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> {
            val fn = rc.fn
            val p = fn?.params?.getOrNull(i)
            fn == null || p == null || escapes.viewStored(fn, p, asView)
        }
        CallKind.VIRTUAL, CallKind.TRAIT, CallKind.FN_VALUE -> rc.args.any { a ->
            a is ArgBinding.Given && a.byRef && lower.model.typeOrNull(a.expr)?.let { CppLending.lends(it) } != false
        } || escapes.dispatchStores(rc, i, asView)
    }

    /** The nodes already refused, so one construct is reported once. */
    private val refused: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())

    /** Reports `cpp.view-lifetime` at [node] (once per node). */
    fun refuse(node: ASTNode, message: String) {
        if (refused.add(node)) {
            lower.ctx.diag(node, VIEW_LIFETIME_CODE, message)
        }
    }

    /** A short name for [e] in a message: what kind of value it is. */
    fun describe(e: Expr): String = when (e) {
        is FunctionCallExpr -> lower.model.call(e)?.fn?.name?.let { "the result of '$it'" } ?: "a call's result"
        is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { describe(it) }
            ?: (lower.model.member(e) as? MemberRef.Field)?.let { "'${it.field.name}'" } ?: "this value"
        is ArrayIndexExpr -> "an element of ${describe(e.originExpr)}"
        is ObjectInitExpr -> "the construction"
        is ArrayLiteral -> "the array literal"
        is InterpolatedStringLiteral -> "the interpolated Str"
        is StringLiteral -> "the Str literal"
        is ThisExpr -> "this"
        is Identifier -> if (lower.isCharPtr(e)) "the Str constant '${e.value}'" else "'${e.value}'"
        is IfExpr -> "the if-expression"
        else -> "this value"
    }

    // ---- lending: which places a call may keep a view into -------------------------------------

    /**
     * Whether the call [rc] may lend from [arg], its argument [i]: a view into it can outlive
     * the call, so it is [PlaceMode.LENT], never copied. Nothing lends from a value whose copy
     * owns no storage ([CppLending.borrowable]: a scalar, a struct of scalars, a view, an `Fx`
     * value). An argument the typer converts to a `View` (`ToView`) is that view; any other
     * is the object the callee's `const&` parameter binds. A print lends nothing; a stdlib
     * binding lends through a lending result or a parameter that can hold a view, its C++
     * being known; a user function, operator or method through a lending result, or where
     * [CppEscapes] says a view into the argument may outlive the call (a generic parameter
     * instantiated with a view counts as that view: `keep<View<Int32>>(mut h, gl, k)`); a
     * virtual, trait, extern or `Fx`-value callee is not seen through, so it lends.
     */
    fun lentArgument(rc: ResolvedCall, i: Int, arg: Expr): Boolean {
        val t = lower.model.typeOrNull(arg) ?: return true
        if (!CppLending.borrowable(t)) {
            return false
        }
        val fn = rc.fn
        val param = fn?.params?.getOrNull(i)
        val asView = lower.model.coercion(arg) is Coercion.ToView
        return when (rc.kind) {
            CallKind.PRINT -> false
            CallKind.MAGIC -> CppLending.lends(rc.returnType) || param == null || CppLending.lends(param.type.substitute(rc.substitution))
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR ->
                CppLending.lends(rc.returnType) || fn == null || param == null || escapes.viewEscapes(fn, param, asView)
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }
    }

    /** [lentArgument] for the receiver of [rc], of type [t] (the receiver a `mut fx` writes is a written place already). */
    fun lentReceiver(rc: ResolvedCall, t: KType): Boolean {
        if (!CppLending.borrowable(t)) {
            return false
        }
        val fn = rc.fn
        return when (rc.kind) {
            CallKind.PRINT -> false
            CallKind.MAGIC -> CppLending.lends(rc.returnType)
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR ->
                CppLending.lends(rc.returnType) || (fn?.let { escapes.viewEscapes(it, null, false) } ?: true)
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }
    }

    /** [CppLending.lends]. */
    fun lends(t: KType): Boolean = CppLending.lends(t)

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
     * `View`: `writeU32(p.from(4), v)` writes `p`), of the receiver of a `mut fx`, and of an
     * assignment's target, at any depth. A `MutView` held in a variable writes whatever it was
     * lent from, which is not traced: then every `Arr`, `List` or `MutView` variable the operands
     * read counts as written. The operands' own call writes nothing while they are evaluated
     * (its callee runs after), so a written place among [ops] adds nothing.
     */
    fun writtenBy(ops: List<Operand>): Set<Symbol> {
        val out: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
        var untraced = false
        fun collect(op: Operand) {
            op.expr?.let { if (writes(it, out)) untraced = true }
            if (op is Operand.Place) {
                op.parts.forEach { collect(it) }
            }
        }
        ops.forEach { collect(it) }
        if (untraced) {
            fun buffers(op: Operand) {
                op.expr?.let { e ->
                    outsideLambdas(e) { node ->
                        if (node is Identifier && CppBindingTable.magicName(lower.model.typeOrNull(node)) in BUFFERS) {
                            lower.model.symbolOf(node)?.let { out += it }
                        }
                    }
                }
                if (op is Operand.Place) {
                    op.parts.forEach { buffers(it) }
                }
            }
            ops.forEach { buffers(it) }
        }
        return out
    }

    /** Adds to [out] what [root] writes ([writtenBy]); true when it writes through a `MutView` variable whose source is not traced. */
    private fun writes(root: Expr, out: MutableSet<Symbol>): Boolean {
        val model = lower.model
        var untraced = false
        fun through(e: Expr) {
            val lentFrom = lentMutView(e)
            if (lentFrom != null) {
                // `p.from(4)` handed on writes p.
                through(lentFrom)
                return
            }
            rootSymbol(e)?.let { out += it }
            if (throughMutView(e)) {
                untraced = true
            }
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
        return untraced
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

    /** The receiver a binding lends the `MutView` [e] from (`p` for `p.from(4)`), or null when [e] is no such call. */
    private fun lentMutView(e: Expr): Expr? {
        val call = when (e) {
            is FunctionCallExpr -> e
            is MemberAccessExpr -> e.member as? FunctionCallExpr
            else -> null
        } ?: return null
        return lower.model.call(call)?.takeIf { CppBindingTable.magicName(it.returnType) == "MutView" }?.receiver
    }

    /** Whether the place [e] is reached through a `MutView` value (`mv[0]`, `w.mv[0]`), which writes where it was lent from. */
    private fun throughMutView(e: Expr): Boolean {
        var cur: Expr? = e
        while (cur != null) {
            if (CppBindingTable.magicName(lower.model.typeOrNull(cur)) == "MutView") {
                return true
            }
            cur = when (cur) {
                is MemberAccessExpr -> if (lower.model.member(cur) is MemberRef.Field) cur.origin else null
                is ArrayIndexExpr -> cur.originExpr
                else -> null
            }
        }
        return false
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
     * though the name is a local) and a local some `MutView` was lent from ([mutViewRoots]:
     * any call may write through that view, wherever it went). Taking a view (the receiver of
     * a pure call whose result is one, `p.from(4)`) reads no element.
     */
    private fun scan(root: Expr): Int {
        var best = PURE
        val stack = ArrayDeque<ASTNode>()
        stack.addLast(root)
        val seen = IdentityHashMap<ASTNode, Boolean>()
        val addressOnly: MutableSet<ASTNode> = Collections.newSetFromMap(IdentityHashMap())
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
                        if (CppLending.lends(rc.returnType)) {
                            rc.receiver?.let { addressOnly += it }
                        } else if (readsThrough(rc)) {
                            best = maxOf(best, READS)
                        }
                    }
                }
                is IntrinsicExpr -> if (node.intrinsicKey.name == "_trace_") return IMPURE
                is ThrowExpr, is TryExpr, is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr -> return IMPURE
                is Identifier -> if (readsShared(node) || (node !in addressOnly && lower.model.symbolOf(node)?.let { it in mutViewRoots } == true)) best = READS
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
                is ArrayIndexExpr -> if (lower.model.typeOrNull(node.originExpr)?.let { CppLending.isView(it) } == true) best = READS
                is Expr -> lower.model.opCalls[node]?.let { rc ->
                    rc.fn?.let { if (lower.model.effect(it) != Effect.PURE) return IMPURE }
                    if (!CppLending.lends(rc.returnType) && readsThrough(rc)) best = maxOf(best, READS)
                }
                else -> {}
            }
            AstTree.children(node).forEach { stack.addLast(it) }
        }
        return best
    }

    /**
     * Whether the pure call [rc] reads through a view or a handle among its operands, or a value
     * holding one (`v.get(0)`, `readU16Le(pkt, 0)`, a pure method on a class, a struct with a
     * view field): what it reads is storage a sibling may write, whatever variable holds the view.
     */
    private fun readsThrough(rc: ResolvedCall): Boolean {
        val operands = listOfNotNull(rc.receiver) + rc.args.mapNotNull { (it as? ArgBinding.Given)?.expr }
        return operands.any { x ->
            val t = lower.model.typeOrNull(x) ?: return@any false
            val converted = lower.model.coercion(x) as? Coercion.ToView
            CppLending.lends(t) || lower.isPointerLike(t) || (converted != null && CppLending.isView(converted.from))
        }
    }

    /**
     * Every variable a `MutView` is lent from anywhere in the program: the receiver of a stdlib
     * lending call (`xs` for `mv: MutView<Int32> = xs.from(0)`), and the `mut` argument or the
     * receiver of a `mut fx` that a user callee may lend one out of ([CppEscapes.lendsMutView]:
     * `mv: MutView<Int32> = mk(mut xs)` with `mk` returning `xs.from(0)`, or storing it in a
     * global). A write through that view may come from any call that reaches it (a closure that
     * captured it, a `List<MutView>` or a struct holding it), so a read of the variable is
     * READS, like a read of a `mut` global. A `LocalSymbol` is one declaration, so the set is
     * exact per variable.
     */
    private val mutViewRoots: Set<Symbol> by lazy {
        val out: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
        fun root(x: Expr) {
            rootSymbol(x)?.let { out += it }
        }
        (lower.model.calls.values + lower.model.opCalls.values).forEach { rc ->
            if (CppBindingTable.magicName(rc.returnType) == "MutView") {
                rc.receiver?.let { root(it) }
            }
            rc.args.forEachIndexed { i, a ->
                if (a is ArgBinding.Given && a.byRef && escapes.lendsMutView(rc, i)) {
                    root(a.expr)
                }
            }
            val receiver = rc.receiver
            if (receiver != null && rc.kind != CallKind.MAGIC && rc.fn?.isMutMethod == true && escapes.lendsMutView(rc, null)) {
                root(receiver)
            }
        }
        out
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

        /**
         * A construct refused because the C++ it would lower to could leave a view pointing at
         * freed storage (the convergence policy: refuse, never emit a use after free). The
         * message says what to write instead.
         */
        const val VIEW_LIFETIME_CODE = "cpp.view-lifetime"

        /** What a `MutView` can be lent from. */
        private val BUFFERS = setOf("Arr", "List", "MutView")

        /** Every line of [lines] (each possibly several) indented four spaces. */
        fun indent(lines: List<String>): String =
            lines.flatMap { it.split('\n') }.joinToString("\n") { if (it.isEmpty()) it else "    $it" }
    }
}

/** What a type says about views: whether a value of it can point into something, and whether a copy of it can be pointed into. */
object CppLending {
    /** The magic classes whose value is a pointer or a view to storage elsewhere: a copy points where the original did. */
    private val POINTERS = setOf("View", "MutView", "Ref", "Weak", "Unsafe")

    /** The magic classes whose value owns storage a view can be taken into. */
    private val OWNERS = setOf("StrBuf", "Arr", "List", "Map", "Set", "Stack", "Queue", "Deque")

    fun isView(t: KType): Boolean = magicName(t).let { it == "View" || it == "MutView" }

    /**
     * Whether a value of type [t] can hold a view or pointer into an operand of the expression
     * that made it, so the operand must stay where it lives: a `View`, `MutView` or `Unsafe`
     * itself, or one held anywhere inside the value by value (a type argument of a `Maybe`, a
     * tuple, a `Result` or a container; a field of a struct at any depth, through the parent
     * chain, with the type arguments substituted: `Win { v = gl, k = nextSize() }` lends `gl`
     * through `Win.v`). A type parameter is taken to lend where [paramLends] (a call's
     * result, where it stands for an unknown type), and not inside a generic body (there it
     * is a value the body cannot take a view from, except through a trait call, which
     * [CppEscapes] sees as a delegation). A class or trait handle is a pointer to a shared
     * object, and an `Fx` value owns its captures (captures are by value, design 2.1), so a
     * copy of either points where the original did.
     */
    fun lends(t: KType, paramLends: Boolean = true): Boolean = lends(t, paramLends, IdentityHashMap())

    private fun lends(t: KType, paramLends: Boolean, seen: MutableMap<ClassSymbol, Boolean>): Boolean = when (t) {
        is KType.Param -> paramLends
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> false
                ClassKind.STRUCT -> if (seen.put(sym, true) != null) {
                    false
                } else {
                    val substitution = sym.typeParams.zip(t.typeArgs()).toMap()
                    sym.fields.any { lends(it.type.substitute(substitution), paramLends, seen) } ||
                        sym.superclass?.let { lends(it.substitute(substitution), paramLends, seen) } == true
                }
                ClassKind.MAGIC -> when (sym.name) {
                    "View", "MutView", "Unsafe" -> true
                    "Ref", "Weak", "Fx" -> false
                    else -> t.typeArgs().any { lends(it, paramLends, seen) }
                }
            }
            else -> false
        }
        else -> false
    }

    /**
     * Whether a value of type [t] can hold a `MutView`, through which it writes where the view
     * was lent from: a `MutView`, or one held by value anywhere inside (a type argument, a
     * struct's field at any depth), or a type parameter where [paramHolds] (a user function's
     * result, instantiated with an unknown type).
     */
    fun holdsMutView(t: KType, paramHolds: Boolean = true): Boolean = holdsMutView(t, paramHolds, IdentityHashMap())

    private fun holdsMutView(t: KType, paramHolds: Boolean, seen: MutableMap<ClassSymbol, Boolean>): Boolean = when (t) {
        is KType.Param -> paramHolds
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> false
                ClassKind.STRUCT -> if (seen.put(sym, true) != null) {
                    false
                } else {
                    val substitution = sym.typeParams.zip(t.typeArgs()).toMap()
                    sym.fields.any { holdsMutView(it.type.substitute(substitution), paramHolds, seen) } ||
                        sym.superclass?.let { holdsMutView(it.substitute(substitution), paramHolds, seen) } == true
                }
                ClassKind.MAGIC -> when (sym.name) {
                    "MutView" -> true
                    "View", "Unsafe", "Fx" -> false
                    else -> t.typeArgs().any { holdsMutView(it, paramHolds, seen) }
                }
            }
            else -> false
        }
        else -> false
    }

    /**
     * Whether a copy of a value of type [t] owns storage a view can be taken into, so lending
     * from the copy would dangle: a `Str`, a `StrBuf`, an `Arr`, a `List`, `Map`, `Set`,
     * `Stack`, `Queue` or `Deque`, a `Maybe`, tuple or `Result` holding one, a struct with such
     * a field at any depth, or a type parameter (unknown). A scalar, an enum, a view, a
     * pointer, a class or trait handle and an `Fx` value (whose captures are its own) are not.
     */
    fun borrowable(t: KType): Boolean = borrowable(t, IdentityHashMap())

    private fun borrowable(t: KType, seen: MutableMap<ClassSymbol, Boolean>): Boolean = when (t) {
        KType.Str, is KType.Param -> true
        is KType.Fn -> false
        is KType.Nominal -> when (val sym = t.sym) {
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> false
                ClassKind.STRUCT -> if (seen.put(sym, true) != null) {
                    false
                } else {
                    val substitution = sym.typeParams.zip(t.typeArgs()).toMap()
                    sym.fields.any { borrowable(it.type.substitute(substitution), seen) } ||
                        sym.superclass?.let { borrowable(it.substitute(substitution), seen) } == true
                }
                ClassKind.MAGIC -> when (sym.name) {
                    in POINTERS, "Fx" -> false
                    in OWNERS -> true
                    else -> t.typeArgs().any { borrowable(it, seen) }
                }
            }
            else -> false
        }
        else -> false
    }

    /**
     * Whether a container of type [t] moves its elements when it grows or is rebuilt, so a
     * reference bound to one of them dangles after a sibling's effect: a `List`, a `Map`, a
     * `Set`, a `Stack`, a `Queue`, a `Deque`, a `Str`, or a type parameter (unknown). An
     * `Arr` keeps its elements in place, and so does a `StrBuf`; a view owns no elements.
     */
    fun reallocates(t: KType): Boolean = when (t) {
        KType.Str, is KType.Param -> true
        is KType.Nominal -> magicName(t) in REALLOCATING
        else -> false
    }

    /**
     * Whether storage a view into a value of type [t] points at can move or be freed while the
     * value's variable lives (a sibling that grows or reassigns it): a [reallocates] container,
     * an `Arr` or other container of such, a struct with such a field at any depth, a class or
     * trait handle (its object's fields are not seen here), a type parameter. An `Arr` of
     * scalars and a `StrBuf` keep their storage where the variable is.
     */
    fun moves(t: KType): Boolean = moves(t, IdentityHashMap())

    private fun moves(t: KType, seen: MutableMap<ClassSymbol, Boolean>): Boolean = when (t) {
        KType.Str, is KType.Param -> true
        is KType.Nominal -> when (val sym = t.sym) {
            is TraitSymbol -> true
            is ClassSymbol -> when (sym.kind) {
                ClassKind.CLASS, ClassKind.OPAQUE -> true
                ClassKind.STRUCT -> if (seen.put(sym, true) != null) {
                    false
                } else {
                    val substitution = sym.typeParams.zip(t.typeArgs()).toMap()
                    sym.fields.any { moves(it.type.substitute(substitution), seen) } ||
                        sym.superclass?.let { moves(it.substitute(substitution), seen) } == true
                }
                ClassKind.MAGIC -> when (sym.name) {
                    in REALLOCATING, "Ref", "Weak" -> true
                    "View", "MutView", "Unsafe", "Fx", "StrBuf" -> false
                    else -> t.typeArgs().any { moves(it, seen) }
                }
            }
            else -> false
        }
        else -> false
    }

    private val REALLOCATING = setOf("List", "Map", "Set", "Stack", "Queue", "Deque")

    private fun magicName(t: KType): String? = CppBindingTable.magicName(t)
}

/**
 * What escapes a call, read from the callee's typed body: the facts the hoister and the
 * closure lowering need and cannot guess. Each scan is conservative: it says "escapes"
 * wherever it cannot see.
 *
 * - [fxEscapes]: whether an `Fx` parameter must be a `kira::Fn` rather than a template
 *   parameter (design 5.1). `TypedModel.fxEscapes` (EscapePass, W2.5) answers where it has an
 *   entry; where it has none this approximates the pass: the parameter does not escape when
 *   the body mentions it only as the callee of a call (`each(buf.slice(at, n))`), outside any
 *   lambda, in a non-virtual, non-trait method. A parameter of a function used as a value
 *   (`g: Fx<...> = applyTo`, a `FnRef`) escapes whatever the model says, since a template is
 *   not a value `kira::Fn` can hold, and so does one with a default (no template argument is
 *   deduced from a default).
 * - [viewEscapes]: whether a view into a caller's operand can outlive the call, for a
 *   parameter or (with no parameter) the receiver. The operand is either the view itself
 *   (`asView`: a `List` or `Arr` place the typer converted to a `View`, which the parameter
 *   holds) or the object a `const&` parameter or the receiver binds (a view into it is one the
 *   body derives: `xs.from(at)`, `Win { v = xs }`). [ViewScan] follows such views through the
 *   body: a view stored anywhere but a local (a field, a global, a `mut` argument, the
 *   receiver, a thrown value), captured by a lambda, handed to a callee this cannot see
 *   through (virtual, trait, extern, an `Fx` value, a stdlib `mut` method on a container that
 *   can hold views) or to one whose own scan says it keeps it, escapes; so does one returned.
 *   A value of a type parameter counts as a view when `asView` (`keep<T>(x: T)` storing `x`,
 *   instantiated with `View<Int32>`). `TypedModel.viewEscapes` can only add an escape: its
 *   entries follow EscapePass's rules (design 3.4), which allow a view that is returned, and
 *   the hoister's question is whether a copy of the operand may be lent from. A store is
 *   local only into a local's own storage: through a handle (`r.value.add(v)`, `r` a `Ref`)
 *   or a view it reaches shared storage, and a heap construction (a class, a `Ref`'s box)
 *   holding the view keeps it.
 * - [dispatchStores] and [lendsMutView] answer the same for a callee not known statically, by
 *   scanning every body it may run: every lambda of the program and every function used as
 *   a value, of the `Fx` value's type, or every method of a virtual or trait call's name.
 */
class CppEscapes(private val model: TypedModel, private val heldByReference: (KType) -> Boolean = ::byReferenceByType) {
    /** What [viewEscapes] found: a view into the operand kept past the call ([stored]), or returned from it ([returned]). */
    data class Fate(val stored: Boolean, val returned: Boolean) {
        val escapes: Boolean get() = stored || returned
    }

    /**
     * A body a call may run: a function's or a method's ([owner] its [FnSymbol]) or a lambda's
     * ([owner] its [LambdaExpr]), with its parameters in order (null where none was recorded).
     * An [opaque] body is one a call does not reach as itself (a virtual or trait method, which
     * dispatch may replace): it is never seen through. A dispatched call instead scans every
     * body it may run, each not opaque ([dispatchStores]).
     */
    private class Body(val owner: Any, val statements: List<Statement>?, val params: List<ParamSymbol?>, val opaque: Boolean)

    private data class Key(val owner: Any, val p: ParamSymbol?, val asView: Boolean, val opaque: Boolean)

    private val fx = IdentityHashMap<ParamSymbol, Boolean>()
    private val fates = HashMap<Key, Fate>()
    private val visiting = HashSet<Key>()

    /** Every function used as a value somewhere in the program (a `FnRef` coercion). */
    private val usedAsValue: Set<FnSymbol> by lazy {
        val out: MutableSet<FnSymbol> = Collections.newSetFromMap(IdentityHashMap())
        model.coercions.values.forEach { if (it is Coercion.FnRef) out += it.fn }
        out
    }

    private fun bodyOf(fn: FnSymbol, opaque: Boolean = fn.isVirtual || fn.owner is TraitSymbol): Body = Body(fn, fn.body, fn.params, opaque)

    private fun bodyOf(l: LambdaExpr): Body = Body(l, l.def.body, l.def.parameters.map { model.declSymbol(it) as? ParamSymbol }, opaque = false)

    /**
     * Every body a call through an `Fx` value of [arity] parameters, of type [type] where it is
     * known, may run: each lambda of the program (every one the typer saw has a `captures`
     * entry) and each function used as a value, of that type (a lambda's type must match its
     * `Fx` exactly, D24), or of that arity where [type] is unknown or generic. An `Fx` value is
     * made only by one of those; a stdlib or extern callee handed one runs it as itself.
     */
    private fun fnValueBodies(arity: Int, type: KType.Fn?): List<Body> {
        val exact = type != null && !mentionsParam(type)
        fun fits(t: KType?): Boolean = !exact || t == type
        return model.captures.keys.filter { it.def.parameters.size == arity && fits(model.typeOrNull(it)) }.map { bodyOf(it) } +
            usedAsValue.filter { fn ->
                fn.params.size == arity && (fn.typeParams.isNotEmpty() || fits(KType.Fn(fn.params.map { FnParam(it.type, it.byRef) }, fn.ret)))
            }.map { bodyOf(it, opaque = false) }
    }

    /** Whether [t] mentions a type parameter anywhere (an `Fx` type a generic body calls through). */
    private fun mentionsParam(t: KType): Boolean = when (t) {
        is KType.Param -> true
        is KType.Fn -> t.params.any { mentionsParam(it.type) } || mentionsParam(t.ret)
        is KType.Nominal -> t.typeArgs().any { mentionsParam(it) }
        else -> false
    }

    /** The call expression of each resolved call, by identity: the callee of an `Fx`-value call is its name's value. */
    private val callNodes: IdentityHashMap<ResolvedCall, FunctionCallExpr> by lazy {
        val out = IdentityHashMap<ResolvedCall, FunctionCallExpr>()
        model.calls.forEach { (node, rc) -> out[rc] = node }
        out
    }

    /** Every method of the program, by name: a virtual or trait call of one may run any of them. */
    private val methodsByName: Map<String, List<FnSymbol>> by lazy {
        model.declSyms.values.filterIsInstance<FnSymbol>().filter { it.owner != null && it.foreign == null }.groupBy { it.name }
    }

    /**
     * Every body the call [rc] may run, for a callee not known statically (an `Fx` value, a
     * virtual or trait method), or null when it cannot be enumerated: for an `Fx` value, every
     * [fnValueBodies] of its arity; for a virtual or trait method, every method of the program
     * with its name and arity that has a body, and, where one of them has none (a slot a
     * construction fills with an `Fx` value, spec "No Abstract Classes", or a trait's
     * declaration), every [fnValueBodies] of that arity too.
     */
    private fun dispatchBodies(rc: ResolvedCall): List<Body>? = when (rc.kind) {
        CallKind.FN_VALUE -> fnValueBodies(rc.args.size, callNodes[rc]?.let { model.typeOrNull(it.name) } as? KType.Fn)
        CallKind.VIRTUAL, CallKind.TRAIT -> rc.fn?.let { fn ->
            val same = methodsByName[fn.name].orEmpty().filter { it.params.size == fn.params.size }
            same.filter { it.body != null }.map { bodyOf(it, opaque = false) } +
                if (same.any { it.body == null }) fnValueBodies(fn.params.size, null) else emptyList()
        }
        else -> null
    }

    /**
     * Whether some body the dispatched call [rc] may run ([dispatchBodies]) keeps, past the
     * call, a view its parameter [i] holds ([asView]) or one taken into the object it binds:
     * `keep(makeList())` through `keep: Fx<...> = fx (xs: List<Int32>) Void { gvs.add(xs.view()) }`.
     */
    fun dispatchStores(rc: ResolvedCall, i: Int, asView: Boolean): Boolean {
        val bodies = dispatchBodies(rc) ?: return true
        return bodies.any { b -> val p = b.params.getOrNull(i); p == null || fate(b, p, asView).stored }
    }

    /**
     * Whether the call [rc] may lend a `MutView` out of its `mut` argument [i] (the receiver a
     * `mut fx` writes when [i] is null) that outlives the call: stored anywhere, or returned in
     * a result that can hold one ([CppLending.holdsMutView]). Afterwards any call that reaches
     * the view may write the argument's variable. A stdlib binding lends one only through its
     * result, an extern for the call only; a user function per [fate] of its body; a
     * dispatched callee per every body it may run.
     */
    fun lendsMutView(rc: ResolvedCall, i: Int?): Boolean {
        val holds = CppLending.holdsMutView(rc.returnType, paramHolds = rc.kind != CallKind.MAGIC)
        fun of(f: Fate): Boolean = f.stored || (f.returned && holds)
        return when (rc.kind) {
            CallKind.PRINT, CallKind.EXTERN -> false
            CallKind.MAGIC -> holds
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> {
                val fn = rc.fn ?: return true
                val p = if (i == null) null else fn.params.getOrNull(i) ?: return true
                of(fate(fn, p, asView = false))
            }
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.FN_VALUE -> {
                val bodies = dispatchBodies(rc) ?: return true
                if (i == null) return true
                bodies.any { b -> val p = b.params.getOrNull(i); p == null || of(fate(b, p, asView = false)) }
            }
        }
    }

    /** Whether the `Fx` parameter [p] escapes its function (the class KDoc). */
    fun fxEscapes(p: ParamSymbol): Boolean {
        val fn = p.fn
        if (p.default != null || (fn != null && fn in usedAsValue)) {
            return true
        }
        return model.fxEscapes[p] ?: fx.getOrPut(p) { scanFx(p) }
    }

    /**
     * Whether a view into the operand fed to [p] of [fn] (the receiver when [p] is null) may
     * outlive a call; [asView] when the parameter holds that view itself (the class KDoc).
     */
    fun viewEscapes(fn: FnSymbol, p: ParamSymbol?, asView: Boolean): Boolean = fate(fn, p, asView).escapes

    /** Whether a view into the operand fed to [p] of [fn] may be kept past the call (stored, not only returned). */
    fun viewStored(fn: FnSymbol, p: ParamSymbol?, asView: Boolean): Boolean = fate(fn, p, asView).stored

    private fun fate(fn: FnSymbol, p: ParamSymbol?, asView: Boolean): Fate = fate(bodyOf(fn), p, asView)

    private fun fate(body: Body, p: ParamSymbol?, asView: Boolean): Fate {
        if (p != null && model.viewEscapes[p] == true) {
            return ESCAPED
        }
        val key = Key(body.owner, p, asView, body.opaque)
        fates[key]?.let { return it }
        if (!visiting.add(key)) {
            // A cycle through the call graph: not seen through.
            return ESCAPED
        }
        val result = ViewScan(body, p, asView).run()
        visiting.remove(key)
        fates[key] = result
        return result
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

    /** Where a value goes: nowhere that outlives it, a local, anywhere else, or out of the function. */
    private sealed interface Sink {
        data object Safe : Sink
        data object Store : Sink
        data object Return : Sink
        data class Local(val sym: Symbol) : Sink
    }

    /**
     * One scan of [fn]'s body for [p] (the receiver when null), flow-insensitive: a local
     * that ever holds a view into the operand holds one everywhere, and the body is walked
     * again until no more locals are found.
     */
    private inner class ViewScan(private val fn: Body, private val p: ParamSymbol?, private val asView: Boolean) {
        /** Variables whose value is, or holds, a view into the operand (the parameter itself when [asView]). */
        private val tainted: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

        /** Variables bound by reference to the operand's storage: the parameter (not [asView]), a loop variable over it. */
        private val roots: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())

        /** The receiver and its fields are the operand. */
        private val thisIsRoot = p == null && !asView
        private var stored = false
        private var returned = false
        private var grew = false

        fun run(): Fate {
            val body = fn.statements ?: return ESCAPED
            if (fn.opaque) {
                return ESCAPED
            }
            if (p != null) {
                if (asView) tainted += p else roots += p
            }
            do {
                grew = false
                body.forEach { statement(it) }
            } while (grew && !stored)
            return Fate(stored, returned)
        }

        private fun statement(s: Statement) {
            if (stored) {
                return
            }
            when (s) {
                is ReturnStatement -> expr(s.expr, Sink.Return)
                is ForIterationStatement -> loop(s)
                else -> children(s)
            }
        }

        /** [node]'s statements and expressions, each where nothing outlives it (a value that flows into [node] is [tainted] at [node]). */
        private fun children(node: ASTNode) {
            AstTree.children(node).forEach { c ->
                when (c) {
                    is Statement -> statement(c)
                    is Expr -> expr(c, Sink.Safe)
                    else -> children(c)
                }
            }
        }

        private fun expr(e: Expr, sink: Sink) {
            if (stored) {
                return
            }
            when (sink) {
                Sink.Safe -> {}
                Sink.Store -> if (tainted(e)) {
                    stored = true
                    return
                }
                Sink.Return -> if (tainted(e)) returned = true
                is Sink.Local -> if (tainted(e)) taint(sink.sym)
            }
            when (e) {
                // A closure holding a view is not followed: it escapes.
                is LambdaExpr -> if (mentions(e)) stored = true
                is VariableDecl -> {
                    val sym = model.declSymbol(e)
                    e.value?.let { v -> expr(v, if (sym != null) Sink.Local(sym) else Sink.Store) }
                }
                is FunctionCallExpr -> {
                    val rc = model.call(e)
                    if (rc == null) unresolved(e) else call(rc, e)
                }
                is MemberAccessExpr -> {
                    val rc = (e.member as? FunctionCallExpr)?.let { model.call(it) }
                    if (rc != null) call(rc, e.member as FunctionCallExpr) else expr(e.origin, Sink.Safe)
                }
                is AssignmentExpr -> assign(e.target, e.value)
                is PlaceAssignmentExpr -> if (e.operator == null) {
                    assign(e.target, e.value)
                } else {
                    expr(e.target, Sink.Safe)
                    expr(e.value, Sink.Safe)
                }
                is ThrowExpr -> AstTree.children(e).forEach { if (it is Expr) expr(it, Sink.Store) }
                // A heap object (a class, a Ref's box) outlives the call whatever holds it: a
                // view in one of its fields is stored. A struct's fields are its value, which
                // the sink above already followed.
                is ObjectInitExpr -> {
                    val ri = model.init(e)
                    val cls = ri?.cls
                    val heap = cls == null || cls.kind == ClassKind.CLASS || (cls.kind == ClassKind.MAGIC && cls.name in HEAP_MAGIC)
                    if (ri == null) {
                        children(e)
                    } else {
                        ri.fields.forEach { f -> if (f is FieldInit.Given) expr(f.expr, if (heap) Sink.Store else Sink.Safe) }
                    }
                }
                else -> {
                    val rc = model.opCall(e)
                    if (rc != null) call(rc, e) else children(e)
                }
            }
        }

        private fun taint(sym: Symbol) {
            if (tainted.add(sym)) {
                grew = true
            }
        }

        private fun assign(target: Expr, value: Expr) {
            val local = localRoot(target)
            expr(value, if (local != null) Sink.Local(local) else Sink.Store)
            expr(target, Sink.Safe)
        }

        /**
         * The local variable whose own storage holds the place (`w` for `w.v`), or null for any
         * other place: a field reached through a handle (`r.value` of a `Ref`, a class's field)
         * or an element reached through a view is shared storage the local only points at
         * (`r.value.add(xs.view())` with `r` a `Ref` keeps the view wherever `r` points).
         */
        private fun localRoot(e: Expr): Symbol? = when (e) {
            is Identifier -> model.symbolOf(e) as? LocalSymbol
            is MemberAccessExpr -> if (model.member(e) is MemberRef.Field && !pointsElsewhere(e.origin)) localRoot(e.origin) else null
            is ArrayIndexExpr -> if (!pointsElsewhere(e.originExpr)) localRoot(e.originExpr) else null
            else -> null
        }

        /** Whether a value of [e]'s type reaches storage outside itself: a class or trait handle, a `Ref`, `Weak`, `Unsafe` or view. */
        private fun pointsElsewhere(e: Expr): Boolean {
            val t = model.typeOrNull(e) as? KType.Nominal ?: return model.typeOrNull(e) is KType.Param
            return when (val sym = t.sym) {
                is TraitSymbol -> true
                is ClassSymbol -> when (sym.kind) {
                    ClassKind.CLASS, ClassKind.OPAQUE -> true
                    ClassKind.STRUCT -> false
                    ClassKind.MAGIC -> sym.name in setOf("Ref", "Weak", "Unsafe", "View", "MutView")
                }
                else -> false
            }
        }

        private fun loop(s: ForIterationStatement) {
            val target = s.forIterationExpr.target
            expr(target, Sink.Safe)
            if (target !is RangeExpr && (rooted(target) || derived(target))) {
                val plan = model.loop(s)
                val v = plan?.variable
                if (plan == null || v == null) {
                    stored = true
                    return
                }
                // An element that is a view, or a loop variable bound by const& into the operand's storage.
                if (CppLending.lends(plan.element, paramLends = asView)) {
                    taint(v)
                }
                if (CppLending.borrowable(plan.element) && (v as? LocalSymbol)?.isMut != true && roots.add(v)) {
                    grew = true
                }
            }
            s.body.forEach { statement(it) }
        }

        private fun call(rc: ResolvedCall, node: Expr) {
            val receiver = rc.receiver
            if (receiver != null) {
                receiverUse(rc, receiver)
                expr(receiver, Sink.Safe)
            } else if (rc.implicitThis && thisIsRoot && delegatesThis(rc)) {
                stored = true
            }
            if (rc.kind == CallKind.FN_VALUE && node is FunctionCallExpr) {
                expr(node.name, Sink.Safe)
            }
            rc.args.forEachIndexed { i, a ->
                if (a is ArgBinding.Given) {
                    argumentUse(rc, i, a)
                    expr(a.expr, Sink.Safe)
                }
            }
        }

        /** Marks [stored] when the call [rc] may keep a view into the operand through its argument [a], parameter [i]. */
        private fun argumentUse(rc: ResolvedCall, i: Int, a: ArgBinding.Given) {
            val x = a.expr
            val view = tainted(x)
            val place = !view && rooted(x) && byReference(x)
            if (!view && !place) {
                return
            }
            if (a.byRef) {
                stored = true
                return
            }
            val keeps = when (rc.kind) {
                CallKind.PRINT -> false
                // A stdlib function keeps nothing (a view it returns is the call's own value),
                // except a method on a container that can hold views, which stores it.
                CallKind.MAGIC -> view && rc.receiver?.let { model.typeOrNull(it) }?.let { CppLending.lends(it) } == true && keptIn(rc.receiver)
                CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> {
                    val callee = rc.fn
                    val q = callee?.params?.getOrNull(i)
                    callee == null || q == null || fate(callee, q, asView = view).stored
                }
                CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
            }
            if (keeps) {
                stored = true
            }
        }

        /** A view stored in the container [receiver]: the container's local, or an escape. */
        private fun keptIn(receiver: Expr): Boolean {
            val local = localRoot(receiver) ?: return true
            taint(local)
            return false
        }

        /** Marks [stored] when the call [rc] may keep a view into the operand through its [receiver]. */
        private fun receiverUse(rc: ResolvedCall, receiver: Expr) {
            val view = tainted(receiver)
            val place = !view && rooted(receiver) && byReference(receiver)
            if (!view && !place) {
                return
            }
            val keeps = when (rc.kind) {
                // A stdlib method keeps nothing of its receiver; a view it returns is the call's own value.
                CallKind.PRINT, CallKind.MAGIC -> false
                CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> view || (rc.fn?.let { fate(it, null, false).stored } ?: true)
                CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
            }
            if (keeps) {
                stored = true
            }
        }

        /** Whether a method called on the implicit `this` (the operand) may keep a view into it. */
        private fun delegatesThis(rc: ResolvedCall): Boolean = when (rc.kind) {
            CallKind.PRINT, CallKind.MAGIC -> false
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> rc.fn?.let { fate(it, null, false).stored } ?: true
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }

        private fun unresolved(e: Expr) {
            if (derived(e) || mentions(e)) {
                stored = true
            }
        }

        /**
         * Whether the value of [e] is, or holds, a view into the operand: a place of it the typer
         * converts to a `View`, a tainted variable, a closure over one, or a value of a type that
         * can hold a view ([CppLending.lends]; a type parameter only when [asView]) computed from
         * the operand or a tainted variable.
         */
        private fun tainted(e: Expr): Boolean {
            if (e is LambdaExpr) {
                return mentions(e)
            }
            if (model.coercion(e) is Coercion.ToView && (rooted(e) || derived(e))) {
                return true
            }
            if (e is Identifier && model.symbolOf(e)?.let { it in tainted } == true) {
                return true
            }
            val t = model.typeOrNull(e) ?: return false
            return CppLending.lends(t, paramLends = asView) && derived(e)
        }

        /** Whether the place [e] is found through the operand's storage: a root, a tainted view, `this` or a field of it. */
        private fun rooted(e: Expr): Boolean {
            var cur: Expr = e
            while (true) {
                cur = when (cur) {
                    is Identifier -> return when (val sym = model.symbolOf(cur)) {
                        null -> false
                        is FieldSymbol -> thisIsRoot
                        else -> sym in roots || sym in tainted
                    }
                    is ThisExpr -> return thisIsRoot
                    is MemberAccessExpr -> if (model.member(cur) is MemberRef.Field) cur.origin else return false
                    is ArrayIndexExpr -> cur.originExpr
                    else -> return false
                }
            }
        }

        /** Whether [e] mentions the operand or a tainted variable anywhere inside it. */
        private fun derived(e: Expr): Boolean = anyNode(e) { node ->
            when (node) {
                is Identifier -> when (val sym = model.symbolOf(node)) {
                    null -> false
                    is FieldSymbol -> thisIsRoot
                    else -> sym in roots || sym in tainted
                }
                is ThisExpr -> thisIsRoot
                else -> false
            }
        }

        /** Whether [e] mentions a tainted variable anywhere inside it (a closure captures by value, so a root copied into one is its own). */
        private fun mentions(e: Expr): Boolean = anyNode(e) { node ->
            node is Identifier && model.symbolOf(node)?.let { it in tainted } == true
        }

        private fun anyNode(root: ASTNode, test: (ASTNode) -> Boolean): Boolean {
            var found = false
            AstTree.walk(root) { node ->
                if (!found && test(node)) {
                    found = true
                }
            }
            return found
        }

        private fun byReference(e: Expr): Boolean = model.typeOrNull(e)?.let { heldByReference(it) } == true
    }

    companion object {
        private val ESCAPED = Fate(stored = true, returned = true)

        /** The magic classes a construction of which is a heap object: `Ref<T> { value = v }` is a `kira::Box<T>` (D46). */
        private val HEAP_MAGIC = setOf("Ref", "Weak")

        /**
         * `CppLowering.heldByReference` from the type alone, for a caller without a lowering:
         * a `Str`, a struct, a container, an `Fx` value, a type parameter; not a scalar, an
         * enum, a view, a pointer or a class or trait handle. A system module's class, a
         * handle to the lowering, counts as held by reference here: that only makes the scan
         * say "escapes" more often.
         */
        fun byReferenceByType(t: KType): Boolean = when (t) {
            KType.Str, is KType.Param, is KType.Fn -> true
            is KType.Nominal -> when (val sym = t.sym) {
                is ClassSymbol -> when (sym.kind) {
                    ClassKind.CLASS, ClassKind.OPAQUE -> false
                    ClassKind.STRUCT -> true
                    ClassKind.MAGIC -> sym.name !in setOf("View", "MutView", "Ref", "Unsafe")
                }
                else -> false
            }
            else -> false
        }
    }
}
