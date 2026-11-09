package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.core.OperatorIntrinsics

/**
 * Ranges: `a..b` is a value of kira:range's `Range<T>`, inclusive at both ends, and `x in r`
 * is a Bool. `x in a..b` builds no Range (the backends lower it to two comparisons); any other
 * right side answers through its type's member `@_op_in_`, recorded in `TypedModel.opCalls`.
 */
internal class RangeTyper(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    /** kira:range's Range, or null when the stdlib has none. */
    fun rangeClass(): ClassSymbol? = c.program.module(MODULE)?.members?.get(NAME) as? ClassSymbol

    /** `Range<T>`. */
    fun rangeOf(t: KType): KType = rangeClass()?.let { KType.Nominal(it, listOf(TypeArg.Ty(t))) } ?: KType.Error

    /** `T` of a `Range<T>`, else null. */
    fun elementOf(t: KType?): KType? =
        (t as? KType.Nominal)?.takeIf { rangeClass() != null && it.sym === rangeClass() }?.typeArgs()?.firstOrNull()

    /**
     * The removed `a.until(b)` (on an integer) and `xs.indices()` (on a Str, List, Arr, View or MutView), refused with the
     * `a..b` form. Null for anything else (a user type's own `until` or `indices`), for ordinary member typing to take over.
     */
    fun removed(call: FunctionCallExpr, ctx: BodyContext, scope: Scope): KType? {
        val access = call.name as? MemberAccessExpr ?: return null
        val name = (access.member as? Identifier)?.takeIf { it.javaClass == Identifier::class.java }?.value ?: return null
        if (call.typeArguments.isNotEmpty() || call.namedParameters.isNotEmpty()) {
            return null
        }
        return when {
            name == "until" && call.positionalParameters.size == 1 -> {
                val bt = c.exprs.synth(access.origin, ctx, scope)
                when {
                    bt.containsError() -> KType.Error
                    !facts.isInteger(bt) -> null
                    else -> {
                        c.exprs.synth(call.positionalParameters[0].value, ctx, scope)
                        c.report(
                            "types.until.removed",
                            "`until` is removed: `a..<b` is the range from a up to b with b left out, and `a..b` holds b too. " +
                                "${KiraUnparser.text(call)} is `${KiraUnparser.text(access.origin)}..<${KiraUnparser.text(call.positionalParameters[0].value)}`.",
                            call,
                        )
                        KType.Error
                    }
                }
            }
            name == "indices" && call.positionalParameters.isEmpty() -> {
                val xt = c.exprs.synth(access.origin, ctx, scope)
                when {
                    xt.containsError() -> KType.Error
                    xt == KType.Str || facts.isList(xt) || facts.isArr(xt) || facts.isView(xt) || facts.isMutView(xt) -> {
                        val xs = KiraUnparser.text(access.origin)
                        c.report(
                            "types.indices.removed",
                            "`indices()` is removed: `0..<n` is the range of the numbers below n, and it is empty when n is 0. " +
                                "$xs.indices() is `0..<$xs.${if (xt == KType.Str) "length" else "size"}()`.",
                            call,
                        )
                        KType.Error
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /** `a..b` as a value: both ends of one number type `T`, a `Range<T>`. */
    fun value(e: RangeExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        if (rangeClass() == null) {
            c.report("types.internal", "internal typer failure: the stdlib has no $MODULE with its $NAME.", e)
            return KType.Error
        }
        val t = ends(listOf(e.begin, e.end), elementOf(hint), e, ctx, scope)
        return if (t.containsError()) KType.Error else rangeOf(t)
    }

    /** `x in r`: two comparisons for a literal range, else the right side's `@_op_in_`. */
    fun membership(e: BinaryExpr, ctx: BodyContext, scope: Scope): KType {
        val range = e.rightExpr as? RangeExpr
        if (range != null) {
            val t = ends(listOf(range.begin, range.end), null, range, ctx, scope, element = e.leftExpr)
            if (!t.containsError()) {
                model.types[range] = rangeOf(t)
            }
            return KType.BOOL
        }
        val rt = c.exprs.synth(e.rightExpr, ctx, scope)
        if (rt.containsError()) {
            c.exprs.synth(e.leftExpr, ctx, scope)
            return KType.BOOL
        }
        val found = inOperator(rt)
        if (found == null) {
            c.exprs.synth(e.leftExpr, ctx, scope)
            c.report(
                "types.in.unsupported",
                "${rt.display()} does not answer `in`: the right side of `in` is a range (a..b), a Range<T>, or a value whose type declares `@${OperatorIntrinsics.IN}: (value: T) Bool`.",
                e,
            )
            return KType.BOOL
        }
        val (fn, sub) = found
        if (fn.params.size != 1 || fn.ret != KType.BOOL) {
            c.exprs.synth(e.leftExpr, ctx, scope)
            c.report(
                "types.in.shape",
                "${rt.display()}'s `@${OperatorIntrinsics.IN}` must take one value and return Bool to answer `in`.",
                e,
            )
            return KType.BOOL
        }
        val wanted = fn.params[0].type.substitute(sub)
        val xt = c.exprs.type(e.leftExpr, wanted, ctx, scope)
        when (val f = c.coercions.fit(e.leftExpr, xt, wanted)) {
            CoercionRules.Fit.Same -> {}
            is CoercionRules.Fit.Coerce -> model.coercions[e.leftExpr] = f.coercion
            is CoercionRules.Fit.No -> {
                inMismatch(e.leftExpr, xt, wanted, rt)
                return KType.BOOL
            }
        }
        model.opCalls[e] = ResolvedCall(
            CallKind.OP_OVERLOAD, fn, e.rightExpr, false, emptyList(),
            listOf(ArgBinding.Given(e.leftExpr, false)), listOf(0), KType.BOOL, sub,
        )
        return KType.BOOL
    }

    /** The `@_op_in_` [t] declares or inherits, with the substitution of the type that declares it. */
    private fun inOperator(t: KType): Pair<FnSymbol, Map<TypeParamSymbol, KType>>? {
        val start = t as? KType.Nominal ?: return null
        val seen = HashSet<KType>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) {
                continue
            }
            val methods = when (val s = n.sym) {
                is ClassSymbol -> s.methods
                is TraitSymbol -> s.methods
                else -> emptyList()
            }
            methods.firstOrNull { it.isOperator && it.name == OperatorIntrinsics.IN }?.let { fn ->
                val params = when (val s = n.sym) {
                    is ClassSymbol -> s.typeParams
                    is TraitSymbol -> s.typeParams
                    else -> emptyList()
                }
                return fn to params.zip(n.typeArgs()).toMap()
            }
            queue.addAll(facts.parents(n))
        }
        return null
    }

    /**
     * Types a range's [sides] (and the [element] `in` tests) as one number type: a literal side
     * takes the others' type (design 3.3), an all-literal range with a float in it is Float64.
     */
    private fun ends(sides: List<Expr>, hint: KType?, at: RangeExpr, ctx: BodyContext, scope: Scope, element: Expr? = null): KType {
        // A written end decides T before the element does: `x in 0..n` is n's type.
        val all = sides + listOfNotNull(element)
        val anchor = all.firstOrNull { !c.literals.isLiteralOnly(it) }
            ?: all.firstOrNull { hasFloat(it) }.takeIf { hint == null }
            ?: all.first()
        val anchorType = c.exprs.type(anchor, hint?.takeIf { facts.isNumeric(it) }, ctx, scope)
        val types = all.associateWith { s -> if (s === anchor) anchorType else c.exprs.type(s, anchorType.takeIf { !it.containsError() }, ctx, scope) }
        if (types.values.any { it.containsError() }) {
            return KType.Error
        }
        val (b, e) = sides.map { types.getValue(it) }
        if (b != e) {
            c.report(
                "types.range.mismatch",
                "A range's two ends have one type; this one is ${b.display()}..${e.display()}" + numericHint(b, e) + ".",
                at,
            )
            return KType.Error
        }
        if (!facts.isNumeric(b)) {
            c.report("types.range.element", "A range holds integers or floats; ${b.display()} is neither.", at)
            return KType.Error
        }
        if (element != null) {
            val xt = types.getValue(element)
            if (xt != b) {
                inMismatch(element, xt, b, rangeOf(b))
                return KType.Error
            }
        }
        return b
    }

    private fun inMismatch(x: Expr, xt: KType, wanted: KType, holder: KType) {
        c.report(
            "types.in.mismatch",
            "`in` against ${holder.display()} takes ${wanted.display()}, but this is ${xt.display()}" + numericHint(xt, wanted) + ".",
            x,
        )
    }

    private fun numericHint(a: KType, b: KType): String = when {
        facts.isInteger(a) && facts.isInteger(b) && (a == KType.SIZE || b == KType.SIZE) ->
            ": Size mixes with no other integer type (D2); convert one with `as`"
        facts.isNumeric(a) && facts.isNumeric(b) -> ": no widening, no int with float; convert one with `as`"
        else -> ""
    }

    private fun hasFloat(e: Expr): Boolean = when (e) {
        is FloatLiteral -> true
        is UnaryExpr -> hasFloat(e.operand)
        is BinaryExpr -> hasFloat(e.leftExpr) || hasFloat(e.rightExpr)
        else -> false
    }

    companion object {
        const val MODULE = "kira:range"
        const val NAME = "Range"
    }
}
