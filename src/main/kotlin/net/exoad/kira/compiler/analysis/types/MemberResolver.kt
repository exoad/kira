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
 * A type parameter's members are those of its bounds, in declaration order (`s.area()` on an
 * `s: T` with `<T: Shape>`; design 5.5's static dispatch through a generic bound), and `Num`
 * in a `T: Num` method's signature is `T` again (`x.abs()` on a `T: Num` is a `T`).
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
        /**
         * The type whose ancestry [via] was found in: the receiver's own nominal, or, on a type
         * parameter, the bound that reaches [fn] (`Num` for `a.equals(b)` on a `T: Num`, though
         * `equals` is declared by `Equatable`).
         */
        val through: KType.Nominal = via,
    ) {
        fun paramType(i: Int): KType = selfFix(fn.params[i].type.substitute(substitution))
        val returnType: KType get() = selfFix(fn.ret.substitute(substitution))
    }

    /**
     * The Nominals whose declarations list [t]'s members: [t] itself, a scalar's or Str's magic
     * class, or, for a type parameter, each of its bounds in declaration order (design 5.5:
     * static dispatch through a generic bound). A type parameter without a bound has none,
     * and a cyclic bound (`<T: T>`) reaches nothing ([TypeFacts.boundNominals] ends the walk).
     */
    private fun nominalsOf(t: KType): List<KType.Nominal> = when (t) {
        is KType.Nominal -> listOf(t)
        is KType.Scalar, KType.Str -> listOfNotNull(c.builtins.classOf(t)?.let { KType.Nominal(it, emptyList()) })
        is KType.Param -> facts.boundNominals(t)
        else -> emptyList()
    }

    private fun numClass(): ClassSymbol? = c.program.graph.lookup(c.coreModule() ?: return null, "Num") { it is ClassSymbol }
        .let { (it as? ModuleGraph.Lookup.Found)?.symbol as? ClassSymbol }

    /**
     * Inside Num's signatures `Num` means the receiver: an Int8 for `x.abs()` on an Int8, and
     * `T` for `x.abs()` on a `T: Num` (the bound's method, with the parameter's own type back).
     */
    private fun selfFixFor(receiver: KType): (KType) -> KType {
        if (receiver !is KType.Scalar && receiver !is KType.Param) {
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
        for (n in nominalsOf(receiver)) {
            for (a in ancestry(n)) {
                val cls = a.sym as? ClassSymbol ?: continue
                val f = cls.field(name) ?: continue
                return f to f.type.substitute(substitutionOf(a))
            }
        }
        return null
    }

    /** The method [name] of [receiver], nearest declaration first (a type parameter's bounds in order). */
    fun method(receiver: KType, name: String): MethodHit? {
        for (n in nominalsOf(receiver)) {
            for (a in ancestry(n)) {
                val fn = when (val s = a.sym) {
                    is ClassSymbol -> s.methods.firstOrNull { it.name == name && !it.isOperator }
                    is TraitSymbol -> s.methods.firstOrNull { it.name == name && !it.isOperator }
                    else -> null
                } ?: continue
                // Every ancestor's parameters, so a method written in a parent sees its own arguments.
                val sub = LinkedHashMap<TypeParamSymbol, KType>()
                ancestry(n).forEach { sub.putAll(substitutionOf(it)) }
                return MethodHit(fn, sub, a, selfFixFor(receiver), through = n)
            }
        }
        return null
    }

    /**
     * Why [receiver] has no member [name], for a diagnostic: a type parameter reaches only the
     * members of its bounds, so it says which bounds there are (or that there is none).
     */
    fun noMember(receiver: KType, what: String, name: String): String {
        if (receiver !is KType.Param) {
            return "${receiver.display()} has no $what '$name'."
        }
        val bounds = receiver.sym.bounds
        return if (bounds.isEmpty()) {
            "${receiver.display()} is a type parameter without a bound, so no member is reachable on it; " +
                "declare it with the trait that has '$name': <${receiver.display()}: Trait>."
        } else if (facts.boundNominals(receiver).isEmpty()) {
            "${receiver.display()} is a type parameter whose bound" +
                (if (bounds.size == 1) " ${bounds[0].display()} is a type parameter" else "s ${bounds.joinToString(", ") { it.display() }} are type parameters") +
                " leading back to it, so no member is reachable on it; bound it with the trait that has '$name'."
        } else {
            "${receiver.display()} is a type parameter, and only the members of its bound" +
                (if (bounds.size == 1) " ${bounds[0].display()}" else "s ${bounds.joinToString(", ") { it.display() }}") +
                " are reachable on it; none has a $what '$name'."
        }
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
                val called = "${origin.value}.${member.value}"
                c.report(
                    "types.member.static",
                    if ((sym as? ClassSymbol)?.kind == ClassKind.MAGIC && called in CALLED_ON_THE_TYPE) {
                        "'$called' is made by the typer and is only called; to pass it as an Fx, wrap the call in a lambda."
                    } else {
                        "'${origin.value}' is a type; '${member.value}' is reached through a value of it, not through the type."
                    },
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
        c.report("types.member.unknown", noMember(receiver, "field or method", name), member)
        return KType.Error
    }

    companion object {
        private val CALLED_ON_THE_TYPE = setOf(
            "Result.success", "Result.error", "Str.of", "Json.parse", "Json.error", "Json.obj", "Json.arr", "Json.null", "Json.of",
        )
    }
}
