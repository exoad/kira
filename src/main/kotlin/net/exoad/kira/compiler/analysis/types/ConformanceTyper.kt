package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr

/**
 * `value == Identity` and `value != Identity` (stage 3): whether the value conforms to a type, a parent
 * or a function shape, decided by the types or tested at run time; a passing test narrows a name.
 */
internal class ConformanceTyper(private val c: PhaseC) {
    private val facts get() = c.facts

    /** Every class and trait of the program, for "some type could be both". */
    private val types: List<KType.Nominal> by lazy {
        c.program.modules.flatMap { m -> m.declarations }.mapNotNull { s ->
            when (s) {
                is ClassSymbol -> s.takeIf { it.kind == ClassKind.CLASS }?.selfType
                is TraitSymbol -> bare(KType.Nominal(s))
                else -> null
            }
        }
    }

    fun test(e: TypeCheckExpr, ctx: BodyContext, scope: Scope): KType {
        val value = c.exprs.synth(e.value, ctx, scope)
        val identity = c.typeOf(e.type)
        if (value.containsError() || identity.containsError()) {
            return KType.BOOL
        }
        val v = verdict(value, identity, e) ?: return KType.BOOL
        c.model.conformances[e] = v
        val checks = e.checks
        if (checks != null) {
            block(e, checks, v, identity, ctx, scope)
        } else if (v.always) {
            val what = "${article(value)} always conforms to ${identity.display()}: the test is always ${!e.negated}."
            c.report("types.conform.always", what, e, Severity.WARNING)
        }
        return KType.BOOL
    }

    // ---- test blocks (stage 3b) --------------------------------------------------------------

    /** Numbers a held subject, `_k_s0`: a prefix apart from the py frame's `_k_t` temporaries. */
    private var temps = 0

    /**
     * `value == T { check, ... }`: each check a Bool naming a member of T bare (with's scope rule),
     * typed where the test passed, so the subject reads as T; TestBlockPass refuses an effect.
     */
    private fun block(e: TypeCheckExpr, checks: List<Expr>, v: Conformance, identity: KType, ctx: BodyContext, scope: Scope) {
        val t = identity as? KType.Nominal
        if (t == null || !(facts.isClass(t) || facts.isStruct(t) || facts.isTrait(t)) || facts.magicName(t) != null) {
            c.report(
                "types.test.identity",
                "A test block checks the members of a type the program declares; ${identity.display()} is none: test it with '==' and compare outside the block.",
                e,
            )
            return
        }
        val members = c.members.memberNames(t)
        for (outer in scope.withFrames()) {
            val shared = members.intersect(outer.members)
            if (shared.isNotEmpty()) {
                c.report(
                    "types.test.nested",
                    "This test block is inside a block whose type also has ${shared.joinToString(", ") { "'$it'" }}, so a bare name would be ambiguous; " +
                        "check through the value instead: '${KiraUnparser.text(e.value)}.${shared.first()} == ...'.",
                    e,
                )
            }
        }
        val seen = v.narrowed ?: v.value
        val named = (e.value as? Identifier)?.let { c.model.refs[it] }
        val frame = if (named is LocalSymbol || named is ParamSymbol) {
            // The subject itself, read as what the test proved (an outer test's narrowing too).
            val self = Identifier((e.value as Identifier).value)
            val declared = (named as? LocalSymbol)?.type ?: (named as ParamSymbol).type
            val place = if (named is LocalSymbol) Place.Local(named) else Place.Param(named as ParamSymbol)
            c.model.refs[self] = named
            c.model.places[self] = place
            c.model.types[self] = seen
            if (declared != seen) c.model.narrowings[self] = Narrowing(declared, seen)
            c.model.tests[e] = TestInfo(self, null, path = true)
            WithFrame(e, t, null, self, members, ctx.lambda, place, named)
        } else {
            val temp = LocalSymbol("_k_s${temps++}", ctx.module, e, seen, fn = ctx.fn)
            ctx.lambda?.let { c.declaredIn[temp] = it }
            val self = Identifier(temp.name)
            c.model.refs[self] = temp
            c.model.places[self] = Place.Local(temp)
            c.model.types[self] = seen
            c.model.tests[e] = TestInfo(self, temp, path(e.value))
            WithFrame(e, t, temp, self, members, ctx.lambda)
        }
        // The checks run only once the identity test passed, a `!=` test's too.
        val inner = into(scope.withBody(frame), e, !e.negated)
        for (check in checks) {
            frame.reads = 0
            val before = c.program.diagnostics.size
            val ct = c.exprs.type(check, KType.BOOL, ctx, inner)
            when {
                // An error inside the check already says what is wrong with it.
                ct.containsError() || c.program.diagnostics.drop(before).any { it.isError } -> {}
                ct != KType.BOOL -> c.report(
                    "types.test.check",
                    "A check in a test block is a condition on a member of ${t.display()}, 'x == value', never a value by position; " +
                        "this is ${article(ct).replaceFirstChar { it.lowercase() }}.",
                    check,
                )
                frame.reads == 0 -> c.report(
                    "types.test.unnamed",
                    "A check in a test block names a member of ${t.display()} bare, 'x == value'; this one names none.",
                    check,
                )
            }
        }
    }

    /** A subject a backend may read again in place of a temporary: a name, `this`, or fields of one. */
    private fun path(e: Expr): Boolean = when (e) {
        is ThisExpr -> true
        is Identifier -> c.model.refs[e].let { it is LocalSymbol || it is ParamSymbol || it is FieldSymbol || it is GlobalSymbol }
        is MemberAccessExpr -> when (val m = c.model.members[e]) {
            is MemberRef.Field -> path(e.origin)
            is MemberRef.ModuleMember -> m.symbol is GlobalSymbol
            else -> false
        }
        else -> false
    }

    private fun verdict(value: KType, identity: KType, e: TypeCheckExpr): Conformance? {
        refusal(value, identity)?.let {
            c.report("types.conform.unsupported", it, e)
            return null
        }
        if (conforms(value, identity)) {
            return Conformance(value, always = true, subtype = null, narrowed = null)
        }
        val inner = facts.maybeInner(value)
        if (inner != null && conforms(inner, identity)) {
            return Conformance(value, always = false, subtype = null, narrowed = inner)
        }
        val sub = subtype(inner ?: value, identity)
        if (sub == null) {
            val what = "${article(value)} never conforms to ${identity.display()}: the test is always ${e.negated}."
            c.report("types.conform.never", what, e)
            return null
        }
        if ((identity as KType.Nominal).args.isNotEmpty()) {
            c.report("types.conform.args", "A run-time test cannot see type arguments, so ${identity.display()} cannot be tested.", e)
            return null
        }
        return Conformance(value, always = false, subtype = sub, narrowed = identity)
    }

    /** What no conformance test can ask, in words; null when the test is a question. */
    private fun refusal(value: KType, identity: KType): String? = when {
        identity == KType.NullT -> "Nothing conforms to Null: test a Maybe with `m == null`."
        identity == KType.Void || identity == KType.Never -> "Nothing conforms to ${identity.display()}."
        value == KType.NullT || value == KType.Void || value == KType.Never -> "${value.display()} is no value to test."
        value is KType.Param || identity is KType.Param -> "A type parameter is known only where the generic is used, so no test can name it."
        facts.isMagic(value, "Any") -> "A value typed Any cannot be tested."
        else -> null
    }

    /** [value]'s type already conforms to [identity]: the same type, a parent, Any, or a Maybe of one. */
    private fun conforms(value: KType, identity: KType): Boolean =
        value == identity || facts.isMagic(identity, "Any") || (value !is KType.Fn && facts.satisfies(value, identity)) ||
            facts.maybeInner(identity)?.let { conforms(value, it) } == true

    /** The class or trait a run-time test asks [held] to be, when some type of the program is both. */
    private fun subtype(held: KType, identity: KType): Symbol? {
        val h = held as? KType.Nominal ?: return null
        val i = identity as? KType.Nominal ?: return null
        if (!refLike(h) || !refLike(i)) {
            return null
        }
        if (facts.isSubtype(i, h) || types.any { t -> facts.isSubtype(t, h) && facts.isSubtype(t, bare(i)) }) {
            return i.sym as Symbol
        }
        return null
    }

    private fun refLike(t: KType): Boolean = facts.isClass(t) || facts.isTrait(t)

    /** [t] over its own type parameters, as [types] holds it. */
    private fun bare(t: KType.Nominal): KType.Nominal = when (val s = t.sym) {
        is ClassSymbol -> s.selfType
        is TraitSymbol -> KType.Nominal(s, s.typeParams.map { TypeArg.Ty(KType.Param(it)) })
        else -> t
    }

    private fun article(t: KType): String {
        val name = t.display()
        return (if (name.first().lowercaseChar() in "aeiou") "An " else "A ") + name
    }

    // ---- narrowing ---------------------------------------------------------------------------

    /** [scope] (the caller's fresh one) with what [cond] proves when it is [outcome]. */
    fun into(scope: Scope, cond: Expr, outcome: Boolean): Scope {
        proved(cond, outcome).forEach { (sym, t) -> scope.narrow(sym, t) }
        return scope
    }

    /** Each unchanging local or parameter [cond] tests, with its type when [cond] is [outcome]. */
    private fun proved(cond: Expr, outcome: Boolean): List<Pair<Symbol, KType>> = when (cond) {
        is TypeCheckExpr -> {
            val to = c.model.conformances[cond]?.narrowed
            val sym = (cond.value as? Identifier)?.let { c.model.refs[it] }?.takeIf { stable(it) }
            if (to != null && sym != null && cond.negated != outcome) listOf(sym to to) else emptyList()
        }
        is BinaryExpr -> when {
            cond.operator == BinaryOp.AND && outcome -> proved(cond.leftExpr, true) + proved(cond.rightExpr, true)
            cond.operator == BinaryOp.OR && !outcome -> proved(cond.leftExpr, false) + proved(cond.rightExpr, false)
            else -> emptyList()
        }
        is UnaryExpr -> if (cond.operator == UnaryOp.NOT) proved(cond.operand, !outcome) else emptyList()
        else -> emptyList()
    }

    /** A local or parameter no write can reach: no `mut`. */
    private fun stable(sym: Symbol): Boolean = when (sym) {
        is LocalSymbol -> !sym.isMut
        is ParamSymbol -> !sym.byRef
        else -> false
    }
}
