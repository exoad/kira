package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
import net.exoad.kira.core.NamedArguments
import java.util.IdentityHashMap

/**
 * Calls and constructions (design 3.3).
 *
 * **Shapes.** `a.m(x)` reaches the typer in two parser shapes, `FunctionCallExpr(MemberAccessExpr(a,
 * m))` and, from the CONJUNCTIVE_DOT operator, `MemberAccessExpr(a, FunctionCallExpr(m))`. Both
 * resolve to the same [ResolvedCall] on the FunctionCallExpr, with `receiver = a`; in the second
 * shape the outer MemberAccessExpr carries the call's type too. The callee name of a method call
 * gets a `refs` entry and no type of its own.
 *
 * **Resolution order of a bare name `f(...)`**: a local `Fx` value; a method of the enclosing
 * type (`implicitThis`), then its `Fx` field; a module function, then an imported one, then the
 * ambient stdlib (ModuleGraph.lookup's order); then the print family (`trace`, and kira:io's
 * `print`, `println`, `eprint`, which are [CallKind.PRINT] wherever they resolve).
 *
 * **Binding.** Arguments bind by position, then by name, by the rules of core/NamedArguments
 * (whose `bind` this cross-checks), extended with defaults: an omitted parameter with a
 * default is `Default(trailing = true)` when every later parameter is omitted too, else
 * `Default(trailing = false)` (R6). `args` is in parameter order; `sourceOrder` lists the
 * given arguments' indices in the order they were written (D33).
 *
 * **mut (D4).** A `mut` parameter takes a call-site `mut` and a mutable place of exactly its
 * type; a `mut` at the call on a parameter that is not `mut` is an error. A `mut fx` called
 * inside a lambda on a capture (a copied local, parameter or struct receiver) is an error too:
 * the capture is immutable (spec), and a copy would be written silently.
 *
 * **Receivers of a type parameter** reach the members of the parameter's bounds (design 5.5):
 * `s.area()` on `s: T` with `<T: Shape>` is a [CallKind.TRAIT] call through the bound, and
 * `x.abs()` on `<T: Num>` a [CallKind.MAGIC] one whose result is `T`.
 *
 * **Generics** take explicit type arguments; only a `@_magic @_infer` stdlib function infers them
 * (D20), from the arguments that are not bare literals, then from the expected type, then from the
 * literals' own defaults, and each inferred argument must satisfy its bound.
 *
 * **Special cases**: `bitCast<T>(v)` (equal sizes), `enumOf<E>(raw)` (an integer enum, a
 * `Maybe<E>`), `Result.success(v)` / `Result.error(e)` (D39, typed by the context), and
 * `Str.of(bytes)` (D55, a Str read from UTF-8).
 */
internal class CallResolver(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    private val printNames = setOf("trace", "print", "println", "eprint")

    /** Containers a `T { }` constructs empty: their `require` storage field is not required. */
    private val emptyConstructible = setOf("List", "Map", "Set", "Stack", "Queue", "Deque")

    fun call(e: FunctionCallExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType = when (val name = e.name) {
        is IntrinsicExpr -> if (name.intrinsicKey.name == "_trace_") print(e, null, ctx, scope) else {
            argsOnly(e, ctx, scope)
            c.report("types.intrinsic.call", "@${name.intrinsicKey.name} cannot be called here.", e)
            KType.Error
        }
        is Identifier -> named(e, name, hint, ctx, scope)
        is MemberAccessExpr -> {
            val member = name.member
            if (member is Identifier && member !is IntrinsicExpr) {
                viaReceiver(e, name.origin, member, hint, ctx, scope)
            } else {
                c.exprs.synth(name.origin, ctx, scope)
                argsOnly(e, ctx, scope)
                c.report("types.member.shape", "After '.' comes a method name; '${KiraUnparser.text(member)}' is not one.", member)
                KType.Error
            }
        }
        else -> {
            val ct = c.exprs.synth(name, ctx, scope)
            if (ct is KType.Fn) fnValue(e, ct, null, false, ctx, scope) else {
                argsOnly(e, ctx, scope)
                if (!ct.containsError()) {
                    c.report("types.call.not-callable", "${ct.display()} is not a function; only an Fx value can be called.", name)
                }
                KType.Error
            }
        }
    }

    /** The CONJUNCTIVE_DOT shape `MemberAccessExpr(a, FunctionCallExpr(m, ...))`. */
    fun memberCall(outer: MemberAccessExpr, call: FunctionCallExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val name = call.name as? Identifier
        val t = if (name == null || name is IntrinsicExpr) {
            c.exprs.synth(outer.origin, ctx, scope)
            argsOnly(call, ctx, scope)
            c.report("types.member.shape", "After '.' comes a method name.", call)
            KType.Error
        } else {
            viaReceiver(call, outer.origin, name, hint, ctx, scope)
        }
        model.types[call] = t
        return t
    }

    // ---- bare names --------------------------------------------------------------------------

    private fun named(e: FunctionCallExpr, id: Identifier, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val name = id.value
        // 1. A local Fx value.
        scope.find(name)?.let { found ->
            val type = (found.symbol as? LocalSymbol)?.type ?: (found.symbol as? ParamSymbol)?.type
            if (type is KType.Fn) {
                val ct = c.exprs.synth(id, ctx, scope)
                return fnValue(e, ct as? KType.Fn ?: type, null, false, ctx, scope)
            }
            if (type != null && !type.containsError()) {
                c.exprs.synth(id, ctx, scope)
                argsOnly(e, ctx, scope)
                c.report(
                    "types.call.shadowed",
                    "'$name' here is the ${type.display()} declared in this body, not a function; rename one of them.",
                    id,
                )
                return KType.Error
            }
        }
        // 2. A method of the enclosing type, then an Fx field of it.
        ctx.owner?.let { owner ->
            val self = c.selfType(owner)
            c.members.method(self, name)?.let { hit ->
                c.exprs.captureThis(ctx, owner, null)
                return method(e, null, self, hit, id, hint, implicitThis = true, ctx = ctx, scope = scope)
            }
            c.members.field(self, name)?.let { (_, type) ->
                val ct = c.exprs.synth(id, ctx, scope)
                if (type is KType.Fn && ct is KType.Fn) {
                    return fnValue(e, ct, null, true, ctx, scope)
                }
                argsOnly(e, ctx, scope)
                if (!ct.containsError()) {
                    c.report("types.call.shadowed", "'$name' here is a field of ${self.display()} (a ${ct.display()}), not a function.", id)
                }
                return KType.Error
            }
        }
        // 3-5. The module, then what it uses, then the ambient stdlib.
        val found = c.program.graph.lookup(ctx.module, name) { it is FnSymbol || it is GlobalSymbol }
        val sym = when (found) {
            is ModuleGraph.Lookup.Found -> found.symbol
            is ModuleGraph.Lookup.NotVisible -> found.symbol
            is ModuleGraph.Lookup.Ambiguous -> {
                c.report(
                    "types.call.ambiguous",
                    "'$name' is exported by more than one used module: " +
                        (listOf(found.first) + found.others).joinToString(", ") { it.from.uri } + ".",
                    id,
                )
                found.first.symbol
            }
            ModuleGraph.Lookup.Missing -> null
        }
        when (sym) {
            is FnSymbol -> return free(e, sym, id, hint, ctx, scope)
            is GlobalSymbol -> {
                val ct = c.exprs.synth(id, ctx, scope)
                if (ct is KType.Fn) {
                    return fnValue(e, ct, null, false, ctx, scope)
                }
                argsOnly(e, ctx, scope)
                if (!ct.containsError()) {
                    c.report("types.call.not-callable", "'$name' is a ${ct.display()}, not a function.", id)
                }
                return KType.Error
            }
            else -> {}
        }
        // 6. The print family.
        if (name in printNames) {
            return print(e, null, ctx, scope)
        }
        argsOnly(e, ctx, scope)
        val asType = c.program.graph.lookup(ctx.module, name) { it is ClassSymbol || it is EnumSymbol || it is TraitSymbol }
        if (asType !is ModuleGraph.Lookup.Missing || Builtins.isBuiltinName(name)) {
            c.report("types.call.type-name", "'$name' is a type; construct a value of it with $name { ... }.", id)
        } else {
            c.report("types.call.unknown", "Unknown function '$name'.", id)
        }
        return KType.Error
    }

    // ---- receivers ---------------------------------------------------------------------------

    private fun viaReceiver(e: FunctionCallExpr, origin: Expr, member: Identifier, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        if (origin is Identifier && origin !is IntrinsicExpr && !c.exprs.namesValue(origin, ctx, scope)) {
            staticCall(e, origin, member, hint, ctx, scope)?.let { return it }
        }
        val recv = c.exprs.synth(origin, ctx, scope)
        if (recv.containsError()) {
            argsOnly(e, ctx, scope)
            return KType.Error
        }
        val name = member.value
        if (facts.isMaybe(recv) && name !in c.members.maybeApi) {
            argsOnly(e, ctx, scope)
            c.report(
                "types.maybe.member",
                "'$name' is not available on a ${recv.display()}: a Maybe offers only isSome, isNone, isNull, unwrap, " +
                    "unwrapOr and value. Unwrap it first, for example m.unwrap().$name(...).",
                member,
            )
            return KType.Error
        }
        val hit = c.members.method(recv, name)
        if (hit == null) {
            val field = c.members.field(recv, name)
            val callee = e.name as? MemberAccessExpr
            if (field != null && field.second is KType.Fn && callee != null) {
                model.members[callee] = MemberRef.Field(field.first, field.second)
                model.types[callee] = field.second
                model.places[origin]?.let { model.places[callee] = Place.Field(it, field.first) }
                return fnValue(e, field.second as KType.Fn, origin, false, ctx, scope)
            }
            argsOnly(e, ctx, scope)
            c.report("types.member.unknown", c.members.noMember(recv, "method", name), member)
            return KType.Error
        }
        return method(e, origin, recv, hit, member, hint, implicitThis = false, ctx = ctx, scope = scope)
    }

    /** `Result.success(v)`, `E.x()`, `module.f(...)`: the origin names a type or a module. */
    private fun staticCall(e: FunctionCallExpr, origin: Identifier, member: Identifier, hint: KType?, ctx: BodyContext, scope: Scope): KType? {
        val asType = c.program.graph.lookup(ctx.module, origin.value) { it is ClassSymbol || it is EnumSymbol || it is TraitSymbol }
        val typeSym = (asType as? ModuleGraph.Lookup.Found)?.symbol ?: (asType as? ModuleGraph.Lookup.NotVisible)?.symbol
            ?: (asType as? ModuleGraph.Lookup.Ambiguous)?.first?.symbol
            ?: if (Builtins.isBuiltinName(origin.value)) c.builtins.classFor(origin.value) else null
        if (typeSym != null) {
            model.refs[origin] = typeSym
            if (typeSym is ClassSymbol && typeSym.kind == ClassKind.MAGIC && typeSym.name == "Result" && member.value in setOf("success", "error")) {
                return result(e, typeSym, member, hint, ctx, scope)
            }
            if (typeSym is ClassSymbol && typeSym.kind == ClassKind.MAGIC && typeSym.name == "Str" && member.value == "of") {
                return strOf(e, typeSym, ctx, scope)
            }
            argsOnly(e, ctx, scope)
            c.report(
                "types.call.static",
                "'${origin.value}' is a type: '${member.value}' is called on a value of it" +
                    (if (typeSym is EnumSymbol) "; an enum has no methods (D25): use enumOf<${origin.value}>(raw) for the entry of a value" else "") + ".",
                e,
            )
            return KType.Error
        }
        val module = ctx.module.imports.firstOrNull { it.pathSegments.lastOrNull() == origin.value } ?: return null
        val fn = module.members[member.value] as? FnSymbol
        if (fn == null) {
            argsOnly(e, ctx, scope)
            c.report("types.call.unknown", "Module ${module.uri} has no function '${member.value}'.", member)
            return KType.Error
        }
        (e.name as? MemberAccessExpr)?.let { model.members[it] = MemberRef.ModuleMember(module, fn) }
        return free(e, fn, null, hint, ctx, scope)
    }

    /**
     * How a method call reaches [fn] on a receiver of type [recv]. On a type parameter [hit]
     * says which bound reaches the method (design 5.5, static dispatch through the bound): a
     * magic bound such as `Num` makes it [CallKind.MAGIC] (the `Num.equals` binding, even for
     * the `equals` that `Equatable` declares, as on a scalar receiver), a trait bound
     * [CallKind.TRAIT], a class bound the class's own kind.
     */
    fun kindFor(recv: KType, fn: FnSymbol, hit: MemberResolver.MethodHit? = null): CallKind = when {
        // R-G (40-round3): every function whose body C++ supplies is called as an extern, so
        // the C++ lowering routes it through CppExternEmitter.call (`.data()`, `.c_str()`,
        // `kira::ffi::in`/`out`), whether its marker is `@_extern` or it is a bodiless method of
        // an `@_opaque` class.
        fn.suppliedByCpp -> CallKind.EXTERN
        recv is KType.Scalar || recv == KType.Str || facts.magicName(recv) != null -> CallKind.MAGIC
        fn.foreign is Foreign.Magic -> CallKind.MAGIC
        recv is KType.Param && hit != null && facts.magicName(hit.through) != null -> CallKind.MAGIC
        facts.isTrait(recv) || (recv is KType.Param && hit?.via?.sym is TraitSymbol) -> CallKind.TRAIT
        fn.isVirtual -> CallKind.VIRTUAL
        else -> CallKind.METHOD
    }

    private fun method(
        e: FunctionCallExpr,
        receiver: Expr?,
        recv: KType,
        hit: MemberResolver.MethodHit,
        nameNode: Identifier,
        hint: KType?,
        implicitThis: Boolean,
        ctx: BodyContext,
        scope: Scope,
    ): KType {
        val fn = hit.fn
        if (implicitThis) {
            model.refs[nameNode] = fn
        }
        val own = ownTypeArgs(e, fn, "${recv.display()}.${fn.name}", allowInfer = false) ?: emptyMap()
        val paramTypes = fn.params.indices.map { hit.paramType(it).substitute(own) }
        var ret = hit.returnType.substitute(own)
        // An Arr or List that is a mutable place lends a MutView: `p.from(4)` on `mut p: Frame`
        // writes through, and reads convert to a View implicitly (ToView). Inside a lambda, a
        // variable it captured is an immutable copy (spec), so it lends a View: typed a MutView,
        // a read `sumV(xs.view())` was lowered `kira::mutView(xs)` over the lambda's const copy
        // and g++ and clang rejected it ('no matching function for call to mutView', measured
        // on the round-3 integration); a write through it is [lendsCapture]'s refusal.
        if (receiver != null && fn.name in LENDERS && (facts.isArr(recv) || facts.isList(recv)) && facts.isView(ret)) {
            val place = model.places[receiver]
            if (place != null && c.isMutablePlace(place, ctx) && !c.writesCapture(place, ctx)) {
                ret = facts.mutViewOf(facts.elementOf(ret) ?: KType.Error)
            }
        }
        val bound = bind(e, "${recv.display()}.${fn.name}", fn.params) ?: run {
            argsOnly(e, ctx, scope)
            return ret
        }
        typeGiven(
            e, bound, paramTypes, fn.params.map { it.byRef }, fn.params.map { it.name }, IdentityHashMap(), ctx, scope,
            strBufText = facts.isStrBuf(recv) && fn.name in setOf("set", "add"),
            externCallee = isExternLike(fn),
        )
        if (receiver != null && recv == KType.Str && isLiteralStrConstant(receiver)) {
            // R5: a literal Str constant is a `const char*` in C++; as a Str method's receiver it
            // is wrapped when the binding is member-style.
            model.coercions[receiver] = Coercion.StrConstReceiver
        }
        if (fn.isMutMethod) {
            mutReceiver(receiver, recv, fn, nameNode, implicitThis, ctx)
        }
        elementBound(e, recv, fn)
        model.calls[e] = ResolvedCall(
            kindFor(recv, fn, hit), fn, receiver, implicitThis, fn.typeParams.map { own[it] ?: KType.Error },
            bound.args, bound.order, ret, hit.substitution + own,
        )
        return ret
    }

    /** The List methods whose element type a generic bound cannot say (D58): a Str to join. */
    private fun elementBound(e: FunctionCallExpr, recv: KType, fn: FnSymbol) {
        val owner = fn.owner as? ClassSymbol ?: return
        if (owner.kind != ClassKind.MAGIC || owner.name != "List" || !facts.isList(recv)) {
            return
        }
        val element = facts.elementOf(recv) ?: return
        val (ok, what) = when (fn.name) {
            "joinToString" -> (element == KType.Str) to "Str"
            else -> return
        }
        if (!ok && !element.containsError()) {
            c.report("types.call.bound", "'${fn.name}' needs a List of $what, and ${recv.display()} is not one.", e)
        }
    }

    /**
     * A `mut fx` writes its receiver (D44), so inside a lambda the receiver must not be a
     * capture: a capture is an immutable copy (spec), the same rule as assigning one or
     * passing it as `mut`. A receiver reached through a reference (a class, a trait value, a
     * `Ref<T>`) is shared, not copied, so writing through it is allowed; so is a struct's own
     * receiver outside a lambda, whose mutability the rule passes check (W2.5).
     */
    private fun mutReceiver(receiver: Expr?, recv: KType, fn: FnSymbol, nameNode: Identifier, implicitThis: Boolean, ctx: BodyContext) {
        if (ctx.lambda == null || facts.isReference(recv)) {
            return
        }
        val place = if (implicitThis) ctx.owner?.let { Place.This(it) } else receiver?.let { model.places[it] }
        if (place == null || !c.writesCapture(place, ctx)) {
            return
        }
        val what = if (implicitThis) "the receiver of ${ctx.owner!!.name}.${fn.name}" else "'${KiraUnparser.text(receiver!!)}'"
        c.report(
            "types.lambda.assign-capture",
            "$what is captured by this lambda, and a capture is an immutable copy (spec): '${fn.name}' is a `mut fx`, " +
                "which writes its receiver. Share mutable state through a Ref<T> instead.",
            receiver ?: nameNode,
        )
    }

    /** A reference to a module constant of type Str whose value folded to a literal (design 5.2: a `const char*`). */
    private fun isLiteralStrConstant(e: Expr): Boolean {
        val sym = when (e) {
            is Identifier -> model.refs[e]
            is MemberAccessExpr -> (model.members[e] as? MemberRef.ModuleMember)?.symbol
            else -> null
        } as? GlobalSymbol ?: return false
        return sym.isConstant && sym.constValue is ConstValue.StrConst
    }

    // ---- free functions ----------------------------------------------------------------------

    private fun free(e: FunctionCallExpr, fn: FnSymbol, nameNode: Identifier?, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        nameNode?.let { model.refs[it] = fn }
        if (fn.module.isStdlib && fn.foreign is Foreign.Magic) {
            when {
                fn.name == "bitCast" && fn.module.uri == "kira:core" -> return bitCast(e, fn, ctx, scope)
                fn.name == "enumOf" && fn.module.uri == "kira:core" -> return enumOf(e, fn, ctx, scope)
                fn.name in printNames && fn.module.uri == "kira:io" -> return print(e, fn, ctx, scope)
            }
        }
        // R-G: a bodiless `pub` prototype is supplied by C++ as an `@_extern` is, and is called as one.
        val kind = when {
            fn.suppliedByCpp -> CallKind.EXTERN
            fn.foreign is Foreign.Magic -> CallKind.MAGIC
            else -> CallKind.FREE
        }
        val bound = bind(e, fn.name, fn.params) ?: run {
            ownTypeArgs(e, fn, fn.name, allowInfer = false)
            argsOnly(e, ctx, scope)
            return KType.Error
        }
        val pre = IdentityHashMap<Expr, KType>()
        val sub: Map<TypeParamSymbol, KType> = ownTypeArgs(e, fn, fn.name, allowInfer = true) ?: infer(e, fn, bound, hint, pre, ctx, scope)
        val paramTypes = fn.params.map { it.type.substitute(sub) }
        val ret = fn.ret.substitute(sub)
        typeGiven(e, bound, paramTypes, fn.params.map { it.byRef }, fn.params.map { it.name }, pre, ctx, scope, externCallee = isExternLike(fn))
        model.calls[e] = ResolvedCall(kind, fn, null, false, fn.typeParams.map { sub[it] ?: KType.Error }, bound.args, bound.order, ret, sub)
        return ret
    }

    /**
     * The explicit type arguments of a call to [fn] by its own type parameters. Null when [fn]
     * is a `@_magic @_infer` function called without them and [allowInfer]: the caller infers.
     */
    private fun ownTypeArgs(e: FunctionCallExpr, fn: FnSymbol, calleeName: String, allowInfer: Boolean): Map<TypeParamSymbol, KType>? {
        val tps = fn.typeParams
        val explicit = e.typeArguments.map { c.typeOf(it) }
        if (tps.isEmpty()) {
            if (explicit.isNotEmpty()) {
                c.report("types.call.type-args", "'$calleeName' takes no type arguments.", e)
            }
            return emptyMap()
        }
        if (explicit.isNotEmpty()) {
            if (explicit.size != tps.size) {
                c.report(
                    "types.call.type-args",
                    "'$calleeName' takes ${tps.size} type argument${if (tps.size == 1) "" else "s"} (${tps.joinToString { it.name }}), not ${explicit.size}.",
                    e,
                )
            }
            return tps.mapIndexed { i, tp -> tp to (explicit.getOrNull(i) ?: KType.Error) }.toMap()
        }
        if (allowInfer && isInfer(fn)) {
            return null
        }
        c.report(
            "types.call.type-args",
            "'$calleeName' is generic: call it with its type arguments, ${fn.name}<${tps.joinToString { it.name }}>(...). " +
                "Only a stdlib @_infer function infers them (D20).",
            e,
        )
        return tps.associateWith { KType.Error }
    }

    private fun isInfer(fn: FnSymbol): Boolean = fn.foreign is Foreign.Magic && fn.markers.any { it.name == INFER }

    /** D20: infers an @_infer function's type arguments, and checks them against their bounds. */
    private fun infer(
        e: FunctionCallExpr,
        fn: FnSymbol,
        bound: Bound,
        hint: KType?,
        pre: IdentityHashMap<Expr, KType>,
        ctx: BodyContext,
        scope: Scope,
    ): Map<TypeParamSymbol, KType> {
        val tps = fn.typeParams.toSet()
        val sol = LinkedHashMap<TypeParamSymbol, KType>()
        val given = bound.args.withIndex().mapNotNull { (i, a) -> (a as? ArgBinding.Given)?.let { i to it.expr } }
        for ((i, arg) in given) {
            if (!c.literals.isLiteralOnly(arg)) {
                val t = c.exprs.synth(arg, ctx, scope)
                pre[arg] = t
                unify(fn.params[i].type, t, tps, sol)
            }
        }
        if (hint != null && sol.keys.size < tps.size) {
            unify(fn.ret, facts.maybeInner(hint) ?: hint, tps, sol)
        }
        for ((i, arg) in given) {
            if (arg !in pre && mentionsUnbound(fn.params[i].type, tps, sol)) {
                val t = c.exprs.synth(arg, ctx, scope)
                pre[arg] = t
                unify(fn.params[i].type, t, tps, sol)
            }
        }
        for (tp in fn.typeParams) {
            val v = sol[tp]
            if (v == null) {
                c.report("types.call.infer", "The type argument ${tp.name} of '${fn.name}' cannot be inferred here; write ${fn.name}<...>(...).", e)
                sol[tp] = KType.Error
                continue
            }
            for (b in tp.bounds) {
                if (!facts.satisfies(v, b)) {
                    c.report(
                        "types.call.bound",
                        "'${fn.name}' needs ${tp.name}: ${b.display()}, and ${v.display()} is not a ${b.display()}.",
                        e,
                    )
                }
            }
        }
        return sol
    }

    private fun mentionsUnbound(t: KType, tps: Set<TypeParamSymbol>, sol: Map<TypeParamSymbol, KType>): Boolean = when (t) {
        is KType.Param -> t.sym in tps && t.sym !in sol
        is KType.Nominal -> t.typeArgs().any { mentionsUnbound(it, tps, sol) }
        is KType.Fn -> t.params.any { mentionsUnbound(it.type, tps, sol) } || mentionsUnbound(t.ret, tps, sol)
        else -> false
    }

    private fun unify(param: KType, arg: KType, tps: Set<TypeParamSymbol>, sol: MutableMap<TypeParamSymbol, KType>) {
        if (arg.containsError()) {
            return
        }
        when {
            param is KType.Param && param.sym in tps -> sol.putIfAbsent(param.sym, arg)
            param is KType.Nominal && arg is KType.Nominal && param.sym === arg.sym ->
                param.typeArgs().zip(arg.typeArgs()).forEach { (p, a) -> unify(p, a, tps, sol) }
            param is KType.Fn && arg is KType.Fn && param.params.size == arg.params.size -> {
                param.params.zip(arg.params).forEach { (p, a) -> unify(p.type, a.type, tps, sol) }
                unify(param.ret, arg.ret, tps, sol)
            }
        }
    }

    // ---- function values ---------------------------------------------------------------------

    private fun fnValue(e: FunctionCallExpr, fn: KType.Fn, receiver: Expr?, implicitThis: Boolean, ctx: BodyContext, scope: Scope): KType {
        if (e.namedParameters.isNotEmpty()) {
            argsOnly(e, ctx, scope)
            c.report("types.call.fn-value-named", "An Fx value's parameters have no names; pass its arguments by position.", e.namedParameters.first())
            return fn.ret
        }
        if (e.typeArguments.isNotEmpty()) {
            c.report("types.call.type-args", "An Fx value takes no type arguments.", e)
        }
        val n = e.positionalParameters.size
        if (n != fn.params.size) {
            argsOnly(e, ctx, scope)
            c.report("types.call.arity", "This Fx takes ${fn.params.size} argument${if (fn.params.size == 1) "" else "s"}, but $n ${if (n == 1) "was" else "were"} given.", e)
            return fn.ret
        }
        val bound = Bound(e.positionalParameters.mapIndexed { i, p -> ArgBinding.Given(p.value, fn.params[i].byRef) }, (0 until n).toList())
        typeGiven(e, bound, fn.params.map { it.type }, fn.params.map { it.byRef }, (1..n).map { "#$it" }, IdentityHashMap(), ctx, scope)
        model.calls[e] = ResolvedCall(CallKind.FN_VALUE, null, receiver, implicitThis, emptyList(), bound.args, bound.order, fn.ret)
        return fn.ret
    }

    // ---- binding -----------------------------------------------------------------------------

    /** The arguments of a call in parameter order, and the order they were written in. */
    data class Bound(val args: List<ArgBinding>, val order: List<Int>)

    /**
     * Binds [e]'s arguments to [params]: positional ones first, then named ones, then defaults
     * for what is left. Reports what cannot bind, and returns null then.
     */
    fun bind(e: FunctionCallExpr, calleeName: String, params: List<ParamSymbol>): Bound? {
        val positional = e.positionalParameters
        val named = e.namedParameters
        if (positional.size > params.size) {
            c.report(
                "types.call.arity",
                "'$calleeName' takes ${params.size} parameter(s), but ${positional.size} positional argument(s) were given.",
                e,
            )
            return null
        }
        val slots = arrayOfNulls<Pair<Expr, Boolean>>(params.size)
        val order = mutableListOf<Int>()
        positional.forEachIndexed { i, p ->
            slots[i] = p.value to p.isMut
            order.add(i)
        }
        // As NamedArguments.bind: the first problem is the one reported.
        for (n in named) {
            val index = params.indexOfFirst { it.name == n.name.value }
            if (index < 0) {
                c.report(
                    "types.call.unknown-named",
                    "'$calleeName' has no parameter named '${n.name.value}'. Its parameters are: ${params.joinToString(", ") { it.name }}.",
                    n,
                )
                return null
            }
            if (slots[index] != null) {
                c.report("types.call.duplicate-arg", "Parameter '${n.name.value}' of '$calleeName' is given more than once.", n)
                return null
            }
            slots[index] = n.value to n.isMut
            order.add(index)
        }
        val missing = params.filterIndexed { i, p -> slots[i] == null && p.default == null }
        if (missing.isNotEmpty()) {
            c.report(
                "types.call.missing-arg",
                "Call to '$calleeName' leaves parameter(s) without an argument: ${missing.joinToString(", ") { it.name }}.",
                e,
            )
            return null
        }
        val args = params.mapIndexed { i, p ->
            val given = slots[i]
            if (given != null) {
                ArgBinding.Given(given.first, p.byRef)
            } else {
                ArgBinding.Default(p, trailing = (i + 1 until params.size).all { slots[it] == null })
            }
        }
        if (params.none { it.default != null }) {
            // The same call, bound by the frontend's one rule for named arguments: it must agree.
            val canonical = NamedArguments.bind(e, calleeName, params.map { it.name })
            val mine = args.map { (it as ArgBinding.Given).expr }
            if (canonical is NamedArguments.Binding.Ordered && canonical.arguments.map { System.identityHashCode(it) } != mine.map { System.identityHashCode(it) }) {
                c.report("types.internal", "internal typer failure: the binding of '$calleeName' disagrees with NamedArguments.", e)
            }
        }
        return Bound(args, order)
    }

    /**
     * Types every given argument against its parameter. A `mut` parameter ([byRef]) takes a call-
     * site `mut` and a mutable place of exactly its type, never a converted value. [pre] holds
     * arguments already typed (by inference). With [strBufText] an interpolation or a string
     * literal argument is text appended to a StrBuf: typed as the `View<Char>` it is read as, no
     * Str is built (design section 10). With [externCallee] a `CStr` parameter takes a `Str`
     * argument as itself (design 7.2: a literal passes through, a named Str becomes `.c_str()`,
     * anything else a `kira::ffi::CStrBuf`; the C++ extern emitter spells which, from the
     * argument's recorded `Str` type, so no coercion is recorded here). Only an extern function
     * has a `CStr` parameter to fill; anywhere else a `Str` is no `CStr`.
     *
     * With [externCallee], an `Unsafe<T>` parameter (design 1.4, 5.5: the FFI pointer type,
     * extern-parameter-only) takes a `View<T>` argument as itself, and a `mut Unsafe<T>`
     * parameter (`T*` by value, not an out-parameter: table 5.1) a `MutView<T>`, needing neither
     * a call-site `mut` nor a place - it hands the callee the view's own pointer, not a
     * reference the callee writes back through. Either is recorded as-is, exactly like the
     * `CStr` case: the C++ extern emitter reads the argument's own `View`/`MutView` type and
     * lowers it `.data()`. An `Unsafe<T>` argument that already is one (the exact-type case a
     * `mut p: Unsafe<T>` local still takes, table 5.1's other way of calling into a C buffer)
     * is unaffected: [mutArgument]'s exact-type check still applies to it.
     */
    private fun typeGiven(
        e: FunctionCallExpr,
        bound: Bound,
        paramTypes: List<KType>,
        byRef: List<Boolean>,
        names: List<String>,
        pre: IdentityHashMap<Expr, KType>,
        ctx: BodyContext,
        scope: Scope,
        strBufText: Boolean = false,
        externCallee: Boolean = false,
    ) {
        val siteMut = IdentityHashMap<Expr, Boolean>()
        e.positionalParameters.forEach { siteMut[it.value] = it.isMut }
        e.namedParameters.forEach { siteMut[it.value] = it.isMut }
        bound.args.forEachIndexed { i, a ->
            val given = a as? ArgBinding.Given ?: return@forEachIndexed
            val arg = given.expr
            val expected = paramTypes.getOrElse(i) { KType.Error }
            val what = "argument '${names.getOrElse(i) { "#${i + 1}" }}'"
            val isMut = siteMut[arg] == true
            if (byRef.getOrElse(i) { false }) {
                val t = pre[arg] ?: c.exprs.synth(arg, ctx, scope)
                if (externCallee && facts.isMagic(expected, UNSAFE) && lendsCapture(arg, ctx)) {
                    return@forEachIndexed
                }
                if (externCallee && facts.isMagic(expected, UNSAFE) && t != expected &&
                    facts.isMutView(t) && facts.elementOf(t) == facts.elementOf(expected)
                ) {
                    // `mut p: Unsafe<T>` is `T*` by value, not an out-parameter (table 5.1): the
                    // caller hands over a MutView's own pointer, so no call-site `mut` and no
                    // place are needed, unlike every other `mut` parameter.
                    return@forEachIndexed
                }
                mutArgument(arg, t, expected, isMut, names.getOrElse(i) { "#${i + 1}" }, ctx)
                return@forEachIndexed
            }
            if (isMut) {
                c.report(
                    "types.call.mut-unexpected",
                    "$what is not a `mut` parameter, so it takes no call-site `mut`; drop it.",
                    arg,
                )
            }
            if (strBufText && facts.isView(expected) && arg is InterpolatedStringLiteral) {
                c.exprs.type(arg, expected, ctx, scope)
                model.types[arg] = expected
                return@forEachIndexed
            }
            val known = pre[arg]
            if (externCallee && facts.isMagic(expected, CSTR)) {
                val t = known ?: c.exprs.synth(arg, ctx, scope)
                if (t != KType.Str) {
                    c.coercions.assign(arg, t, expected, what)
                }
                return@forEachIndexed
            }
            if (externCallee && facts.isMagic(expected, UNSAFE)) {
                val t = known ?: c.exprs.synth(arg, ctx, scope)
                val element = facts.elementOf(expected)
                if (t != expected && element != null && (facts.isView(t) || facts.isMutView(t)) && facts.elementOf(t) == element) {
                    // Given as itself (table 5.1: a `View<T>`/`MutView<T>` converts to the `const
                    // T*`/`T*` the extern emitter lowers it to, `.data()`); anything else falls
                    // through to the ordinary mismatch below.
                    return@forEachIndexed
                }
                // Design 1.4: a non-mut `p: Unsafe<T>` also takes "anything the typer converts
                // to" a `View<T>` - an `Arr`/`List` place, or a `Str` literal when `T` is `Char`
                // (`Coercion.ToView`, `CoercionRules.fit`'s own `facts.isView(expected)` branch,
                // the same conversion an ordinary `View<T>` parameter accepts). Tried before the
                // plain mismatch below, so `sumP(ys, ...)` (`ys: List<Int32>`) and `lenBuf("abc")`
                // fit, and the `mut Unsafe<T>` case (still `MutView<T>`-only above, table 5.1) is
                // untouched. The coercion is recorded exactly as it would be for a `View<T>`
                // parameter, so the emitter's `.data()` wrap (`isUnsafe(p.type)`, which now also
                // reads this coercion) is all that is new at the boundary.
                if (element != null) {
                    when (val f = c.coercions.fit(arg, t, facts.viewOf(element))) {
                        is CoercionRules.Fit.Coerce -> {
                            model.coercions[arg] = f.coercion
                            return@forEachIndexed
                        }
                        CoercionRules.Fit.Same -> return@forEachIndexed
                        is CoercionRules.Fit.No -> {}
                    }
                }
                c.coercions.assign(arg, t, expected, what)
                return@forEachIndexed
            }
            if (known == null && facts.isMutView(expected)) {
                // ExprTyper.check, with the refusal of a MutView lent from a capture in place of
                // the View-for-MutView mismatch it would report ([lendsCapture]).
                val actual = c.exprs.type(arg, expected, ctx, scope)
                if (!lendsCapture(arg, ctx)) {
                    c.coercions.assign(arg, actual, expected, what)
                }
                return@forEachIndexed
            }
            if (known != null) {
                c.coercions.assign(arg, known, expected, what)
            } else {
                c.exprs.check(arg, expected, ctx, scope, what)
            }
        }
    }

    /**
     * F2's typer half (40-round3 R-F): an argument lent from a variable the enclosing lambda
     * captured, given where a `MutView` is taken (a `MutView<T>` parameter, or an extern's `mut
     * p: Unsafe<T>`, which takes a MutView's pointer), is `types.lambda.assign-capture`; reports
     * it and returns true. Forming a `MutView` of a place is a write access (it needs a `mut`
     * binding), and a capture is an immutable copy (spec), so the lambda's `xs.view()` is a
     * `View` ([method]) and handing it to a writer would write the capture: the same rule as
     * passing the capture as `mut`. Round 1's u11, `run(fx() Void { fillP(xs.view(), 3, 0) })`,
     * reached g++ ('no matching function for call to mutView'); round 2 refused it only by a
     * false rules.view.write. Walks a chain of lenders (`xs.view().from(1)`) back to the `Arr`
     * or `List` place they lend from; a view parameter's own `from` lends its pointer and writes
     * no capture.
     */
    private fun lendsCapture(arg: Expr, ctx: BodyContext): Boolean {
        if (ctx.lambda == null) {
            return false
        }
        var e: Expr = arg
        while (true) {
            val rc = (e as? FunctionCallExpr)?.let { model.calls[it] } ?: return false
            val recv = rc.receiver ?: return false
            val recvType = model.types[recv] ?: return false
            if (rc.fn?.name !in LENDERS) {
                return false
            }
            val place = model.places[recv]
            if (place != null) {
                if (!(facts.isArr(recvType) || facts.isList(recvType)) || !c.isMutablePlace(place, ctx) || !c.writesCapture(place, ctx)) {
                    return false
                }
                c.report(
                    "types.lambda.assign-capture",
                    "'${KiraUnparser.text(recv)}' is captured by this lambda, and a capture is an immutable copy: " +
                        "'${KiraUnparser.text(arg)}' would lend a MutView of it, which writes it. Share mutable state through a Ref<T> instead.",
                    arg,
                )
                return true
            }
            e = recv
        }
    }

    /** The magic methods that lend a view of their receiver (a `MutView` of a mutable `Arr`/`List` place). */
    private val LENDERS = setOf("from", "slice", "view")

    /** `CStr`, the FFI `const char*` (design 7.2, a magic class of the builtins). */
    private val CSTR = "CStr"

    /** `Unsafe<T>`, the FFI `const T*`/`T*` (design 1.4, table 5.1, a magic class of the builtins). */
    private val UNSAFE = "Unsafe"

    /**
     * Whether [fn]'s body C++ supplies: R-G's one predicate, [suppliedByCpp] (an `@_extern`
     * function or method, a bodiless `pub` free prototype a C++ file defines, a bodiless method
     * of an `@_opaque` class). Both conventions this file gives such a callee - `CStr` taking a
     * `Str` (design 7.2) and `Unsafe<T>` taking a `View<T>`/`MutView<T>` (design 1.4) - apply to
     * each the same way, since none has a body of its own to fill a parameter differently. A
     * bodiless method of a class is a slot a construction fills, not a prototype, so it is none
     * of these (round 2 counted any bodiless `pub` method).
     */
    private fun isExternLike(fn: FnSymbol): Boolean = fn.suppliedByCpp

    private fun mutArgument(arg: Expr, t: KType, expected: KType, isMut: Boolean, name: String, ctx: BodyContext) {
        if (!isMut) {
            c.report(
                "types.call.mut-missing",
                "Parameter '$name' is `mut` (passed by reference, D4): write `mut ${KiraUnparser.text(arg)}` at the call.",
                arg,
            )
        }
        val place = model.places[arg]
        if (place == null) {
            if (!t.containsError()) {
                c.report(
                    "types.call.mut-not-place",
                    "A `mut` argument is written by the callee, so it must be a variable, a field or an element; " +
                        "'${KiraUnparser.text(arg)}' is a value.",
                    arg,
                )
            }
        } else if (c.writesCapture(place, ctx)) {
            c.report(
                "types.lambda.assign-capture",
                "'${KiraUnparser.text(arg)}' is captured by this lambda, and a capture is an immutable copy: it cannot be passed as `mut`.",
                arg,
            )
        } else if (!c.isMutablePlace(place, ctx)) {
            c.report(
                "types.call.mut-immutable",
                "'${KiraUnparser.text(arg)}' cannot be written, so it cannot be passed as `mut`; declare it `mut`.",
                arg,
            )
        }
        if (!t.containsError() && !expected.containsError() && t != expected) {
            c.report(
                "types.call.mut-type",
                "A `mut` argument is passed by reference, so it must be exactly ${expected.display()}; this is ${t.display()}.",
                arg,
            )
        }
    }

    /** Types the arguments of a call that did not resolve, so each still has its facts. */
    fun argsOnly(e: FunctionCallExpr, ctx: BodyContext, scope: Scope) {
        e.positionalParameters.forEach { if (model.types[it.value] == null) c.exprs.synth(it.value, ctx, scope) }
        e.namedParameters.forEach { if (model.types[it.value] == null) c.exprs.synth(it.value, ctx, scope) }
    }

    // ---- special calls -----------------------------------------------------------------------

    /** `trace(x)`, `print(x)`, `println(x)`, `eprint(x)`: one value of a showable type. */
    private fun print(e: FunctionCallExpr, fn: FnSymbol?, ctx: BodyContext, scope: Scope): KType {
        val label = fn?.name ?: "trace"
        e.positionalParameters.forEach { p ->
            val t = c.exprs.synth(p.value, ctx, scope)
            if (!t.containsError() && !facts.isShowable(t)) {
                c.report(
                    "types.print.not-showable",
                    "$label prints a number, Char, Bool, Str or an enum; this is ${t.display()}.",
                    p.value,
                )
            }
            if (p.isMut) {
                c.report("types.call.mut-unexpected", "$label takes its value by copy; drop the `mut`.", p.value)
            }
        }
        e.namedParameters.forEach { c.exprs.synth(it.value, ctx, scope) }
        if (e.namedParameters.isNotEmpty() || e.positionalParameters.size != 1) {
            c.report("types.call.print-args", "$label takes exactly one value.", e)
        }
        val args = e.positionalParameters.map { ArgBinding.Given(it.value, false) }
        model.calls[e] = ResolvedCall(CallKind.PRINT, fn, null, false, emptyList(), args, args.indices.toList(), KType.Void)
        return KType.Void
    }

    /** `bitCast<T>(v)` (R20): `T` and `v` are fixed-width scalars of one size. */
    private fun bitCast(e: FunctionCallExpr, fn: FnSymbol, ctx: BodyContext, scope: Scope): KType {
        val explicit = e.typeArguments.map { c.typeOf(it) }
        val target = explicit.singleOrNull()
        val arg = e.positionalParameters.singleOrNull()?.value
        val argType = arg?.let { c.exprs.synth(it, ctx, scope) }
        if (arg == null || e.namedParameters.isNotEmpty()) {
            argsOnly(e, ctx, scope)
            c.report("types.bitcast.args", "bitCast<T>(value) takes one value.", e)
        }
        if (target == null) {
            c.report("types.bitcast.type-args", "bitCast needs its target type: bitCast<Float32>(bits).", e)
            return KType.Error
        }
        fun width(t: KType): Int? = t.prim?.takeIf { it != Prim.BOOL && it != Prim.SIZE }?.bits
        val tw = width(target)
        val aw = argType?.let { width(it) }
        if (!target.containsError() && tw == null) {
            c.report("types.bitcast.type", "bitCast reinterprets a fixed-width scalar; ${target.display()} is not one.", e)
            return KType.Error
        } else if (argType != null && !argType.containsError() && aw == null) {
            c.report("types.bitcast.type", "bitCast reinterprets a fixed-width scalar; ${argType.display()} is not one.", arg)
        } else if (tw != null && aw != null && tw != aw) {
            c.report(
                "types.bitcast.size",
                "bitCast needs equal sizes: ${target.display()} is $tw bits and ${argType.display()} is $aw.",
                e,
            )
        }
        if (arg != null) {
            model.calls[e] = ResolvedCall(
                CallKind.MAGIC, fn, null, false, listOf(target), listOf(ArgBinding.Given(arg, false)), listOf(0), target,
                fn.typeParams.firstOrNull()?.let { mapOf(it to target) } ?: emptyMap(),
            )
        }
        return target
    }

    /** `enumOf<E>(raw)` (D25): the entry of an integer enum E whose value is raw, as a `Maybe<E>`. */
    private fun enumOf(e: FunctionCallExpr, fn: FnSymbol, ctx: BodyContext, scope: Scope): KType {
        val target = e.typeArguments.map { c.typeOf(it) }.singleOrNull()
        val enum = (target as? KType.Nominal)?.sym as? EnumSymbol
        val arg = e.positionalParameters.singleOrNull()?.value
        if (arg == null || e.namedParameters.isNotEmpty()) {
            argsOnly(e, ctx, scope)
            c.report("types.enumof.args", "enumOf<E>(raw) takes one integer.", e)
        }
        if (enum == null || enum.base.prim?.isInteger != true) {
            if (arg != null) {
                c.exprs.synth(arg, ctx, scope)
            }
            if (target?.containsError() != true) {
                c.report("types.enumof.type", "enumOf<E> needs an enum with an integer base as E, like enumOf<Kind>(raw).", e)
            }
            return KType.Error
        }
        if (arg != null) {
            val t = if (c.literals.isLiteralOnly(arg)) c.exprs.check(arg, enum.base, ctx, scope, "enumOf's raw value") else c.exprs.synth(arg, ctx, scope)
            if (!t.containsError() && !facts.isInteger(t)) {
                c.report("types.enumof.raw", "enumOf's raw value is an integer; this is ${t.display()}.", arg)
            }
            model.calls[e] = ResolvedCall(
                CallKind.MAGIC, fn, null, false, listOf(target), listOf(ArgBinding.Given(arg, false)), listOf(0), facts.maybeOf(target),
                fn.typeParams.firstOrNull()?.let { mapOf(it to target) } ?: emptyMap(),
            )
        }
        return facts.maybeOf(target)
    }

    /** `Result.success(v)` / `Result.error(e)` (D39): the Result type comes from the context. */
    private fun result(e: FunctionCallExpr, cls: ClassSymbol, member: Identifier, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val which = member.value
        noTypeArgs(e, "Result.$which", " Its Result type comes from where it goes.")
        val target = hint?.let { facts.maybeInner(it) ?: it }?.takeIf { facts.isMagic(it, "Result") } as? KType.Nominal
        if (target == null) {
            argsOnly(e, ctx, scope)
            c.report(
                "types.result.context",
                "Result.$which(...) takes its type from where it goes, like r: Result<Int32, Str> = Result.$which(...).",
                e,
            )
            return KType.Error
        }
        val args = target.typeArgs()
        val fn = c.stmts.resultFn(cls, which)
        val bound = bind(e, "Result.$which", fn.params) ?: run {
            argsOnly(e, ctx, scope)
            return target
        }
        val sub = cls.typeParams.zip(args).toMap()
        typeGiven(e, bound, fn.params.map { it.type.substitute(sub) }, listOf(false), listOf("value"), IdentityHashMap(), ctx, scope)
        model.calls[e] = ResolvedCall(CallKind.MAGIC, fn, null, false, emptyList(), bound.args, bound.order, target, sub)
        return target
    }

    /**
     * `Str.of(bytes)` (D55): the text the UTF-8 in a `View<UInt8>` holds, each ill-formed part
     * replaced with U+FFFD, as Python's `bytes.decode("utf-8", "replace")`. Like Result.success
     * it has no Kira declaration: one magic FnSymbol on Str, keyed `Str.of` for the bindings.
     */
    private fun strOf(e: FunctionCallExpr, cls: ClassSymbol, ctx: BodyContext, scope: Scope): KType {
        noTypeArgs(e, "Str.of", "")
        val fn = c.stmts.strOfFn(cls, facts.viewOf(KType.UINT8))
        val bound = bind(e, "Str.of", fn.params) ?: run {
            argsOnly(e, ctx, scope)
            return KType.Str
        }
        typeGiven(e, bound, fn.params.map { it.type }, listOf(false), listOf("bytes"), IdentityHashMap(), ctx, scope)
        model.calls[e] = ResolvedCall(CallKind.MAGIC, fn, null, false, emptyList(), bound.args, bound.order, KType.Str, emptyMap())
        return KType.Str
    }

    /** A compiler-made callable (Result.success, Str.of) takes no type arguments, as a plain function does not. */
    private fun noTypeArgs(e: FunctionCallExpr, calleeName: String, why: String) {
        if (e.typeArguments.isNotEmpty()) {
            e.typeArguments.forEach { c.typeOf(it) }
            c.report("types.call.type-args", "'$calleeName' takes no type arguments.$why", e)
        }
    }

    /** `@_trace_(x)` and the other intrinsics met in expression position. */
    fun intrinsic(e: IntrinsicExpr, ctx: BodyContext, scope: Scope): KType {
        val name = e.intrinsicKey.name
        val params = e.parameters
        when (name) {
            "_trace_" -> {
                if (params == null || params.size != 1) {
                    params?.forEach { c.exprs.synth(it, ctx, scope) }
                    c.report("types.call.print-args", "@_trace_ takes exactly one value.", e)
                    return KType.Void
                }
                val t = c.exprs.synth(params.single(), ctx, scope)
                if (!t.containsError() && !facts.isShowable(t)) {
                    c.report("types.print.not-showable", "@_trace_ prints a number, Char, Bool, Str or an enum; this is ${t.display()}.", params.single())
                }
                return KType.Void
            }
            "_static_assert" -> {
                params?.forEach { c.exprs.synth(it, ctx, scope) }
                c.report("types.static-assert.position", "@_static_assert is a module-level declaration, not a statement.", e)
                return KType.Void
            }
        }
        params?.forEach { c.exprs.synth(it, ctx, scope) }
        c.report("types.intrinsic.value", "@$name is not a value.", e)
        return KType.Error
    }

    // ---- construction ------------------------------------------------------------------------

    /**
     * `T { a, b }` and `T { x = a }`: positional arguments fill the fields in declaration order
     * (a superclass's fields first), named ones name their field; `require` fields must be given
     * and the others keep their default (D38). A magic container constructs empty; `Ref<T> {
     * value = v }` and `TupleN { ... }` take their fields.
     */
    fun construct(e: ObjectInitExpr, ctx: BodyContext, scope: Scope): KType {
        val t = c.typeOf(e.typeName)
        fun bail(): KType {
            e.positionalArgs.forEach { c.exprs.synth(it, ctx, scope) }
            e.namedArgs.forEach { c.exprs.synth(it.value, ctx, scope) }
            return KType.Error
        }
        if (t.containsError()) {
            return bail()
        }
        val nominal = t as? KType.Nominal
        val cls = nominal?.sym as? ClassSymbol
        val refused = when {
            cls == null -> "${t.display()} is not a class or struct"
            cls.kind == ClassKind.MAGIC && (Builtins.prim(cls.name) != null || cls.name in Builtins.SPECIAL || cls.name in setOf("View", "MutView", "Fx", "Unsafe", "Any", "Num")) ->
                "${t.display()} is not constructed with { }" + if (cls.name == "View" || cls.name == "MutView") "; take a view of a container with .view(), .from() or .slice()" else ""
            cls.kind == ClassKind.OPAQUE || cls.foreign is Foreign.Extern -> "${t.display()} is made by the C/C++ side; call the function that returns one"
            else -> null
        }
        if (refused != null) {
            c.report("types.init.not-constructible", "$refused.", e)
            return bail()
        }
        cls!!
        val magic = cls.kind == ClassKind.MAGIC
        val chain = mutableListOf<ClassSymbol>()
        var cur: ClassSymbol? = cls
        val seen = HashSet<ClassSymbol>()
        while (cur != null && seen.add(cur)) {
            chain.add(0, cur)
            cur = cur.superclass?.sym as? ClassSymbol
        }
        val fields = chain.flatMap { it.fields }
        val fieldTypes = fields.map { f -> c.members.field(t, f.name)?.takeIf { it.first === f }?.second ?: f.type }
        val enforceRequire = !magic || cls.name !in emptyConstructible
        val slots = arrayOfNulls<FieldInit.Given>(fields.size)
        val order = mutableListOf<Int>()
        if (e.positionalArgs.size > fields.size) {
            c.report(
                "types.init.arity",
                "${t.display()} has ${fields.size} field${if (fields.size == 1) "" else "s"}, but ${e.positionalArgs.size} positional values were given.",
                e,
            )
        }
        e.positionalArgs.forEachIndexed { i, v ->
            if (i >= fields.size) {
                c.exprs.synth(v, ctx, scope)
                return@forEachIndexed
            }
            c.exprs.check(v, fieldTypes[i], ctx, scope, "field '${fields[i].name}'")
            slots[i] = FieldInit.Given(fields[i], v, named = false)
            order.add(i)
        }
        for (n in e.namedArgs) {
            val i = fields.indexOfFirst { it.name == n.name.value }
            if (i < 0) {
                c.exprs.synth(n.value, ctx, scope)
                c.report(
                    "types.init.unknown-field",
                    "${t.display()} has no field named '${n.name.value}'." +
                        if (fields.isEmpty()) "" else " Its fields are: ${fields.joinToString(", ") { it.name }}.",
                    n,
                )
                continue
            }
            model.refs[n.name] = fields[i]
            if (slots[i] != null) {
                c.exprs.synth(n.value, ctx, scope)
                c.report("types.init.duplicate", "Field '${n.name.value}' of ${t.display()} is given more than once.", n)
                continue
            }
            c.exprs.check(n.value, fieldTypes[i], ctx, scope, "field '${n.name.value}'")
            slots[i] = FieldInit.Given(fields[i], n.value, named = true)
            order.add(i)
        }
        val missing = fields.filterIndexed { i, f -> slots[i] == null && f.isRequired && f.default == null && enforceRequire }
        if (missing.isNotEmpty()) {
            c.report(
                "types.init.missing-required",
                "${t.display()} requires ${missing.joinToString(", ") { "'${it.name}'" }}: a `require` field must be given at construction.",
                e,
            )
        }
        val inits = fields.mapIndexed { i, f -> slots[i] ?: FieldInit.Default(f) }
        val sub = LinkedHashMap<TypeParamSymbol, KType>()
        val ancestry = ArrayDeque<KType.Nominal>().apply { add(nominal) }
        val visited = HashSet<KType>()
        while (ancestry.isNotEmpty()) {
            val n = ancestry.removeFirst()
            if (!visited.add(n)) continue
            (n.sym as? ClassSymbol)?.let { s -> sub.putAll(s.typeParams.zip(n.typeArgs())) }
            ancestry.addAll(facts.parents(n).filter { it.sym is ClassSymbol })
        }
        model.inits[e] = ResolvedInit(t, cls, inits, order, sub)
        return t
    }

    companion object {
        /** The marker that lets a `@_magic` stdlib function infer its type arguments (D20). */
        const val INFER = "_infer"
    }
}
