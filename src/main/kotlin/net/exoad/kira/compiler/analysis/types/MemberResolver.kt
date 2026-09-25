package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr

/**
 * Members: which field or method a name reaches on a receiver type, with the receiver's type
 * arguments substituted, and the value form `a.b` (fields, enum entries, a module's members).
 *
 * Lookup walks the receiver's type and then its parents, nearest first: a class, then its
 * superclass chain, then the traits (whose default bodies a class inherits); a trait, then its
 * parent traits. A scalar's members come from its magic class and, through it, from `Num`;
 * inside Num's signatures `Num` itself means the receiver's own type (`x.abs()` on an Int8 is
 * an Int8). `Str`, the containers, `Maybe`, `View` and the rest come from their magic classes.
 *
 * Member access on a `Maybe` is limited to its API (design 3.3, D40): `isSome`, `isNone`,
 * `isNull`, `unwrap`, `unwrapOr` and the `value` field. Anything else must unwrap first.
 */
internal class MemberResolver(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    /** A method reached on a receiver: the declaration and its signature after substitution. */
    data class MethodHit(
        val fn: FnSymbol,
        /** The receiver's (and its parents') type parameters to their arguments. */
        val substitution: Map<TypeParamSymbol, KType>,
        /** The type whose declaration holds [fn], with its arguments. */
        val via: KType.Nominal,
        /** Maps `Num` to the receiver scalar in a Num method's signature, else identity. */
        val selfFix: (KType) -> KType,
    ) {
        fun paramType(i: Int): KType = selfFix(fn.params[i].type.substitute(substitution))
        val returnType: KType get() = selfFix(fn.ret.substitute(substitution))
    }

    /** The receiver type as a Nominal whose declaration lists its members. */
    private fun asNominal(t: KType): KType.Nominal? = when (t) {
        is KType.Nominal -> t
        is KType.Scalar, KType.Str -> c.builtins.classOf(t)?.let { KType.Nominal(it, emptyList()) }
        else -> null
    }

    private fun numClass(): ClassSymbol? = c.program.graph.lookup(c.coreModule() ?: return null, "Num") { it is ClassSymbol }
        .let { (it as? ModuleGraph.Lookup.Found)?.symbol as? ClassSymbol }

    private fun selfFixFor(receiver: KType): (KType) -> KType {
        if (receiver !is KType.Scalar) {
            return { it }
        }
        val num = numClass() ?: return { it }
        return { t -> replaceNominal(t, num, receiver) }
    }

    private fun replaceNominal(t: KType, sym: TypeSymbol, with: KType): KType = when (t) {
        is KType.Nominal -> if (t.sym === sym) with else KType.Nominal(t.sym, t.args.map { a ->
            if (a is TypeArg.Ty) TypeArg.Ty(replaceNominal(a.t, sym, with)) else a
        })
        is KType.Fn -> KType.Fn(t.params.map { FnParam(replaceNominal(it.type, sym, with), it.byRef) }, replaceNominal(t.ret, sym, with))
        else -> t
    }

    /** The types [t] reaches, nearest first (itself included), each with its arguments substituted. */
    private fun ancestry(t: KType.Nominal): List<KType.Nominal> {
        val out = mutableListOf<KType.Nominal>()
        val seen = HashSet<KType>()
        val queue = ArrayDeque<KType.Nominal>()
        queue.add(t)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) {
                continue
            }
            out.add(n)
            queue.addAll(facts.parents(n))
        }
        return out
    }

    private fun substitutionOf(n: KType.Nominal): Map<TypeParamSymbol, KType> {
        val params = when (val s = n.sym) {
            is ClassSymbol -> s.typeParams
            is TraitSymbol -> s.typeParams
            else -> emptyList()
        }
        return params.zip(n.typeArgs()).toMap()
    }

    /** The field [name] of [receiver] (inherited ones included) and its type there. */
    fun field(receiver: KType, name: String): Pair<FieldSymbol, KType>? {
        val n = asNominal(receiver) ?: return null
        for (a in ancestry(n)) {
            val cls = a.sym as? ClassSymbol ?: continue
            val f = cls.field(name) ?: continue
            return f to f.type.substitute(substitutionOf(a))
        }
        return null
    }

    /** The method [name] of [receiver], nearest declaration first. */
    fun method(receiver: KType, name: String): MethodHit? {
        val n = asNominal(receiver) ?: return null
        for (a in ancestry(n)) {
            val fn = when (val s = a.sym) {
                is ClassSymbol -> s.methods.firstOrNull { it.name == name && !it.isOperator }
                is TraitSymbol -> s.methods.firstOrNull { it.name == name && !it.isOperator }
                else -> null
            } ?: continue
            // Every ancestor's parameters, so a method written in a parent sees its own arguments.
            val sub = LinkedHashMap<TypeParamSymbol, KType>()
            ancestry(n).forEach { sub.putAll(substitutionOf(it)) }
            return MethodHit(fn, sub, a, selfFixFor(receiver))
        }
        return null
    }

    /** The Maybe API: the only members reachable on a `Maybe<T>` without unwrapping (D40). */
    val maybeApi = setOf("isSome", "isNone", "isNull", "unwrap", "unwrapOr", "value")

    /**
     * `a.b` as a value: an enum entry, a module's member, or a field. A method named without a
     * call is an error (bind it in a lambda); a member call `a.m()` of the parser's
     * CONJUNCTIVE_DOT shape goes to [CallResolver].
     */
    fun access(e: MemberAccessExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val member = e.member
        if (member is FunctionCallExpr) {
            return c.calls.memberCall(e, member, hint, ctx, scope)
        }
        val name = (member as? Identifier)?.takeIf { it !is IntrinsicExpr }?.value
        if (name == null) {
            c.exprs.synth(e.origin, ctx, scope)
            c.report("types.member.shape", "After '.' comes a member name or a call; '${KiraUnparser.text(member)}' is neither.", member)
            return KType.Error
        }
        val origin = e.origin
        if (origin is Identifier && origin !is IntrinsicExpr && !c.exprs.namesValue(origin, ctx, scope)) {
            staticAccess(e, origin, member as Identifier, ctx)?.let { return it }
        }
        val receiver = c.exprs.synth(origin, ctx, scope)
        if (receiver.containsError()) {
            return KType.Error
        }
        return fieldAccess(e, receiver, member as Identifier)
    }

    /** `E.ENTRY` and `module.member`: the origin names a type or a module, not a value. */
    private fun staticAccess(e: MemberAccessExpr, origin: Identifier, member: Identifier, ctx: BodyContext): KType? {
        when (val found = c.program.graph.lookup(ctx.module, origin.value) { it is EnumSymbol || it is ClassSymbol || it is TraitSymbol }) {
            is ModuleGraph.Lookup.Found, is ModuleGraph.Lookup.NotVisible, is ModuleGraph.Lookup.Ambiguous -> {
                val sym = when (found) {
                    is ModuleGraph.Lookup.Found -> found.symbol
                    is ModuleGraph.Lookup.NotVisible -> found.symbol
                    is ModuleGraph.Lookup.Ambiguous -> found.first.symbol
                    else -> null
                }!!
                model.refs[origin] = sym
                if (sym is EnumSymbol) {
                    val entry = sym.entry(member.value)
                    if (entry == null) {
                        c.report(
                            "types.enum.unknown-entry",
                            "enum ${sym.name} has no entry '${member.value}'. Its entries are: ${sym.entries.joinToString(", ") { it.name }}.",
                            member,
                        )
                        return KType.Error
                    }
                    model.members[e] = MemberRef.EnumEntry(entry)
                    return KType.Nominal(sym)
                }
                c.report(
                    "types.member.static",
                    "'${origin.value}' is a type; '${member.value}' is reached through a value of it, not through the type.",
                    e,
                )
                return KType.Error
            }
            ModuleGraph.Lookup.Missing -> {}
        }
        val module = ctx.module.imports.firstOrNull { it.pathSegments.lastOrNull() == origin.value } ?: return null
        val sym = module.members[member.value]
        if (sym == null || !(sym is GlobalSymbol || sym is FnSymbol)) {
            c.report("types.member.unknown", "Module ${module.uri} has no value named '${member.value}'.", member)
            return KType.Error
        }
        model.members[e] = MemberRef.ModuleMember(module, sym)
        return when (sym) {
            is GlobalSymbol -> {
                model.places[e] = Place.Global(sym)
                sym.constValue?.let { if (sym.isConstant) model.consts[e] = it }
                sym.type
            }
            is FnSymbol -> {
                model.coercions[e] = Coercion.FnRef(sym)
                sym.fnType
            }
            else -> KType.Error
        }
    }

    /** `a.f` where `a` has type [receiver]: a field, `Maybe.value`, or an error. */
    private fun fieldAccess(e: MemberAccessExpr, receiver: KType, member: Identifier): KType {
        val name = member.value
        if (facts.isMaybe(receiver) && name !in maybeApi) {
            c.report(
                "types.maybe.member",
                "'$name' is not available on a ${receiver.display()}: a Maybe offers only isSome, isNone, isNull, " +
                    "unwrap, unwrapOr and value. Unwrap it first, for example m.unwrap().$name.",
                member,
            )
            return KType.Error
        }
        val found = field(receiver, name)
        if (found != null) {
            val (f, type) = found
            model.members[e] = MemberRef.Field(f, type)
            model.places[e] = Place.Field(model.places[e.origin], f)
            return type
        }
        if (method(receiver, name) != null) {
            c.report(
                "types.member.method-value",
                "'$name' is a method of ${receiver.display()}; call it (${KiraUnparser.text(e)}(...)), or wrap the call in a lambda to pass it on.",
                member,
            )
            return KType.Error
        }
        c.report("types.member.unknown", "${receiver.display()} has no field or method '$name'.", member)
        return KType.Error
    }
}
