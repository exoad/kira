package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
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
 *   5.1) read at a place is a place that may be [Operand.Place.snapshot]: D33 reads it before a
 *   sibling's effect, and C++ would hand the callee the object as the effect left it
 *   (`show(gs, changeS())` printed the new `gs`), so where it is not PURE it is copied like a
 *   value, its path ordered first (`const kira::Str t0_ = gs;`). The one exception is an
 *   operand the call may lend from: a view the callee takes into it (through a `View` or
 *   `MutView` parameter, or one it derives from a `const&` parameter with `from`, `slice` or
 *   `view`) can leave the call through the result (`tail(xs, nextSize())` returning
 *   `View<Int32>`, `Win { v = gl, k = nextSize() }` whose field `v` is one), through a `mut`
 *   argument or receiver it writes (`w.attach(gl, nextSize())` storing `src` in `w.v`), or
 *   through anything else it writes; a copy the view points into dies with the lambda (gcc
 *   printed garbage, MSVC's ASan a heap-use-after-free), so such an operand stays where it
 *   lives and the call reads it as the effect left it. The decision is per operand, made
 *   where the parameter it feeds is known ([lentArgument], [lentReceiver], [CppLending.lends]
 *   for a construction's field or an assignment's target), and the emitter passes it as the
 *   operand's `snapshot`: a stdlib binding lends only through its result and its `View`
 *   parameters (its C++ is known), a user function through whatever `EscapePass` (W2.5,
 *   `TypedModel.viewEscapes`) or, where the model has no entry, [CppEscapes]'s conservative
 *   scan of its body says.
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
         * `this`: [build] spells it and nothing is ordered. A [snapshot] place is a read of a
         * value C++ holds by `const&` that the call cannot lend from: copied like a value. A
         * place with no expression of its own (an implicit `this`) carries its [type] and the
         * [rank] of reading it.
         */
        class Place(
            expr: Expr?,
            val parts: List<Operand>,
            val snapshot: Boolean = false,
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
     * the IIFE.
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

    /**
     * Whether one leaf is [IMPURE] and another is not [PURE] (design R19, D33; `Effect`). A
     * snapshot place is a leaf of its own (it is copied); any other place is its parts' leaves.
     */
    fun needsSpill(ops: List<Operand>): Boolean {
        val ranks = leaves(ops)
        return ranks.any { it == IMPURE } && ranks.count { it != PURE } >= 2
    }

    /** The rank of [op]: a value's evaluation, or the highest of a place's parts (how its location is found). */
    fun rankOf(op: Operand): Int = when (op) {
        is Operand.Value -> op.expr?.let { rank(it) } ?: PURE
        is Operand.Place -> op.parts.maxOfOrNull { rankOf(it) } ?: PURE
    }

    /** The rank of reading the snapshot place [op] as a value. */
    private fun readRank(op: Operand.Place): Int = op.rank ?: op.expr?.let { rank(it) } ?: PURE

    private fun leaves(ops: List<Operand>): List<Int> = ops.flatMap { op ->
        when (op) {
            is Operand.Value -> listOf(rankOf(op))
            is Operand.Place -> if (op.snapshot) listOf(readRank(op)) else leaves(op.parts)
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
     * over its ordered parts, and a snapshot place is that path copied. Called inside a
     * [CppBodyState.block] that an [iife] closes.
     */
    fun ordered(op: Operand, lines: MutableList<String>): CppEx = when (op) {
        is Operand.Place -> {
            // The parts first, in source order; a snapshot copies the path over them.
            val texts = op.parts.map { ordered(it, lines) }
            if (op.snapshot) copied(op.expr, false, { op.build(texts) }, lines, op.type, op.rank) else op.build(texts)
        }
        is Operand.Value -> copied(op.expr, op.mutable, op.emit, lines)
    }

    /** [emit] copied into a typed temporary declared in [lines] when [e] (of [type], at [rank]) is not PURE, else its text as written. */
    private fun copied(e: Expr?, mutable: Boolean, emit: () -> CppEx, lines: MutableList<String>, type: KType? = null, rank: Int? = null): CppEx {
        val t = type ?: e?.let { lower.model.typeOrNull(it) }
        val r = rank ?: e?.let { rank(it) } ?: PURE
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

    // ---- lending: which snapshot places must stay where they live ----------------------------

    /**
     * Whether the call [rc] may lend from its argument [i], a snapshot candidate of type [t]:
     * a view the callee takes into it can outlive the call. Nothing lends from a value whose
     * copy owns no storage ([CppLending.borrowable]: a struct of scalars, an `Fx` value). A
     * stdlib binding or a print lends only through a lending result or a `View` parameter;
     * a user function, operator or method through what [CppEscapes] says of the parameter,
     * or through a result that lends ([CppLending.lends]); a virtual, trait, extern or
     * `Fx`-value callee is not seen through, so it lends.
     */
    fun lentArgument(rc: ResolvedCall, i: Int, t: KType): Boolean {
        if (!CppLending.borrowable(t)) {
            return false
        }
        val fn = rc.fn
        return when (rc.kind) {
            CallKind.PRINT -> false
            CallKind.MAGIC -> CppLending.lends(rc.returnType) || (fn?.params?.getOrNull(i)?.let { CppLending.isView(it.type.substitute(rc.substitution)) } ?: true)
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR ->
                CppLending.lends(rc.returnType) || (fn?.params?.getOrNull(i)?.let { escapes.viewEscapes(fn, it) } ?: true)
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }
    }

    /** [lentArgument] for the receiver of [rc], a snapshot candidate of type [t] (the receiver a `mut fx` writes is a place already). */
    fun lentReceiver(rc: ResolvedCall, t: KType): Boolean {
        if (!CppLending.borrowable(t)) {
            return false
        }
        val fn = rc.fn
        return when (rc.kind) {
            CallKind.PRINT -> false
            CallKind.MAGIC -> CppLending.lends(rc.returnType)
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR ->
                CppLending.lends(rc.returnType) || (fn?.let { escapes.viewEscapes(it, null) } ?: true)
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }
    }

    /** [CppLending.lends]. */
    fun lends(t: KType): Boolean = CppLending.lends(t)

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
        val seen = IdentityHashMap<ASTNode, Boolean>()
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

    private fun magicName(t: KType): String? = CppBindingTable.magicName(t)
}

/**
 * What escapes a call, where `EscapePass` (W2.5) has not said: the model's entry
 * (`TypedModel.fxEscapes`, `TypedModel.viewEscapes`) wins when present; an absent one means
 * escaping there, and this class approximates the pass's answer from the callee's typed body,
 * conservatively (it says "escapes" wherever it cannot see), the way `CppHoister.rank`
 * approximates EffectsPass. When the pass lands, its entries take over and these scans are
 * reached only where it wrote none.
 *
 * - [fxEscapes]: an `Fx` parameter does not escape when its body mentions it only as the
 *   callee of a call (`each(buf.slice(at, n))`), outside any lambda; stored, returned, passed,
 *   captured or compared, it escapes. A virtual or trait method's does (no template is virtual).
 * - [viewEscapes]: a view derived from a `const&` parameter, a `View` parameter or the
 *   receiver may outlive the call when the body makes any view at all (an expression of a
 *   lending type, or coerced to a view: `xs.from(at)`, `Win { v = src }`), or hands the
 *   parameter, the receiver or a field of it by reference to a callee this cannot see through
 *   (a virtual, trait, extern or `Fx`-value callee, a stdlib `View` parameter) or one whose
 *   own answer is "escapes" (a fixpoint over the calls, a cycle counting as escaping). A body
 *   the emitter has not got (extern, abstract) escapes.
 */
class CppEscapes(private val model: TypedModel, private val heldByReference: (KType) -> Boolean = ::byReferenceByType) {
    private val fx = IdentityHashMap<ParamSymbol, Boolean>()
    private val views = IdentityHashMap<Symbol, Boolean>()
    private val visiting: MutableSet<Symbol> = Collections.newSetFromMap(IdentityHashMap())
    private val makesViews = IdentityHashMap<FnSymbol, Boolean>()

    /** Whether the `Fx` parameter [p] escapes its function: the model's entry, else the scan's. */
    fun fxEscapes(p: ParamSymbol): Boolean = model.fxEscapes[p] ?: fx.getOrPut(p) { scanFx(p) }

    /**
     * Whether a view derived from [p] of [fn], or from [fn]'s receiver when [p] is null, may
     * outlive a call: the model's entry for the parameter, else the scan's. (The receiver has
     * no model key yet; a `viewEscapes` entry keyed by the method would be read here.)
     */
    fun viewEscapes(fn: FnSymbol, p: ParamSymbol?): Boolean {
        val key: Symbol = p ?: fn
        if (p != null) {
            model.viewEscapes[p]?.let { return it }
        }
        views[key]?.let { return it }
        if (!visiting.add(key)) {
            return true
        }
        val result = scanView(fn, p)
        visiting.remove(key)
        views[key] = result
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

    private fun scanView(fn: FnSymbol, p: ParamSymbol?): Boolean {
        val body = fn.body ?: return true
        if (fn.isVirtual || fn.owner is TraitSymbol) {
            return true
        }
        if (makesViews.getOrPut(fn) { makesViews(body) }) {
            return true
        }
        var escapes = false
        body.forEach { stmt ->
            AstTree.walk(stmt) { node ->
                if (escapes) {
                    return@walk
                }
                val rc = when (node) {
                    is FunctionCallExpr -> model.call(node)
                    is BinaryExpr -> model.opCall(node)
                    else -> null
                } ?: return@walk
                rc.args.forEachIndexed { i, a ->
                    if (a is ArgBinding.Given && byReference(a.expr) && rootedAt(a.expr, fn, p) && argumentEscapes(rc, i)) {
                        escapes = true
                    }
                }
                val receiver = rc.receiver
                if (receiver != null) {
                    if (byReference(receiver) && rootedAt(receiver, fn, p) && receiverEscapes(rc)) {
                        escapes = true
                    }
                } else if (rc.implicitThis && p == null && receiverEscapes(rc)) {
                    escapes = true
                }
            }
        }
        return escapes
    }

    /** Whether [body] makes any view: an expression of a lending type, or one coerced to a view. */
    private fun makesViews(body: List<Statement>): Boolean {
        var found = false
        body.forEach { stmt ->
            AstTree.walk(stmt) { node ->
                if (found || node !is Expr) {
                    return@walk
                }
                if (model.typeOrNull(node)?.let { CppLending.lends(it, paramLends = false) } == true || model.coercion(node) is Coercion.ToView) {
                    found = true
                }
            }
        }
        return found
    }

    private fun argumentEscapes(rc: ResolvedCall, i: Int): Boolean {
        val callee = rc.fn
        return when (rc.kind) {
            CallKind.PRINT -> false
            CallKind.MAGIC -> callee?.params?.getOrNull(i)?.let { CppLending.isView(it.type.substitute(rc.substitution)) } ?: true
            CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> callee?.params?.getOrNull(i)?.let { viewEscapes(callee, it) } ?: true
            CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
        }
    }

    /** A stdlib receiver lends only through its result, which [makesViews] saw where it lends. */
    private fun receiverEscapes(rc: ResolvedCall): Boolean = when (rc.kind) {
        CallKind.PRINT, CallKind.MAGIC -> false
        CallKind.FREE, CallKind.METHOD, CallKind.OP_OVERLOAD, CallKind.CTOR -> rc.fn?.let { viewEscapes(it, null) } ?: true
        CallKind.VIRTUAL, CallKind.TRAIT, CallKind.EXTERN, CallKind.FN_VALUE -> true
    }

    private fun byReference(e: Expr): Boolean = model.typeOrNull(e)?.let { heldByReference(it) } == true

    companion object {
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

    /** Whether the place [e] is [p], `this` of [fn] (when [p] is null), or a field or element of it, at any depth. */
    private fun rootedAt(e: Expr, fn: FnSymbol, p: ParamSymbol?): Boolean {
        var cur: Expr = e
        while (true) {
            cur = when (cur) {
                is Identifier -> return when (val sym = model.symbolOf(cur)) {
                    null -> false
                    is FieldSymbol -> p == null && sym.owner === fn.owner
                    else -> p != null && sym === p
                }
                is ThisExpr -> return p == null
                is MemberAccessExpr -> if (model.member(cur) is MemberRef.Field) cur.origin else return false
                is ArrayIndexExpr -> cur.originExpr
                else -> return false
            }
        }
    }
}
