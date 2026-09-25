package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariantDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * Phase B: fills in every type the declarations name, in dependency order, then computes the
 * whole-program facts:
 *
 * 1. type-parameter bounds, and the declared types of class, trait and alias names;
 * 2. alias targets (TypeResolver checks for cycles);
 * 3. parents: a class's first parent may be a class, the rest must be traits; a struct's and
 *    a trait's parents are all traits; inheritance cycles are errors;
 * 4. enum base types and values ([EnumDecl.memberValues], range-checked against the base);
 * 5. global, field, parameter and return types, with the default-order rule;
 * 6. flattened trait methods;
 * 7. overrides: [FnSymbol.overrides], [FnSymbol.isVirtual], [ClassSymbol.isSubclassed];
 * 8. every Type node inside bodies, initializers and defaults (so phase C only reads typeRefs);
 * 9. constants (ConstEval).
 *
 * Each declaration is resolved under its own guard: an internal failure leaves that
 * declaration's types as [KType.Error] with a `types.internal` diagnostic, and the rest of
 * the program still resolves.
 */
internal class SignatureResolver(private val program: TypedProgram) {
    private val model = program.model
    private val resolver = TypeResolver(program)
    private val constEval = ConstEval(program, resolver).also { resolver.constEval = it }

    private val classes = mutableListOf<ClassSymbol>()
    private val traits = mutableListOf<TraitSymbol>()
    private val enums = mutableListOf<EnumSymbol>()
    private val aliases = mutableListOf<AliasSymbol>()
    private val globals = mutableListOf<GlobalSymbol>()
    private val functions = mutableListOf<FnSymbol>()

    fun resolveAll() {
        for (m in program.modules) {
            for (s in m.declarations) {
                when (s) {
                    is ClassSymbol -> classes.add(s)
                    is TraitSymbol -> traits.add(s)
                    is EnumSymbol -> enums.add(s)
                    is AliasSymbol -> aliases.add(s)
                    is GlobalSymbol -> globals.add(s)
                    is FnSymbol -> functions.add(s)
                    else -> {}
                }
            }
        }
        step("type parameters") { typeParameters() }
        aliases.forEach { a -> each(a) { aliasTarget(a) } }
        classes.forEach { c -> each(c) { classParents(c) } }
        traits.forEach { t -> each(t) { traitParents(t) } }
        step("inheritance cycles") { inheritanceCycles() }
        enums.forEach { e -> each(e) { enumValues(e) } }
        globals.forEach { g -> each(g) { constEval.globalType(g) } }
        classes.forEach { c -> each(c) { members(c) } }
        traits.forEach { t -> t.methods.forEach { m -> each(m) { signature(m, fnScope(m)) } } }
        functions.forEach { f -> each(f) { signature(f, fnScope(f)) } }
        traits.forEach { t -> each(t) { flatten(t, IdentityHashMap()) } }
        classes.forEach { c -> each(c) { overrides(c) } }
        traits.forEach { t -> each(t) { traitOverrides(t) } }
        step("body types") { bodyTypes() }
        globals.forEach { g -> each(g) { constEval.valueOf(g) } }
        step("defaults") { defaults() }
    }

    private fun step(what: String, block: () -> Unit) {
        KiraTyper.guard(program, what, null, block)
    }

    /** Resolves one declaration; on an internal failure its types stay Error. */
    private fun each(symbol: Symbol, block: () -> Unit) {
        val ok = KiraTyper.guard(program, "resolving ${symbol.qualifiedName}", symbol.decl, block)
        if (!ok) {
            poison(symbol)
        }
    }

    private fun poison(symbol: Symbol) {
        when (symbol) {
            is FnSymbol -> {
                symbol.ret = KType.Error
                symbol.params.forEach { it.type = KType.Error }
            }
            is GlobalSymbol -> symbol.type = KType.Error
            is AliasSymbol -> symbol.target = KType.Error
            is EnumSymbol -> symbol.base = KType.Error
            is ClassSymbol -> symbol.fields.forEach { it.type = KType.Error }
            else -> {}
        }
    }

    // ---- scopes ----------------------------------------------------------------------------

    private fun classScope(c: ClassSymbol) = TypeScope(c.module, c.typeParams)

    private fun traitScope(t: TraitSymbol) = TypeScope(t.module, t.typeParams)

    private fun ownerScope(owner: TypeSymbol?, module: ModuleSymbol): TypeScope = when (owner) {
        is ClassSymbol -> classScope(owner)
        is TraitSymbol -> traitScope(owner)
        else -> TypeScope(module)
    }

    private fun fnScope(f: FnSymbol): TypeScope = ownerScope(f.owner, f.module).with(f.typeParams)

    // ---- 1. type parameters and declared names ---------------------------------------------

    private fun typeParameters() {
        fun bounds(params: List<TypeParamSymbol>, scope: TypeScope) {
            for (tp in params) {
                val node = tp.decl as? Type ?: continue
                model.typeRefs[node] = KType.Param(tp)
                val bound = node.constraint ?: continue
                each(tp) { tp.bounds.add(resolver.resolve(bound, scope)) }
            }
        }
        classes.forEach { c ->
            bounds(c.typeParams, classScope(c))
            nameType(c)?.let { model.typeRefs[it] = declaredType(c) }
            c.methods.forEach { bounds(it.typeParams, fnScope(it)) }
        }
        traits.forEach { t ->
            bounds(t.typeParams, traitScope(t))
            t.decl?.name?.let { model.typeRefs[it] = KType.Nominal(t, t.typeParams.map { p -> TypeArg.Ty(KType.Param(p)) }) }
            t.methods.forEach { bounds(it.typeParams, fnScope(it)) }
        }
        aliases.forEach { a -> bounds(a.typeParams, TypeScope(a.module, a.typeParams)) }
        functions.forEach { f -> bounds(f.typeParams, fnScope(f)) }
    }

    private fun nameType(c: ClassSymbol): Type? = when (val d = c.decl) {
        is ClassDecl -> d.name
        is StructDecl -> d.name
        is VariantDecl -> d.name
        else -> null
    }

    /** A class's own type: a builtin's KType for a magic builtin (`Int32`, `Str`), else Nominal over its parameters. */
    private fun declaredType(c: ClassSymbol): KType {
        if (c.kind == ClassKind.MAGIC) {
            Builtins.prim(c.name)?.let { return KType.Scalar(it) }
            Builtins.SPECIAL[c.name]?.let { return it }
        }
        return c.selfType
    }

    // ---- 2. aliases ------------------------------------------------------------------------

    private fun aliasTarget(a: AliasSymbol) {
        val target = resolver.aliasTarget(a)
        a.decl?.alias?.let { model.typeRefs[it] = target }
    }

    // ---- 3. parents ------------------------------------------------------------------------

    private fun parentTypes(c: ClassSymbol): List<Type> = when (val d = c.decl) {
        is ClassDecl -> d.parents
        is StructDecl -> d.traits
        is VariantDecl -> d.parents
        else -> emptyList()
    }

    private fun classParents(c: ClassSymbol) {
        val scope = classScope(c)
        parentTypes(c).forEachIndexed { i, node ->
            val t = resolver.resolve(node, scope)
            if (t == KType.Error) {
                return@forEachIndexed
            }
            val sym = (t as? KType.Nominal)?.sym
            when {
                sym is TraitSymbol -> c.traits.add(t)
                sym is ClassSymbol && c.isStruct -> program.report(
                    "types.struct.inherits-class",
                    "struct ${c.name} cannot inherit from ${sym.name}: a struct is a value type and only implements traits (D1).",
                    node,
                )
                sym is ClassSymbol && sym.isStruct -> program.report(
                    "types.class.inherits-struct",
                    "${c.name} cannot inherit from struct ${sym.name}: a struct is a value type and cannot be a parent (D1).",
                    node,
                )
                sym is ClassSymbol && c.superclass != null -> program.report(
                    "types.class.multiple-superclasses",
                    "class ${c.name} already inherits from ${c.superclass!!.sym.name}; it cannot also inherit from ${sym.name} " +
                        "(single inheritance: the other parents must be traits).",
                    node,
                )
                sym is ClassSymbol && i > 0 -> program.report(
                    "types.class.superclass-not-first",
                    "The superclass ${sym.name} must come first in ${c.name}'s parent list; the parents after it are traits.",
                    node,
                )
                sym is ClassSymbol -> c.superclass = t
                else -> program.report(
                    "types.class.bad-parent",
                    "${c.name} can only inherit from a class or implement a trait; ${t.display()} is neither.",
                    node,
                )
            }
        }
    }

    private fun traitParents(t: TraitSymbol) {
        val decl = t.decl ?: return
        val scope = traitScope(t)
        for (node in decl.parents) {
            val p = resolver.resolve(node, scope)
            if (p == KType.Error) {
                continue
            }
            if ((p as? KType.Nominal)?.sym is TraitSymbol) {
                t.parents.add(p)
            } else {
                program.report(
                    "types.trait.bad-parent",
                    "trait ${t.name} can only extend traits; ${p.display()} is not a trait.",
                    node,
                )
            }
        }
    }

    private fun inheritanceCycles() {
        for (c in classes) {
            val seen = IdentityHashMap<ClassSymbol, Boolean>()
            var cur: ClassSymbol? = c
            while (cur != null) {
                if (seen.put(cur, true) != null) {
                    if (cur === c) {
                        program.report(
                            "types.class.inheritance-cycle",
                            "class ${c.name} inherits from itself through its superclasses.",
                            nameType(c) ?: c.decl,
                        )
                        c.superclass = null
                    }
                    break
                }
                cur = cur.superclass?.sym as? ClassSymbol
            }
        }
        for (t in traits) {
            if (reachesTrait(t, t, IdentityHashMap())) {
                program.report(
                    "types.trait.inheritance-cycle",
                    "trait ${t.name} extends itself through its parents.",
                    t.decl?.name ?: t.decl,
                )
                t.parents.clear()
            }
        }
        for (c in classes) {
            (c.superclass?.sym as? ClassSymbol)?.isSubclassed = true
        }
    }

    private fun reachesTrait(from: TraitSymbol, target: TraitSymbol, seen: IdentityHashMap<TraitSymbol, Boolean>): Boolean {
        for (p in from.parents) {
            val pt = p.sym as? TraitSymbol ?: continue
            if (pt === target) {
                return true
            }
            if (seen.put(pt, true) == null && reachesTrait(pt, target, seen)) {
                return true
            }
        }
        return false
    }

    // ---- 4. enums --------------------------------------------------------------------------

    private fun enumValues(e: EnumSymbol) {
        val decl = e.decl ?: return
        val base = decl.baseType?.let { resolver.resolve(it, TypeScope(e.module)) }
            ?: when (EnumDecl.inferBaseTypeName(decl.members.toList())) {
                "Str" -> KType.Str
                "Float64" -> KType.FLOAT64
                else -> KType.INT32
            }
        val prim = base.prim
        val valid = base == KType.Str || (prim != null && (prim.isInteger || prim.isFloat))
        if (!valid) {
            if (base != KType.Error) {
                program.report(
                    "types.enum.base",
                    "enum ${e.name}'s base type must be an integer, a float or Str, not ${base.display()}.",
                    decl.baseType ?: decl,
                )
            }
            e.base = KType.Error
            return
        }
        e.base = base
        val values = decl.memberValues()
        e.entries.forEachIndexed { i, entry ->
            val raw = values.getOrNull(i) ?: return@forEachIndexed
            val member = decl.members[i]
            val value = enumValue(raw, base)
            if (value == null) {
                program.report(
                    "types.enum.value",
                    if (prim != null && prim.isInteger && raw is Long) {
                        "${e.name}.${entry.name} = ${render(raw)} does not fit the base type ${base.display()}, " +
                            "which holds ${prim.minValue}..${prim.maxValue}."
                    } else {
                        "${e.name}.${entry.name} = ${render(raw)} is not a value of the base type ${base.display()}."
                    },
                    member.value ?: member,
                )
                return@forEachIndexed
            }
            entry.value = value
            member.value?.let { model.consts[it] = value }
        }
    }

    private fun render(raw: Any): String = if (raw is String) "\"$raw\"" else raw.toString()

    private fun enumValue(raw: Any, base: KType): ConstValue? {
        if (base == KType.Str) {
            return (raw as? String)?.let { ConstValue.StrConst(it) }
        }
        val prim = base.prim ?: return null
        return when {
            prim.isInteger && raw is Long -> BigInteger.valueOf(raw).takeIf { prim.fits(it) }?.let { ConstValue.IntConst.of(it, prim) }
            prim.isFloat && raw is Long -> ConstValue.FloatConst(roundTo(raw.toDouble(), prim), prim)
            prim.isFloat && raw is Double -> ConstValue.FloatConst(roundTo(raw, prim), prim)
            else -> null
        }
    }

    private fun roundTo(v: Double, prim: Prim): Double = if (prim == Prim.FLOAT32) v.toFloat().toDouble() else v

    // ---- 5. members and signatures ---------------------------------------------------------

    private fun members(c: ClassSymbol) {
        val scope = classScope(c)
        val inherited = inheritedFieldNames(c)
        for (f in c.fields) {
            val decl = f.decl as? VariableDecl ?: continue
            f.type = resolver.resolve(decl.type, scope)
            inherited[f.name]?.let { owner ->
                program.report(
                    "types.decl.duplicate",
                    "'${f.name}' is already a field of ${c.name}'s superclass $owner.",
                    decl,
                )
            }
        }
        for (m in c.methods) {
            each(m) { signature(m, fnScope(m)) }
        }
    }

    private fun inheritedFieldNames(c: ClassSymbol): Map<String, String> {
        val out = HashMap<String, String>()
        val seen = IdentityHashMap<ClassSymbol, Boolean>()
        var cur = c.superclass?.sym as? ClassSymbol
        while (cur != null && seen.put(cur, true) == null) {
            cur.fields.forEach { out.putIfAbsent(it.name, cur.name) }
            cur = cur.superclass?.sym as? ClassSymbol
        }
        return out
    }

    private fun signature(f: FnSymbol, scope: TypeScope) {
        val decl = f.decl ?: return
        var sawDefault: ParamSymbol? = null
        for (p in f.params) {
            val pd = p.decl as FunctionDeclParameterExpr
            p.type = resolver.resolve(pd.typeSpecifier, scope)
            if (p.default != null) {
                if (p.byRef) {
                    program.report(
                        "types.param.mut-default",
                        "'mut ${p.name}' is passed by reference, so it cannot have a default value.",
                        p.default,
                    )
                }
                sawDefault = p
            } else if (sawDefault != null) {
                program.report(
                    "types.param.default-order",
                    "Parameter '${p.name}' has no default but comes after '${sawDefault.name}', which has one: " +
                        "defaulted parameters go last.",
                    pd,
                )
            }
        }
        f.ret = resolver.resolve(decl.def.returnTypeSpecifier, scope)
    }

    // ---- 6. traits -------------------------------------------------------------------------

    private fun flatten(t: TraitSymbol, visiting: IdentityHashMap<TraitSymbol, Boolean>): List<FnSymbol> {
        if (t.flatMethods.isNotEmpty() || visiting.containsKey(t)) {
            return t.flatMethods
        }
        visiting[t] = true
        val out = mutableListOf<FnSymbol>()
        for (p in t.parents) {
            val parent = p.sym as? TraitSymbol ?: continue
            for (m in flatten(parent, visiting)) {
                if (out.none { it === m || it.name == m.name }) {
                    out.add(m)
                }
            }
        }
        for (m in t.methods) {
            if (m.isOperator || m.name == DeclarationCollector.ANONYMOUS) {
                out.add(m)
                continue
            }
            val at = out.indexOfFirst { it.name == m.name }
            if (at >= 0) out[at] = m else out.add(m)
        }
        t.flatMethods.clear()
        t.flatMethods.addAll(out)
        visiting.remove(t)
        return out
    }

    private fun traitOverrides(t: TraitSymbol) {
        for (m in t.methods) {
            m.isVirtual = true
            val inherited = traitClosure(t.parents, emptyMap()).firstNotNullOfOrNull { (pt, sub) ->
                pt.methods.firstOrNull { it.name == m.name }?.let { it to sub }
            }
            if (inherited != null) {
                m.overrides = inherited.first
                checkOverride(m, inherited.first, inherited.second, t.name)
            }
        }
    }

    // ---- 7. overrides ----------------------------------------------------------------------

    /** Every trait [roots] reach, each with the substitution of its type parameters. */
    private fun traitClosure(
        roots: List<KType.Nominal>,
        sub: Map<TypeParamSymbol, KType>,
    ): List<Pair<TraitSymbol, Map<TypeParamSymbol, KType>>> {
        val out = mutableListOf<Pair<TraitSymbol, Map<TypeParamSymbol, KType>>>()
        val seen = IdentityHashMap<TraitSymbol, Boolean>()
        fun visit(n: KType.Nominal, outer: Map<TypeParamSymbol, KType>) {
            val t = n.sym as? TraitSymbol ?: return
            if (seen.put(t, true) != null) {
                return
            }
            val s = t.typeParams.zip(n.typeArgs().map { it.substitute(outer) }).toMap()
            out.add(t to s)
            t.parents.forEach { visit(it, s) }
        }
        roots.forEach { visit(it, sub) }
        return out
    }

    private fun superSubstitution(n: KType.Nominal): Map<TypeParamSymbol, KType> {
        val c = n.sym as? ClassSymbol ?: return emptyMap()
        return c.typeParams.zip(n.typeArgs()).toMap()
    }

    private fun overrides(c: ClassSymbol) {
        if (c.kind == ClassKind.MAGIC) {
            return
        }
        // The superclass chain, nearest first, each with its substitution into this class.
        val chain = mutableListOf<Pair<ClassSymbol, Map<TypeParamSymbol, KType>>>()
        val seen = IdentityHashMap<ClassSymbol, Boolean>()
        var n = c.superclass
        var outer: Map<TypeParamSymbol, KType> = emptyMap()
        while (n != null) {
            val sc = n.sym as? ClassSymbol ?: break
            if (seen.put(sc, true) != null) {
                break
            }
            val s = superSubstitution(KType.Nominal(sc, n.args.map { a -> if (a is TypeArg.Ty) TypeArg.Ty(a.t.substitute(outer)) else a }))
            chain.add(sc to s)
            outer = s
            n = sc.superclass
        }
        val implemented = traitClosure(c.traits, emptyMap()) + chain.flatMap { (sc, s) -> traitClosure(sc.traits, s) }
        val quiet = c.module.isStdlib
        for (m in c.methods) {
            if (m.isOperator || m.name == DeclarationCollector.ANONYMOUS) {
                continue
            }
            val base = chain.firstNotNullOfOrNull { (sc, s) -> sc.methods.firstOrNull { it.name == m.name }?.let { it to s } }
            // Each trait's own methods, nearest trait first: the closure lists every ancestor
            // with the substitution that reaches it, so an inherited method is found at the
            // trait that declares it, under that trait's own type arguments.
            val viaTrait = if (base == null) {
                implemented.firstNotNullOfOrNull { (t, s) -> t.methods.firstOrNull { it.name == m.name }?.let { it to s } }
            } else {
                null
            }
            val target = base ?: viaTrait
            if (target != null) {
                m.overrides = target.first
                if (!c.isStruct) {
                    m.isVirtual = true
                    if (base != null) {
                        base.first.isVirtual = true
                    }
                }
                checkOverride(m, target.first, target.second, c.name)
            }
            if (quiet) {
                continue
            }
            if (m.isOverride && target == null) {
                program.report(
                    "types.override.nothing",
                    "${c.name}.${m.name} is marked override, but no superclass or trait of ${c.name} declares '${m.name}'.",
                    m.decl,
                )
            } else if (!m.isOverride && target != null) {
                program.report(
                    "types.override.missing",
                    "${c.name}.${m.name} replaces ${target.first.qualifiedName}; mark it `override` (spec: Inheritance).",
                    m.decl,
                    Severity.WARNING,
                )
            }
        }
    }

    private fun checkOverride(m: FnSymbol, base: FnSymbol, sub: Map<TypeParamSymbol, KType>, where: String) {
        if (m.module.isStdlib) {
            return
        }
        val expectedParams = base.params.map { it.type.substitute(sub) }
        val expectedRet = base.ret.substitute(sub)
        val paramsMatch = m.params.size == base.params.size &&
            m.params.indices.all { i -> m.params[i].type == expectedParams[i] && m.params[i].byRef == base.params[i].byRef }
        val errors = (expectedParams + expectedRet + m.params.map { it.type } + m.ret).any { it.containsError() }
        if (errors) {
            return
        }
        if (!paramsMatch || m.ret != expectedRet) {
            val want = "(${base.params.indices.joinToString(", ") { (if (base.params[it].byRef) "mut " else "") + expectedParams[it].display() }}) ${expectedRet.display()}"
            val got = "(${m.params.joinToString(", ") { (if (it.byRef) "mut " else "") + it.type.display() }}) ${m.ret.display()}"
            program.report(
                "types.override.signature",
                "$where.${m.name} must have the signature of ${base.qualifiedName}, $want; it is $got.",
                m.decl,
            )
        } else if (m.isMutMethod != base.isMutMethod) {
            // `mut fx` decides C++ `const`: a mismatch would declare a second function, not an override.
            program.report(
                "types.override.signature",
                "$where.${m.name} must be ${if (base.isMutMethod) "a `mut fx`" else "a plain `fx` (not `mut`)"}, " +
                    "like ${base.qualifiedName}.",
                m.decl,
            )
        }
    }

    // ---- 8. types inside bodies ------------------------------------------------------------

    private fun bodyTypes() {
        for (m in program.modules) {
            val moduleScope = TypeScope(m)
            for (s in m.declarations) {
                each(s) {
                    when (s) {
                        is FnSymbol -> fnBodyTypes(s)
                        is GlobalSymbol -> s.init?.let { walkTypes(it, moduleScope) }
                        is ClassSymbol -> {
                            val scope = classScope(s)
                            s.fields.forEach { f -> f.default?.let { walkTypes(it, scope) } }
                            s.initially?.forEach { walkTypes(it, scope) }
                            s.finally?.forEach { walkTypes(it, scope) }
                            s.methods.forEach { fnBodyTypes(it) }
                        }
                        is TraitSymbol -> s.methods.forEach { fnBodyTypes(it) }
                        else -> {}
                    }
                }
            }
            m.statements.forEach { walkTypes(it, moduleScope) }
        }
    }

    private fun fnBodyTypes(f: FnSymbol) {
        val scope = fnScope(f)
        f.params.forEach { p -> p.default?.let { walkTypes(it, scope) } }
        f.body?.forEach { walkTypes(it, scope) }
    }

    /** Resolves every Type node under [node]; a Type resolves its own arguments. */
    private fun walkTypes(node: ASTNode, scope: TypeScope) {
        when (node) {
            is Type -> resolver.resolve(node, scope)
            is FunctionDecl -> {
                // A function declared inside a body: its own type parameters are in scope for it.
                val params = node.generics.mapIndexedNotNull { i, g ->
                    val id = (g.identifier as? Identifier)?.takeIf { it !is IntrinsicExpr } ?: return@mapIndexedNotNull null
                    TypeParamSymbol(id.value, scope.module, g, i).also { tp ->
                        model.declSyms[g] = tp
                        model.typeRefs[g] = KType.Param(tp)
                        model.refs[id] = tp
                    }
                }
                val inner = scope.with(params)
                AstTree.children(node).forEach { if (!node.generics.any { g -> g === it }) walkTypes(it, inner) }
            }
            else -> AstTree.children(node).forEach { walkTypes(it, scope) }
        }
    }

    // ---- 9. defaults -----------------------------------------------------------------------

    private fun defaults() {
        for (c in classes) {
            for (f in c.fields) {
                val d = f.default ?: continue
                if (f.type != KType.Error) {
                    constEval.fold(d, f.type, f.module)
                }
            }
        }
        val fns = functions + classes.flatMap { it.methods } + traits.flatMap { it.methods }
        for (f in fns) {
            for (p in f.params) {
                val d = p.default ?: continue
                if (p.type != KType.Error) {
                    constEval.fold(d, p.type, f.module)
                }
            }
        }
    }
}
