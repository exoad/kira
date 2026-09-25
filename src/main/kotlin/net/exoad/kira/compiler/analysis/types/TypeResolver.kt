package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import java.util.IdentityHashMap

/** The type parameters in scope at a Type node, innermost first, over a module. */
class TypeScope(
    val module: ModuleSymbol,
    val typeParams: List<TypeParamSymbol> = emptyList(),
    val parent: TypeScope? = null,
) {
    fun findTypeParam(name: String): TypeParamSymbol? =
        typeParams.firstOrNull { it.name == name } ?: parent?.findTypeParam(name)

    /** A nested scope that adds [params] (a method's inside its class's). */
    fun with(params: List<TypeParamSymbol>): TypeScope = if (params.isEmpty()) this else TypeScope(module, params, this)
}

/**
 * Phase B: turns a `Type` node into a [KType] and records it in `TypedModel.typeRefs`.
 *
 * Name lookup: the scope's type parameters, then the module's members, then the `pub`
 * members of `use`d modules, then the ambient stdlib (ModuleGraph.lookup); then the builtin
 * names (Builtins), so UInt8, Size, View and the rest resolve before the stdlib declares
 * them. A name found only as a non-`pub` member of a used module resolves, with a
 * `types.type.not-visible` error.
 *
 * In type-argument position an integer literal (a ConstTypeArg) or the name of an integer
 * constant (`Arr<UInt8, USER_CMD_BYTES>`) becomes [TypeArg.Const]. Such an argument node is
 * not a type, so it gets no typeRefs entry; a constant name gets a `consts` entry and a
 * `refs` entry for its identifier instead.
 *
 * Aliases are inlined, with their own type parameters substituted; a cycle through aliases
 * is a `types.alias.cycle` error.
 */
class TypeResolver(private val program: TypedProgram) {
    lateinit var constEval: ConstEval

    private val model = program.model
    private val aliasInProgress = IdentityHashMap<AliasSymbol, Boolean>()
    private val aliasCycleReported = IdentityHashMap<AliasSymbol, Boolean>()
    private val aliasOrder = mutableListOf<AliasSymbol>()

    fun resolve(t: Type, scope: TypeScope): KType {
        model.typeRefs[t]?.let { return it }
        val result = try {
            resolveUncached(t, scope)
        } catch (e: StackOverflowError) {
            program.report("types.type.too-deep", "This type nests too deeply to resolve.", t)
            KType.Error
        }
        model.typeRefs[t] = result
        return result
    }

    private fun resolveUncached(t: Type, scope: TypeScope): KType {
        if (t is ConstTypeArg) {
            program.report(
                "types.type.const-arg",
                "The constant ${t.value.value} is a type argument (like the size in Arr<UInt8, ${t.value.value}>), not a type.",
                t,
            )
            return KType.Error
        }
        val id = t.identifier as? Identifier
        if (id == null || id is IntrinsicExpr) {
            program.report("types.type.unknown", "'$t' does not name a type.", t)
            return KType.Error
        }
        val name = id.value
        scope.findTypeParam(name)?.let { tp ->
            model.refs[id] = tp
            if (t.children.isNotEmpty()) {
                program.report("types.type.arity", "Type parameter '$name' takes no type arguments.", t)
                return KType.Error
            }
            return KType.Param(tp)
        }
        var notVisible: ModuleGraph.Lookup.NotVisible? = null
        when (val found = program.graph.lookup(scope.module, name, ::isTypeLike)) {
            is ModuleGraph.Lookup.Found -> return symbolType(found.symbol, t, id, scope)
            is ModuleGraph.Lookup.Ambiguous -> {
                program.report(
                    "types.type.ambiguous",
                    "'$name' is exported by more than one used module: " +
                        (listOf(found.first) + found.others).joinToString(", ") { it.from.uri } + ".",
                    t,
                )
                return symbolType(found.first.symbol, t, id, scope)
            }
            is ModuleGraph.Lookup.NotVisible -> notVisible = found
            ModuleGraph.Lookup.Missing -> {}
        }
        if (Builtins.isBuiltinName(name)) {
            return builtinType(name, t, id, scope)
        }
        if (notVisible != null) {
            program.report(
                "types.type.not-visible",
                "'$name' is declared in ${notVisible.from.uri} but is not pub, so this module cannot use it.",
                t,
            )
            return symbolType(notVisible.symbol, t, id, scope)
        }
        val value = program.graph.lookup(scope.module, name) { it !is ClassSymbol && it !is TraitSymbol && it !is EnumSymbol && it !is AliasSymbol }
        if (value is ModuleGraph.Lookup.Found) {
            program.report("types.type.not-a-type", "'$name' is a ${kindOf(value.symbol)}, not a type.", t)
            return KType.Error
        }
        program.report("types.type.unknown", "Unknown type '$name'.", t)
        return KType.Error
    }

    private fun kindOf(s: Symbol): String = when (s) {
        is FnSymbol -> "function"
        is GlobalSymbol -> if (s.isConstant) "constant" else "variable"
        else -> s.javaClass.simpleName.removeSuffix("Symbol").lowercase()
    }

    private fun isTypeLike(s: Symbol): Boolean = s is ClassSymbol || s is TraitSymbol || s is EnumSymbol || s is AliasSymbol

    private fun symbolType(sym: Symbol, t: Type, id: Identifier, scope: TypeScope): KType {
        if (sym is ClassSymbol && sym.kind == ClassKind.MAGIC && Builtins.isBuiltinName(sym.name)) {
            return builtinType(sym.name, t, id, scope)
        }
        model.refs[id] = sym
        return when (sym) {
            is AliasSymbol -> expandAlias(sym, t, scope)
            is ClassSymbol -> nominal(sym, sym.typeParams.size, t, scope)
            is TraitSymbol -> nominal(sym, sym.typeParams.size, t, scope)
            is EnumSymbol -> nominal(sym, 0, t, scope)
            else -> {
                program.report("types.type.not-a-type", "'${sym.name}' is not a type.", t)
                KType.Error
            }
        }
    }

    private fun nominal(sym: TypeSymbol, arity: Int, t: Type, scope: TypeScope): KType {
        val args = t.children.map { resolveArg(it, scope) }
        if (args.size != arity) {
            program.report("types.type.arity", arityMessage(sym.name, arity..arity, args.size), t)
            return KType.Error
        }
        if (!typeArgsOnly(sym.name, args, t)) {
            return KType.Error
        }
        return KType.Nominal(sym, args)
    }

    private fun arityMessage(name: String, expected: IntRange, got: Int): String {
        val want = if (expected.first == expected.last) "${expected.first}" else "${expected.first} or ${expected.last}"
        return "'$name' takes $want type argument${if (expected.last == 1) "" else "s"}, not $got."
    }

    private fun typeArgsOnly(name: String, args: List<TypeArg>, t: Type): Boolean {
        val c = args.filterIsInstance<TypeArg.Const>().firstOrNull() ?: return true
        program.report("types.type.const-arg", "'$name' takes types as arguments, not the constant ${c.n}.", t)
        return false
    }

    /** A type argument: a type, or (a literal or a constant's name) a [TypeArg.Const]. */
    fun resolveArg(child: Type, scope: TypeScope): TypeArg {
        if (child is ConstTypeArg) {
            return TypeArg.Const(child.value.value)
        }
        val id = child.identifier as? Identifier
        if (child.children.isEmpty() && id != null && id !is IntrinsicExpr && scope.findTypeParam(id.value) == null) {
            val name = id.value
            val asType = program.graph.lookup(scope.module, name, ::isTypeLike)
            if (asType is ModuleGraph.Lookup.Missing && !Builtins.isBuiltinName(name)) {
                val asValue = program.graph.lookup(scope.module, name) { it is GlobalSymbol }
                val global = when (asValue) {
                    is ModuleGraph.Lookup.Found -> asValue.symbol as GlobalSymbol
                    is ModuleGraph.Lookup.NotVisible -> {
                        program.report(
                            "types.type.not-visible",
                            "'$name' is declared in ${asValue.from.uri} but is not pub, so this module cannot use it.",
                            child,
                        )
                        asValue.symbol as GlobalSymbol
                    }
                    is ModuleGraph.Lookup.Ambiguous -> asValue.first.symbol as GlobalSymbol
                    ModuleGraph.Lookup.Missing -> null
                }
                if (global != null) {
                    model.refs[id] = global
                    val value = constEval.valueOf(global)
                    if (value is ConstValue.IntConst) {
                        model.consts[child] = value
                        return TypeArg.Const(value.value.toLong())
                    }
                    program.report(
                        "types.type.const-arg",
                        "'$name' is used as a type argument, so it must be an integer constant" +
                            (if (global.isMut) "; it is declared mut." else " that folds at compile time."),
                        child,
                    )
                    return TypeArg.Ty(KType.Error)
                }
            }
        }
        return TypeArg.Ty(resolve(child, scope))
    }

    private fun builtinType(name: String, t: Type, id: Identifier, scope: TypeScope): KType {
        program.builtins.classFor(name)?.let { model.refs[id] = it }
        Builtins.prim(name)?.let { prim ->
            if (t.children.isNotEmpty()) {
                program.report("types.type.arity", "'$name' takes no type arguments.", t)
                return KType.Error
            }
            return KType.Scalar(prim)
        }
        Builtins.SPECIAL[name]?.let { special ->
            if (t.children.isNotEmpty()) {
                program.report("types.type.arity", "'$name' takes no type arguments.", t)
                return KType.Error
            }
            return special
        }
        val cls = program.builtins.classFor(name) ?: run {
            program.report("types.type.unknown", "Unknown type '$name'.", t)
            return KType.Error
        }
        return when (name) {
            Builtins.ARR -> arr(cls, t, scope)
            Builtins.FX -> fx(t, scope)
            Builtins.STRBUF -> strBuf(cls, t, scope)
            else -> {
                val tuple = Builtins.tupleArity(name)
                nominal(cls, tuple ?: cls.typeParams.size, t, scope)
            }
        }
    }

    /** `Arr<T>` or `Arr<T, N>`: resolved by arity (D6). */
    private fun arr(cls: ClassSymbol, t: Type, scope: TypeScope): KType {
        val args = t.children.map { resolveArg(it, scope) }
        if (args.size !in 1..2) {
            program.report("types.type.arity", arityMessage("Arr", 1..2, args.size), t)
            return KType.Error
        }
        if (args[0] is TypeArg.Const) {
            program.report("types.type.const-arg", "Arr's first argument is the element type, not a constant.", t)
            return KType.Error
        }
        if (args.size == 2) {
            val size = args[1]
            if (size !is TypeArg.Const) {
                program.report(
                    "types.type.const-arg",
                    "Arr's second argument is its size: an integer literal or constant, like Arr<UInt8, 32>.",
                    t,
                )
                return KType.Error
            }
            if (size.n < 0) {
                program.report("types.type.const-arg", "An Arr cannot have a negative size (${size.n}).", t)
                return KType.Error
            }
        }
        return KType.Nominal(cls, args)
    }

    /** `StrBuf<N>`: one constant, the capacity. */
    private fun strBuf(cls: ClassSymbol, t: Type, scope: TypeScope): KType {
        val args = t.children.map { resolveArg(it, scope) }
        val size = args.singleOrNull()
        if (size !is TypeArg.Const) {
            program.report(
                "types.type.const-arg",
                "StrBuf takes one argument, its capacity: an integer literal or constant, like StrBuf<64>.",
                t,
            )
            return KType.Error
        }
        if (size.n <= 0) {
            program.report("types.type.const-arg", "A StrBuf needs a positive capacity, not ${size.n}.", t)
            return KType.Error
        }
        return KType.Nominal(cls, args)
    }

    /** `Fx<TupleN<A..>, R>` becomes a [KType.Fn]; `mut` elements are by-reference parameters (D24). */
    private fun fx(t: Type, scope: TypeScope): KType {
        if (t.children.size != 2) {
            program.report("types.type.arity", arityMessage("Fx", 2..2, t.children.size), t)
            return KType.Error
        }
        val paramsNode = t.children[0]
        val paramsType = resolveArg(paramsNode, scope)
        val ret = resolveArg(t.children[1], scope)
        if (ret !is TypeArg.Ty) {
            program.report("types.type.const-arg", "Fx's second argument is the return type, not a constant.", t)
            return KType.Error
        }
        val tuple = (paramsType as? TypeArg.Ty)?.t
        if (tuple == KType.Error || ret.t == KType.Error) {
            return KType.Error
        }
        val nominal = tuple as? KType.Nominal
        val arity = nominal?.let { Builtins.tupleArity(it.sym.name) }?.takeIf { (nominal.sym as? ClassSymbol)?.kind == ClassKind.MAGIC }
        if (nominal == null || arity == null) {
            program.report(
                "types.fx.shape",
                "Fx's first argument lists the parameter types as a tuple, like Fx<Tuple2<Int32, Str>, Bool>; " +
                    "'${tuple?.display() ?: paramsNode}' is not a TupleN.",
                t,
            )
            return KType.Error
        }
        val flags = mutFlags(paramsNode, IdentityHashMap())
        val params = nominal.typeArgs().mapIndexed { i, pt ->
            FnParam(pt, flags.getOrNull(i) == true)
        }
        return KType.Fn(params, ret.t)
    }

    /**
     * The `mut` of each element of the tuple [node] spells, read from the spelling itself: a
     * Nominal carries no by-reference flag, so when the tuple is named through an alias
     * (`alias Args as Tuple1<mut Int32>`, then `Fx<Args, Void>`) the flags come from the alias's
     * target, through any chain of aliases. [seen] stops a cycle, which is reported elsewhere.
     */
    private fun mutFlags(node: Type, seen: IdentityHashMap<AliasSymbol, Boolean>): List<Boolean> {
        val alias = model.aliasRefs[node] ?: return node.children.map { it.isMutParam }
        if (seen.put(alias, true) != null) {
            return emptyList()
        }
        val target = alias.decl?.target ?: return emptyList()
        return mutFlags(target, seen)
    }

    private fun expandAlias(alias: AliasSymbol, t: Type, scope: TypeScope): KType {
        if (aliasInProgress.containsKey(alias)) {
            reportCycle(alias, t)
            return KType.Error
        }
        val target = aliasTarget(alias)
        val args = t.children.map { resolveArg(it, scope) }
        if (args.size != alias.typeParams.size) {
            program.report("types.type.arity", arityMessage(alias.name, alias.typeParams.size..alias.typeParams.size, args.size), t)
            return KType.Error
        }
        if (!typeArgsOnly(alias.name, args, t)) {
            return KType.Error
        }
        model.aliasRefs[t] = alias
        if (target == KType.Error) {
            return KType.Error
        }
        val substitution = alias.typeParams.zip(args.map { (it as TypeArg.Ty).t }).toMap()
        return target.substitute(substitution)
    }

    /** The alias's target over its own type parameters, resolved once (with the cycle check). */
    fun aliasTarget(alias: AliasSymbol): KType {
        val decl = alias.decl ?: return alias.target
        model.typeRefs[decl.target]?.let { return it.also { alias.target = it } }
        if (aliasInProgress.containsKey(alias)) {
            reportCycle(alias, decl.target)
            return KType.Error
        }
        aliasInProgress[alias] = true
        aliasOrder.add(alias)
        try {
            val target = resolve(decl.target, TypeScope(alias.module, alias.typeParams))
            val result = if (aliasCycleReported.containsKey(alias)) KType.Error else target
            model.typeRefs[decl.target] = result
            alias.target = result
            return result
        } finally {
            aliasInProgress.remove(alias)
            aliasOrder.removeAt(aliasOrder.size - 1)
        }
    }

    /** Reports the cycle once, at the alias where it closed; every alias on it becomes Error. */
    private fun reportCycle(alias: AliasSymbol, at: Type) {
        val chain = aliasOrder.dropWhile { it !== alias }.map { it.name } + alias.name
        aliasInProgress.keys.forEach { aliasCycleReported[it] = true }
        program.report(
            "types.alias.cycle",
            "Alias '${alias.name}' refers to itself (${chain.joinToString(" -> ")}); " +
                "an alias is inlined, so it cannot be recursive.",
            alias.decl ?: at,
        )
    }
}
