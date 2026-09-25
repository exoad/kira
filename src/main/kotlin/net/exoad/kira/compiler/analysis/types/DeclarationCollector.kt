package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.EnumDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FirstClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariantDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.source.SourceContext

/**
 * Phase A: one [ModuleSymbol] per module URI, and a symbol for every declaration in it and
 * every member of its classes, structs, traits and enums, with types left unresolved.
 *
 * The stdlib (`kira:*`) and its `@_magic` declarations are collected like any module. Markers
 * are read from `SourceContext.astIntrinsicMarked`, the way `KiraCCodeGenerator.isMagicDecl`
 * does: `@_magic` makes a class [ClassKind.MAGIC] and a declaration [Foreign.Magic];
 * `@_opaque` makes a class [ClassKind.OPAQUE]; `@_extern` becomes [Foreign.Extern] with its
 * arguments verbatim; `@_const` sets [FnSymbol.isConst]. Every marker is also kept, as the
 * parser saw it, in the symbol's `markers`.
 *
 * A declaration whose name is already taken in its module (or its class, trait or enum) is a
 * `types.decl.duplicate` error; the first declaration keeps the name.
 */
internal class DeclarationCollector(
    private val program: TypedProgram,
    private val modules: MutableList<ModuleSymbol>,
) {
    private val model = program.model

    fun collect() {
        val sources = program.unit.allSources()
            .filter { hasAst(it) }
            .map { it to moduleUriOf(it) }
            .sortedWith(compareBy({ it.second }, { it.first.file }))
        for ((source, uri) in sources) {
            val moduleDecl = moduleDeclOf(source)
            if (uri == null || moduleDecl == null) {
                continue
            }
            val existing = modules.firstOrNull { it.uri == uri }
            if (existing != null) {
                program.report(
                    "types.module.duplicate",
                    "Module '$uri' is declared by two files: ${existing.source.file} and ${source.file}. " +
                        "The second one is ignored.",
                    moduleDecl,
                )
                continue
            }
            val module = ModuleSymbol(uri, source)
            module.decl = moduleDecl
            modules.add(module)
            model.declSyms[moduleDecl] = module
            KiraTyper.guard(program, "collecting module $uri", moduleDecl) {
                collectModule(module, source)
            }
        }
        program.builtins = Builtins(
            modules.filter { it.isStdlib }.flatMap { m -> m.declarations.filterIsInstance<ClassSymbol>() }
        )
    }

    private fun hasAst(source: SourceContext): Boolean = runCatching { source.ast }.isSuccess

    private fun moduleDeclOf(source: SourceContext): ModuleDecl? =
        source.ast.statements.firstNotNullOfOrNull { (it as? Statement)?.expr as? ModuleDecl }

    private fun moduleUriOf(source: SourceContext): String? = moduleDeclOf(source)?.uri?.value

    private fun collectModule(module: ModuleSymbol, source: SourceContext) {
        var seenModuleDecl = false
        for (node in source.ast.statements) {
            if (node is UseStatement) {
                module.uses.add(node)
                continue
            }
            val stmt = node as? Statement
            if (stmt == null) {
                continue
            }
            when (val e = stmt.expr) {
                is ModuleDecl -> {
                    if (seenModuleDecl) {
                        program.report("types.module.second-decl", "A file declares exactly one module; this second module line is ignored.", e)
                    }
                    seenModuleDecl = true
                }
                is FunctionDecl -> guarded(e) { topLevel(module, function(module, source, e, null)) }
                is VariableDecl -> guarded(e) { topLevel(module, global(module, source, e)) }
                is ClassDecl -> guarded(e) { topLevel(module, classLike(module, source, e)) }
                is StructDecl -> guarded(e) { topLevel(module, struct(module, source, e)) }
                is TraitDecl -> guarded(e) { topLevel(module, trait(module, source, e)) }
                is EnumDecl -> guarded(e) { topLevel(module, enum(module, source, e)) }
                is TypeAliasDecl -> guarded(e) { topLevel(module, alias(module, source, e)) }
                is VariantDecl -> guarded(e) { topLevel(module, variant(module, source, e)) }
                else -> module.statements.add(stmt)
            }
        }
    }

    private fun guarded(node: ASTNode, step: () -> Unit) {
        KiraTyper.guard(program, "collecting a declaration", node, step)
    }

    private fun topLevel(module: ModuleSymbol, symbol: Symbol) {
        module.declarations.add(symbol)
        if (symbol is FnSymbol && symbol.isOperator) {
            module.operators.add(symbol)
            return
        }
        if (symbol is FnSymbol && symbol.name == ANONYMOUS) {
            return
        }
        val previous = module.members[symbol.name]
        if (previous != null) {
            duplicate(symbol, previous, "module ${module.uri}")
            return
        }
        module.members[symbol.name] = symbol
    }

    private fun duplicate(symbol: Symbol, previous: Symbol, where: String) {
        val at = previous.decl?.let { program.locate(it) }?.second
        program.report(
            "types.decl.duplicate",
            "'${symbol.name}' is already declared in $where${at?.let { " (line ${it.lineNumber})" } ?: ""}.",
            symbol.decl,
        )
    }

    // ---- markers ---------------------------------------------------------------------------

    private fun markersOf(source: SourceContext, node: ASTNode): List<Marker> {
        val invocations = invocationsOf(source, node)
        if (invocations.isNotEmpty()) {
            return invocations.map { Marker(it.intrinsicKey.name, it.parameters ?: emptyList(), it.namedParameters) }
        }
        val names = runCatching { source.astIntrinsicMarked[node] }.getOrNull() ?: return emptyList()
        return names.map { Marker(it.name) }
    }

    private fun Marker.isNamed(vararg names: String): Boolean = name in names

    private fun hasMarker(markers: List<Marker>, vararg names: String): Boolean = markers.any { it.isNamed(*names) }

    private fun foreignOf(markers: List<Marker>, name: String): Foreign? {
        markers.firstOrNull { it.isNamed(EXTERN) }?.let { m ->
            val params = linkedMapOf<String, String>()
            m.parameters.forEachIndexed { i, p -> params[if (i == 0) "symbol" else "symbol#$i"] = m.text(p) }
            m.namedParameters.forEach { (k, v) -> params[k] = m.text(v) }
            return Foreign.Extern(params)
        }
        markers.firstOrNull { it.isNamed(MAGIC, MAGIC_ALT) }?.let { m ->
            return Foreign.Magic(m.parameters.firstOrNull()?.let { m.text(it) } ?: name)
        }
        return null
    }

    // ---- declarations ----------------------------------------------------------------------

    private fun function(module: ModuleSymbol, source: SourceContext, decl: FunctionDecl, owner: TypeSymbol?): FnSymbol {
        val (name, isOperator) = when (val n = decl.name) {
            is IntrinsicExpr -> n.intrinsicKey.name to true
            AnonymousIdentifier -> ANONYMOUS to false
            is Identifier -> n.value to false
            else -> n.toString() to false
        }
        val typeParams = typeParams(module, decl.generics)
        val params = decl.def.parameters.mapIndexed { i, p ->
            ParamSymbol(
                p.name.value, module, p,
                byRef = Modifier.MUTABLE in p.modifiers,
                default = p.defaultValue,
                index = i,
            )
        }
        val markers = markersOf(source, decl)
        // A member of a magic class is magic (`Str.length`); a member of an extern class or
        // struct is extern under its own name (`Car.arm` is the C++ member `arm`).
        val ownerForeign = when (owner) {
            is ClassSymbol -> if (owner.kind == ClassKind.MAGIC) Foreign.Magic("") else owner.foreign
            is TraitSymbol -> owner.foreign
            else -> null
        }
        val foreign = foreignOf(markers, name) ?: when (ownerForeign) {
            is Foreign.Magic -> Foreign.Magic("${owner!!.name}.$name")
            is Foreign.Extern -> Foreign.Extern(emptyMap())
            null -> null
        }
        val fn = FnSymbol(
            name, module, decl, typeParams, params,
            owner = owner,
            isMutMethod = owner != null && Modifier.MUTABLE in decl.modifiers,
            foreign = foreign,
            hasBody = decl.def.body != null,
            isOverride = Modifier.OVERRIDE in decl.modifiers,
            isConst = hasMarker(markers, CONST),
        )
        fn.isPub = Modifier.PUBLIC in decl.modifiers
        fn.isOperator = isOperator
        fn.markers.addAll(markers)
        typeParams.forEach { it.owner = fn }
        params.forEach { it.fn = fn }
        model.declSyms[decl] = fn
        (decl.name as? Identifier)?.takeIf { it !is IntrinsicExpr && it !== AnonymousIdentifier }?.let { model.refs[it] = fn }
        val seen = HashMap<String, ParamSymbol>()
        for (p in params) {
            model.declSyms[p.decl!!] = p
            model.refs[(p.decl as FunctionDeclParameterExpr).name] = p
            val prev = seen.putIfAbsent(p.name, p)
            if (prev != null) {
                duplicate(p, prev, "the parameters of '$name'")
            }
        }
        return fn
    }

    private fun typeParams(module: ModuleSymbol, types: List<Type>): List<TypeParamSymbol> {
        val out = mutableListOf<TypeParamSymbol>()
        types.forEachIndexed { i, t ->
            val id = t.identifier as? Identifier
            if (id == null || id is IntrinsicExpr) {
                program.report("types.type-param.shape", "A type parameter is a plain name, like T.", t)
                return@forEachIndexed
            }
            if (t.children.isNotEmpty()) {
                program.report("types.type-param.shape", "Type parameter '${id.value}' cannot take type arguments.", t)
            }
            val tp = TypeParamSymbol(id.value, module, t, out.size)
            if (out.any { it.name == tp.name }) {
                duplicate(tp, out.first { it.name == tp.name }, "the type parameters")
            }
            out.add(tp)
            model.declSyms[t] = tp
            model.refs[id] = tp
        }
        return out
    }

    private fun global(module: ModuleSymbol, source: SourceContext, decl: VariableDecl): GlobalSymbol {
        val markers = markersOf(source, decl)
        val g = GlobalSymbol(
            decl.name.value, module, decl,
            isMut = Modifier.MUTABLE in decl.modifiers,
            init = decl.value,
        )
        g.isPub = Modifier.PUBLIC in decl.modifiers
        g.foreign = foreignOf(markers, g.name)
        g.markers.addAll(markers)
        model.declSyms[decl] = g
        model.refs[decl.name] = g
        return g
    }

    private fun classKind(markers: List<Marker>, default: ClassKind): ClassKind = when {
        hasMarker(markers, MAGIC, MAGIC_ALT) -> ClassKind.MAGIC
        hasMarker(markers, OPAQUE) -> ClassKind.OPAQUE
        else -> default
    }

    private fun declaredName(t: Type): Identifier? = (t.identifier as? Identifier)?.takeIf { it !is IntrinsicExpr }

    private fun classLike(module: ModuleSymbol, source: SourceContext, decl: ClassDecl): ClassSymbol {
        val markers = markersOf(source, decl)
        val cls = newClass(module, decl, decl.name, classKind(markers, ClassKind.CLASS), markers, Modifier.PUBLIC in decl.modifiers)
        cls.initially = decl.initially
        cls.finally = decl.finally
        members(module, source, cls, decl.members)
        return cls
    }

    private fun struct(module: ModuleSymbol, source: SourceContext, decl: StructDecl): ClassSymbol {
        val markers = markersOf(source, decl)
        val cls = newClass(module, decl, decl.name, classKind(markers, ClassKind.STRUCT), markers, Modifier.PUBLIC in decl.modifiers)
        cls.initially = decl.initially
        members(module, source, cls, decl.members)
        return cls
    }

    private fun variant(module: ModuleSymbol, source: SourceContext, decl: VariantDecl): ClassSymbol {
        program.report(
            "types.variant.unsupported",
            "variant '${declaredName(decl.name)?.value ?: decl.name}' has no typed lowering yet (deferred, design D43).",
            decl,
        )
        val markers = markersOf(source, decl)
        val cls = newClass(module, decl, decl.name, classKind(markers, ClassKind.CLASS), markers, Modifier.PUBLIC in decl.modifiers)
        members(module, source, cls, decl.members)
        return cls
    }

    private fun newClass(module: ModuleSymbol, decl: ASTNode, nameType: Type, kind: ClassKind, markers: List<Marker>, isPub: Boolean): ClassSymbol {
        val id = declaredName(nameType)
        val typeParams = typeParams(module, nameType.children)
        val cls = ClassSymbol(id?.value ?: nameType.toString(), module, decl, kind, typeParams)
        cls.isPub = isPub
        cls.markers.addAll(markers)
        cls.foreign = foreignOf(markers, cls.name)
        typeParams.forEach { it.owner = cls }
        model.declSyms[decl] = cls
        id?.let { model.refs[it] = cls }
        return cls
    }

    private fun members(module: ModuleSymbol, source: SourceContext, cls: ClassSymbol, members: List<FirstClassDecl>) {
        val names = HashMap<String, Symbol>()
        for (member in members) {
            val sym: Symbol = when (member) {
                is VariableDecl -> field(module, source, cls, member, cls.fields.size).also { cls.fields.add(it) }
                is FunctionDecl -> function(module, source, member, cls).also { cls.methods.add(it) }
                else -> {
                    program.report("types.class.member", "A ${cls.kind.name.lowercase()} holds fields and methods only.", member)
                    continue
                }
            }
            if (sym is FnSymbol && (sym.isOperator || sym.name == ANONYMOUS)) {
                continue
            }
            val prev = names.putIfAbsent(sym.name, sym)
            if (prev != null) {
                duplicate(sym, prev, cls.name)
            }
        }
    }

    private fun field(module: ModuleSymbol, source: SourceContext, owner: TypeSymbol, decl: VariableDecl, index: Int): FieldSymbol {
        val f = FieldSymbol(
            decl.name.value, module, decl, owner,
            isMut = Modifier.MUTABLE in decl.modifiers,
            isPub = Modifier.PUBLIC in decl.modifiers,
            isRequired = Modifier.REQUIRE in decl.modifiers,
            default = decl.value,
            index = index,
        )
        f.markers.addAll(markersOf(source, decl))
        model.declSyms[decl] = f
        model.refs[decl.name] = f
        return f
    }

    private fun trait(module: ModuleSymbol, source: SourceContext, decl: TraitDecl): TraitSymbol {
        val id = declaredName(decl.name)
        val markers = markersOf(source, decl)
        val typeParams = typeParams(module, decl.name.children)
        val t = TraitSymbol(id?.value ?: decl.name.toString(), module, decl, typeParams)
        t.isPub = Modifier.PUBLIC in decl.modifiers
        t.markers.addAll(markers)
        t.foreign = foreignOf(markers, t.name)
        typeParams.forEach { it.owner = t }
        model.declSyms[decl] = t
        id?.let { model.refs[it] = t }
        val names = HashMap<String, Symbol>()
        for (member in decl.members) {
            val fn = function(module, source, member, t)
            t.methods.add(fn)
            if (fn.isOperator || fn.name == ANONYMOUS) {
                continue
            }
            val prev = names.putIfAbsent(fn.name, fn)
            if (prev != null) {
                duplicate(fn, prev, t.name)
            }
        }
        return t
    }

    private fun enum(module: ModuleSymbol, source: SourceContext, decl: EnumDecl): EnumSymbol {
        val markers = markersOf(source, decl)
        val e = EnumSymbol(decl.name.value, module, decl)
        e.isPub = Modifier.PUBLIC in decl.modifiers
        e.markers.addAll(markers)
        e.foreign = foreignOf(markers, e.name)
        model.declSyms[decl] = e
        model.refs[decl.name] = e
        val names = HashMap<String, EnumEntrySymbol>()
        decl.members.forEachIndexed { i, member ->
            val entry = EnumEntrySymbol(member.name.value, e, i, member)
            e.entries.add(entry)
            model.declSyms[member] = entry
            model.refs[member.name] = entry
            val prev = names.putIfAbsent(entry.name, entry)
            if (prev != null) {
                duplicate(entry, prev, "enum ${e.name}")
            }
        }
        return e
    }

    private fun alias(module: ModuleSymbol, source: SourceContext, decl: TypeAliasDecl): AliasSymbol {
        val id = declaredName(decl.alias)
        val markers = markersOf(source, decl)
        val typeParams = typeParams(module, decl.alias.children)
        val a = AliasSymbol(id?.value ?: decl.alias.toString(), module, decl, typeParams)
        a.isPub = Modifier.PUBLIC in decl.modifiers
        a.markers.addAll(markers)
        typeParams.forEach { it.owner = a }
        model.declSyms[decl] = a
        id?.let { model.refs[it] = a }
        return a
    }

    companion object {
        const val ANONYMOUS = "<anonymous>"
        const val MAGIC = "_magic"
        const val MAGIC_ALT = "magic"
        const val EXTERN = "_extern"
        const val OPAQUE = "_opaque"
        const val CONST = "_const"

        /**
         * The marker invocations (arguments included) the parser recorded for [node].
         *
         * The frontend package (W1.1) adds `SourceContext.intrinsicInvocationsOf(node)` over
         * a field `astIntrinsicInvocations` with its parser work, after the AST contract this
         * package merged. Until neither exists this returns an empty list, and markers carry
         * their names only. Once either exists it is read here, and a read that fails is not
         * swallowed: it is an internal failure (KiraTyper.guard reports it as `types.internal`),
         * because an `@_extern` whose arguments went quietly missing would give the FFI package
         * nothing to bind. Replace the reflection with `source.intrinsicInvocationsOf(node)`
         * when both packages are on one branch; TyperPhaseTest.externArgumentsAreRecorded...
         * fails, not skips, if that read returns nothing on a branch with the dialect parser.
         */
        fun invocationsOf(source: SourceContext, node: ASTNode): List<IntrinsicExpr> {
            val read = invocationsReader ?: return emptyList()
            return read(source, node)
        }

        private val invocationsReader: ((SourceContext, ASTNode) -> List<IntrinsicExpr>)? by lazy {
            val method = runCatching {
                SourceContext::class.java.getMethod("intrinsicInvocationsOf", ASTNode::class.java)
            }.getOrNull()
            if (method != null) {
                return@lazy { source, node -> (method.invoke(source, node) as List<*>).filterIsInstance<IntrinsicExpr>() }
            }
            val field = runCatching {
                SourceContext::class.java.getDeclaredField("astIntrinsicInvocations").apply { isAccessible = true }
            }.getOrNull()
            if (field != null) {
                return@lazy { source, node ->
                    // null: a lateinit the parser never set (a hand-built AST), which has no arguments.
                    val map = field.get(source) as Map<*, *>?
                    (map?.get(node) as List<*>?)?.filterIsInstance<IntrinsicExpr>() ?: emptyList()
                }
            }
            null
        }
    }
}
