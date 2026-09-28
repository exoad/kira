package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import java.util.IdentityHashMap

/**
 * Thrown by [TypedModel.require] when a fact the C++ backend needs was never recorded: an
 * internal compiler error, never a reason to guess.
 */
class UntypedExpression(val expr: Expr) :
    RuntimeException("internal compiler error: no type was recorded for ${expr.javaClass.simpleName} $expr")

/** A variable a lambda captures (TypedModel.captures). Captures are by value and immutable (spec). */
sealed interface Capture {
    /** A local or parameter of an enclosing function, by value (`[k]`). */
    data class Value(val symbol: Symbol) : Capture

    /** A field of the enclosing struct or class, read through the implicit receiver. */
    data class Field(val field: FieldSymbol) : Capture

    /** The receiver itself: a method call or `this` inside the lambda (`[*this]`, `[this]`, `[self = ...]`). */
    data class This(val owner: TypeSymbol) : Capture
}

enum class LoopKind {
    /** `for i: T in a..b`: exclusive upper bound. */
    RANGE,

    /** The legacy `for mut i: a..b`: inclusive upper bound, with a deprecation warning (D17). */
    RANGE_INCLUSIVE,
    ARR,
    LIST,
    SET,

    /** A `View<T>` or `MutView<T>`. */
    VIEW,

    /** A `Map<K, V>`, iterated as `Tuple2<K, V>` in insertion order (D27). */
    MAP,
}

/**
 * How a `for` loop iterates (TypedModel.loops, keyed by the ForIterationStatement).
 *
 * @property element the loop variable's type (the range's integer type, or the element type).
 * @property variable the loop variable.
 * @property isLegacy the `for mut x: e` form.
 */
data class LoopPlan(
    val kind: LoopKind,
    val element: KType,
    val variable: Symbol?,
    val isLegacy: Boolean,
)

/**
 * Purity (EffectsPass), ordered: what evaluating an expression (or calling a function) does
 * to its siblings under D33. An absent entry means [IMPURE].
 *
 * - [PURE]: no effect, and a value only its own evaluation can change (locals, by-value
 *   parameters, constants, and what they hold by copy). It may be evaluated in any order
 *   against any sibling.
 * - [READS]: no effect, but it observes state a sibling's effect could change: a `mut`
 *   global or `mut` parameter, a field of a class, the elements of a view, or what a view or
 *   reference receiver borrows. Two READS siblings need no ordering; a READS beside an
 *   [IMPURE] sibling does.
 * - [IMPURE]: an effect: a write outside the writer's own locals, a print, a call of an
 *   extern, a virtual or an `Fx` value, a magic binding without `pure: true`, or a `throw`.
 *
 * The C++ emitter spills the operands of a call or operator into typed temporaries, in
 * source order, when one of them is IMPURE and another is not PURE (R19). Every non-PURE
 * operand is copied then, the READS ones included: `add2(G, bump())`, where `bump` writes
 * `G`, reads `G` first in Kira (D33), and C++ does the same only as `t0 = G; t1 = bump();
 * add2(t0, t1)`; copying `bump()` alone would run it before `G` is read. The operands the
 * emitter cannot copy, because C++ takes them by reference (a `mut` argument, a receiver, the
 * target of a compound assignment, whose old value Kira reads first), are ExclusivityPass's:
 * it refuses a sibling whose write, visible or hidden in its callee, reaches such an operand
 * (`rules.exclusivity.order`), and leaves every other read to the spill; a place that is only
 * located (an assignment's target, `xs[G] = f()`) is bound by reference in order.
 */
enum class Effect { PURE, READS, IMPURE }

/**
 * Where the storage a second-class value points into lives (ViewPass, design 30-second-class
 * 2.3): TypedModel.viewOrigins, keyed by every second-class expression (a `View`, `MutView`,
 * `CStr` or `Unsafe` value, a place the typer converts to a view, a lambda that captures a
 * view parameter). The C++ emitter reads it to lower views (30-second-class 3.4) and never
 * recomputes it.
 */
sealed interface ViewOrigin {
    /** A second-class parameter [param] of the enclosing function or of an enclosing lambda: the caller checked its storage for the whole call. */
    data class Param(val param: ParamSymbol) : ViewOrigin

    /** A string literal's own text, or a non-`mut` global that is not a `Str` (an `Arr` constant). */
    data object Static : ViewOrigin

    /**
     * The storage of the place [place], which the checker keeps from moving while the view
     * is in use. [within] when the view may point anywhere inside it (a method's result on
     * its receiver, the object behind a reference) rather than at [place]'s own buffer
     * (`xs.view()`). [type] is the place's type; [kind] its class (30-second-class 2.3).
     */
    data class Stored(
        val place: Place,
        val kind: PlaceKind,
        val within: Boolean,
        val type: KType?,
    ) : ViewOrigin

    /** A temporary the full-expression makes, [owner] the expression that owns it (a call result, a construction, an operator, a Str constant). C++ keeps it to the end of the full-expression. */
    data class Temp(val owner: Expr) : ViewOrigin
}

/** How far a [ViewOrigin.Stored] place is shared (30-second-class 2.3). */
enum class PlaceKind {
    /** Rooted at a local of this body or a by-value parameter through value steps only: nothing else can name it. */
    PRIVATE,

    /** Rooted at a `mut` global through value steps only. */
    GLOBAL,

    /** Everything else: a `const&` or `mut` parameter, `this`, or any step through a reference. */
    SHARED,
}

/**
 * Every fact the typer establishes, in side tables keyed by AST node **identity**:
 * `Identifier.equals` compares by value, so two `x` identifiers would collide in a HashMap.
 * Every table here is a [java.util.IdentityHashMap].
 *
 * Who fills what: phase B (W1.2) fills [typeRefs], [aliasRefs], [declSyms], [refs] for
 * declared names and type names, and [consts] for folded module-level initializers, enum
 * values and defaults. Phase C (W2.1) fills every table but [effects], [fnEffects],
 * [fxEscapes] and [viewOrigins], which the rule passes (W2.5) fill.
 */
class TypedModel {
    /** The type of every expression. */
    val types: IdentityHashMap<Expr, KType> = IdentityHashMap()

    /** What each identifier refers to (uses, and declared names). */
    val refs: IdentityHashMap<Identifier, Symbol> = IdentityHashMap()

    /** Every `Type` node in the program, resolved. */
    val typeRefs: IdentityHashMap<Type, KType> = IdentityHashMap()

    /** The Type nodes that were spelled through an alias, and which alias. */
    val aliasRefs: IdentityHashMap<Type, AliasSymbol> = IdentityHashMap()

    val calls: IdentityHashMap<FunctionCallExpr, ResolvedCall> = IdentityHashMap()

    /** Operators that resolve to an `@op_*` overload, keyed by the BinaryExpr or UnaryExpr. */
    val opCalls: IdentityHashMap<Expr, ResolvedCall> = IdentityHashMap()
    val inits: IdentityHashMap<ObjectInitExpr, ResolvedInit> = IdentityHashMap()
    val members: IdentityHashMap<MemberAccessExpr, MemberRef> = IdentityHashMap()

    /** Implicit conversions, keyed by the expression converted. */
    val coercions: IdentityHashMap<Expr, Coercion> = IdentityHashMap()

    /** Assignable locations, keyed by the expression that denotes them. */
    val places: IdentityHashMap<Expr, Place> = IdentityHashMap()

    /** Compile-time values. */
    val consts: IdentityHashMap<Expr, ConstValue> = IdentityHashMap()
    val conversions: IdentityHashMap<TypeCastExpr, ConversionKind> = IdentityHashMap()
    val captures: IdentityHashMap<LambdaExpr, List<Capture>> = IdentityHashMap()
    val loops: IdentityHashMap<ForIterationStatement, LoopPlan> = IdentityHashMap()

    /** Whether an if-expression can become a C++ ternary (R18). */
    val ifShape: IdentityHashMap<IfExpr, Boolean> = IdentityHashMap()

    /** Purity per expression (EffectsPass); an absent entry means [Effect.IMPURE]. */
    val effects: IdentityHashMap<Expr, Effect> = IdentityHashMap()

    /** Purity per function (EffectsPass); an absent entry means [Effect.IMPURE]. */
    val fnEffects: IdentityHashMap<FnSymbol, Effect> = IdentityHashMap()

    /** The symbol each declaring node introduces (declarations, fields, parameters, entries, type parameters). */
    val declSyms: IdentityHashMap<ASTNode, Symbol> = IdentityHashMap()

    /** Whether an `Fx` parameter escapes (EscapePass); an absent entry means ESCAPING, so `std::function`. */
    val fxEscapes: IdentityHashMap<ParamSymbol, Boolean> = IdentityHashMap()

    /** Where each second-class expression points (ViewPass, [ViewOrigin]); absent for every first-class one. */
    val viewOrigins: IdentityHashMap<Expr, Set<ViewOrigin>> = IdentityHashMap()

    fun typeOrNull(e: Expr): KType? = types[e] ?: (e as? Type)?.let { typeRefs[it] }

    /** The type of [e]. The C++ backend uses only this: a missing type is an internal compiler error. */
    fun require(e: Expr): KType = typeOrNull(e) ?: throw UntypedExpression(e)

    /** The resolved type of a `Type` node, or null. */
    fun typeOf(t: Type): KType? = typeRefs[t]

    fun symbolOf(id: Identifier): Symbol? = refs[id]

    /** The symbol a declaring node introduces, or null. */
    fun declSymbol(node: ASTNode): Symbol? = declSyms[node]

    fun call(c: FunctionCallExpr): ResolvedCall? = calls[c]

    fun opCall(e: Expr): ResolvedCall? = opCalls[e]

    fun init(o: ObjectInitExpr): ResolvedInit? = inits[o]

    fun member(m: MemberAccessExpr): MemberRef? = members[m]

    fun coercion(e: Expr): Coercion? = coercions[e]

    fun place(e: Expr): Place? = places[e]

    fun const(e: Expr): ConstValue? = consts[e]

    fun conversion(c: TypeCastExpr): ConversionKind? = conversions[c]

    fun captures(l: LambdaExpr): List<Capture>? = captures[l]

    fun loop(f: ForIterationStatement): LoopPlan? = loops[f]

    fun ifShape(e: IfExpr): Boolean? = ifShape[e]

    fun effect(e: Expr): Effect = effects[e] ?: Effect.IMPURE

    fun effect(fn: FnSymbol): Effect = fnEffects[fn] ?: Effect.IMPURE

    /** True unless EscapePass proved the parameter does not escape. */
    fun fxEscapes(p: ParamSymbol): Boolean = fxEscapes[p] ?: true

    /** The origins ViewPass recorded for the second-class expression [e]; empty for a first-class one. */
    fun viewOrigins(e: Expr): Set<ViewOrigin> = viewOrigins[e].orEmpty()
}
