package net.exoad.kira.compiler

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FirstClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ModuleDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TypeAliasDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.QualifiedIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.source.SourceContext
import java.util.IdentityHashMap

/**
 * What needs every file read: each import's module, each type's role, and each slot's form. Run
 * once a unit is parsed, before anything reads it; again only when the unit has changed, from
 * the trees as parsed.
 */
object DialectLink {
    private val linked = java.util.WeakHashMap<CompilationUnit, Int>()

    /** Each source's tree as linked, and as parsed: a later link starts from the parsed one. */
    private val parsed = java.util.WeakHashMap<SourceContext, Pair<RootASTNode, RootASTNode>>()

    fun link(unit: CompilationUnit) {
        val sources = unit.allSources().filter { runCatching { it.ast }.isSuccess }
        val stamp = sources.sumOf { System.identityHashCode(it.ast) }
        if (linked[unit] == stamp) return
        for (s in sources) {
            parsed[s]?.let { (done, before) -> if (s.ast === done) s.ast = before }
        }
        val before = sources.associateWith { it.ast }
        imports(sources)
        roles(sources, Scopes(sources))
        for (s in sources) {
            if (s.ast !== before[s]) parsed[s] = s.ast to before.getValue(s)
        }
        linked[unit] = sources.sumOf { System.identityHashCode(it.ast) }
    }

    private fun moduleUri(source: SourceContext): String? =
        source.ast.statements.firstNotNullOfOrNull { (it as? Statement)?.expr as? ModuleDecl }?.uri?.value

    /** `a.b.c as name` names the module whose dotted name is `a.b.c`, or `a.b`'s member `c`. */
    private fun imports(sources: List<SourceContext>) {
        val byDotted = HashMap<String, String>()
        sources.forEach { s -> moduleUri(s)?.let { byDotted.putIfAbsent(ModuleNames.dotted(it), it) } }
        for (s in sources) {
            for (node in s.ast.statements) {
                val use = node as? UseStatement ?: continue
                val written = use.uri.value
                if (use.alias == null) continue
                val whole = byDotted[written]
                val owner = byDotted[written.substringBeforeLast('.', "")]
                when {
                    whole != null -> use.resolvedUri = whole
                    owner != null -> {
                        use.resolvedUri = owner
                        use.member = written.substringAfterLast('.')
                    }
                }
            }
        }
    }

    /**
     * What a type name means from inside each module, as the typer's scope reads it: the
     * module's own types, a name imported alone, then the stdlib's; `alias.Name` through an
     * import (old text also sees its imports' pub types bare, as ModuleGraph has it).
     */
    private class Scopes(sources: List<SourceContext>) {
        class Module(val uri: String) {
            val types = LinkedHashMap<String, Decl>()
            val aliases = HashMap<String, String>()
            val named = HashMap<String, Pair<String, String>>()
            val imports = mutableListOf<String>()
        }

        val modules = LinkedHashMap<String, Module>()
        val bySource = IdentityHashMap<SourceContext, Module>()
        private val stdlib: List<Module>

        init {
            for (s in sources) {
                val uri = moduleUri(s) ?: continue
                if (uri in modules) continue
                val m = Module(uri)
                modules[uri] = m
                bySource[s] = m
                for (node in s.ast.statements) {
                    if (node is UseStatement) {
                        val target = node.resolvedUri ?: continue
                        val alias = node.alias?.value ?: continue
                        val member = node.member
                        if (member != null) {
                            m.named.putIfAbsent(alias, target to member)
                        } else {
                            m.aliases.putIfAbsent(alias, target)
                            if (target !in m.imports) m.imports += target
                        }
                        continue
                    }
                    val e = (node as? Statement)?.expr ?: continue
                    val name = when (e) {
                        is ClassDecl, is StructDecl, is TraitDecl -> (e.name as? Type)?.identifier as? Identifier
                        is TypeAliasDecl -> e.alias.identifier as? Identifier
                        else -> null
                    } ?: continue
                    m.types.putIfAbsent(name.value, e as Decl)
                }
            }
            stdlib = modules.values.filter { it.uri.startsWith("${ModuleNames.STDLIB}:") }.sortedBy { it.uri }
        }

        /** The type declaration [t] names from inside [m], an alias followed to what it names; null for none. */
        fun lookup(m: Module, t: Type, depth: Int = 0): Decl? {
            val id = t.identifier as? Identifier ?: return null
            if (id is IntrinsicExpr || depth > 8) return null
            if (id is QualifiedIdentifier) {
                val owner = modules[m.aliases[id.qualifier.value] ?: return null] ?: return null
                return owner.types[id.value]?.takeIf(::isPub)?.let { follow(owner, it, depth) }
            }
            m.types[id.value]?.let { return follow(m, it, depth) }
            m.named[id.value]?.let { (uri, member) ->
                val owner = modules[uri] ?: return null
                return owner.types[member]?.takeIf(::isPub)?.let { follow(owner, it, depth) }
            }
            // Old text (`use`) also sees its imports' pub types bare, a project's first.
            if (!DialectGates.aliasOnlyImports) {
                val found = m.imports.mapNotNull { modules[it] }.mapNotNull { u -> u.types[id.value]?.takeIf(::isPub)?.let { u to it } }
                (found.firstOrNull { !it.first.uri.startsWith("${ModuleNames.STDLIB}:") } ?: found.firstOrNull())?.let { (u, d) ->
                    return follow(u, d, depth)
                }
            }
            for (std in stdlib) {
                if (std === m) continue
                std.types[id.value]?.takeIf(::isPub)?.let { return follow(std, it, depth) }
            }
            return null
        }

        private fun follow(m: Module, d: Decl, depth: Int): Decl? = if (d is TypeAliasDecl) lookup(m, d.target, depth + 1) else d

        private fun isPub(d: Decl): Boolean = when (d) {
            is ClassDecl -> Modifier.PUBLIC in d.modifiers
            is StructDecl -> Modifier.PUBLIC in d.modifiers
            is TraitDecl -> Modifier.PUBLIC in d.modifiers
            is TypeAliasDecl -> Modifier.PUBLIC in d.modifiers
            else -> false
        }
    }

    /** A type declaration where it stands. */
    private class TypeAt(val source: SourceContext, val module: Scopes.Module, val decl: Decl)

    /**
     * Trait and class are one kind of type, each keyed by its declaration. A type is a class
     * when it holds state (a field, `initially`, an intrinsic), is constructed, or has a class
     * parent; any other type is a trait when a type lists it as a parent or it has a slot.
     */
    private fun roles(sources: List<SourceContext>, scopes: Scopes) {
        val types = mutableListOf<TypeAt>()
        for (s in sources) {
            val m = scopes.bySource[s] ?: continue
            for (node in s.ast.statements) {
                when (val e = (node as? Statement)?.expr) {
                    is ClassDecl, is StructDecl, is TraitDecl -> types += TypeAt(s, m, e)
                    else -> {}
                }
            }
        }
        val parentsOf = IdentityHashMap<Decl, List<Decl>>()
        val listed = identitySet<Decl>()
        for (t in types) {
            val ps = parentTypes(t.decl).mapNotNull { scopes.lookup(t.module, it) }
            parentsOf[t.decl] = ps
            listed += ps
        }
        val constructed = identitySet<Decl>()
        for (s in sources) {
            val m = scopes.bySource[s] ?: continue
            AstTree.walk(s.ast) { n ->
                if (n is ObjectInitExpr) scopes.lookup(m, n.typeName)?.let { constructed += it }
            }
        }
        val classLike = identitySet<Decl>()
        for (t in types) {
            val c = t.decl as? ClassDecl ?: continue
            val marked = t.source.astIntrinsicMarked[c]?.isNotEmpty() == true
            if (marked || c.initially != null || c.members.any { holdsState(t.source, it) } || c in constructed) classLike += c
        }
        var grew = true
        while (grew) {
            grew = false
            for (t in types) {
                val c = t.decl as? ClassDecl ?: continue
                if (c !in classLike && parentsOf[c].orEmpty().any { it in classLike }) {
                    classLike += c
                    grew = true
                }
            }
        }
        val childrenOf = IdentityHashMap<Decl, MutableList<Decl>>()
        parentsOf.forEach { (child, ps) -> ps.forEach { childrenOf.getOrPut(it) { mutableListOf() } += child } }
        for (t in types) {
            val c = t.decl
            val slotted = membersOf(c).any { (it is FunctionDecl && it.def.body == null) || isSlotField(t.source, it) }
            val replacement = when {
                c is ClassDecl && c !in classLike && (c in listed || slotted) -> asTrait(t, c)
                t.source.astIntrinsicMarked[c]?.isNotEmpty() == true -> null
                else -> slotsResolved(t, overridden(c, childrenOf))
            } ?: continue
            replace(t.source, c, replacement)
        }
    }

    private fun parentTypes(d: Decl): List<Type> = when (d) {
        is ClassDecl -> d.parents
        is StructDecl -> d.traits
        is TraitDecl -> d.parents
        else -> emptyList()
    }

    private fun membersOf(d: Decl): List<FirstClassDecl> = when (d) {
        is ClassDecl -> d.members
        is StructDecl -> d.members
        is TraitDecl -> d.members
        else -> emptyList()
    }

    /** The names a subtype of [d] (at any depth) gives a body: the slots a subtype supplies. */
    private fun overridden(d: Decl, childrenOf: IdentityHashMap<Decl, MutableList<Decl>>): Set<String> {
        val out = HashSet<String>()
        val seen = identitySet<Decl>()
        val queue = ArrayDeque(childrenOf[d].orEmpty())
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            membersOf(c).forEach { m -> if (m is FunctionDecl && m.def.body != null) (m.name as? Identifier)?.let { out += it.value } }
            queue.addAll(childrenOf[c].orEmpty())
        }
        return out
    }

    /** A field: any variable but a slot. */
    private fun holdsState(s: SourceContext, m: FirstClassDecl): Boolean = m is VariableDecl && !isSlotField(s, m)

    /** `pub f: Fx<Tuple1<Int32>, Int32>` with nothing after it: a slot, spelled as a field. */
    private fun isSlotField(s: SourceContext, m: FirstClassDecl): Boolean =
        m is VariableDecl && m.value == null && isFx(m.type) && s.astIntrinsicMarked[m].isNullOrEmpty()

    private fun isFx(t: Type): Boolean {
        val id = t.identifier as? Identifier ?: return false
        return id !is QualifiedIdentifier && id !is IntrinsicExpr && id.value == "Fx" && t.children.size == 2
    }

    /** [c] as a trait, each slot an abstract function; null when a member cannot be one. */
    private fun asTrait(t: TypeAt, c: ClassDecl): TraitDecl? {
        val members = c.members.map { m ->
            when (m) {
                is FunctionDecl -> m
                is VariableDecl -> abstractOf(t.source, m) ?: return null
                else -> return null
            }
        }
        return TraitDecl(c.name, c.modifiers.toTypedArray(), members, c.parents)
    }

    /**
     * A slot (`f: (n: Int32) Int32` or `f: Fx<Tuple1<Int32>, Int32>`, nothing after) is one rule:
     * when a subtype overrides it, the subtype supplies it (an abstract function); else the
     * construction does (a field). [t]'s class with each slot so; null when none changes. A
     * value type has no subtype: its body-less function stays the prototype the host defines.
     */
    private fun slotsResolved(t: TypeAt, overridden: Set<String>): Decl? {
        val c = t.decl as? ClassDecl ?: return null
        var changed = false
        val members = c.members.map { m ->
            val name = (m.name as? Identifier)?.takeIf { it !is IntrinsicExpr }?.value
            val next = when {
                name == null -> null
                m is FunctionDecl && name !in overridden -> fieldOf(t.source, m)
                m is VariableDecl && name in overridden && isSlotField(t.source, m) -> abstractOf(t.source, m)
                else -> null
            }
            if (next != null) changed = true
            next ?: m
        }
        if (!changed) return null
        return ClassDecl(c.name, c.modifiers, members, c.parents, c.initially)
    }

    /** A body-less function as the field a construction fills; null when it cannot be one. */
    private fun fieldOf(s: SourceContext, f: FunctionDecl): VariableDecl? {
        val name = f.name as? Identifier ?: return null
        val def = f.def
        if (def.body != null || name is IntrinsicExpr || f.generics.isNotEmpty() || !s.astIntrinsicMarked[f].isNullOrEmpty()) return null
        if (Modifier.MUTABLE in f.modifiers || Modifier.OVERRIDE in f.modifiers || def.parameters.any { it.defaultValue != null }) return null
        val at = s.astOrigins[f]
        fun <T : ASTNode> origin(n: T): T = n.also { at?.let { p -> s.astOrigins[n] = p } }
        val args = def.parameters.map { p ->
            val pt = p.typeSpecifier
            if (Modifier.MUTABLE in p.modifiers) origin(Type(pt.identifier, pt.constraint, pt.children, true)) else pt
        }
        val tuple = origin(Type(origin(Identifier("Tuple${args.size}")), null, args))
        val fx = origin(Type(origin(Identifier("Fx")), null, listOf(tuple, def.returnTypeSpecifier)))
        return origin(VariableDecl(name, fx, null, f.modifiers))
    }

    /** A slot spelled as a field, as the abstract function a subtype overrides; null unless its type is `Fx<TupleN<..>, R>`. */
    private fun abstractOf(s: SourceContext, v: VariableDecl): FunctionDecl? {
        val tuple = v.type.children[0]
        val tupleName = (tuple.identifier as? Identifier)?.value ?: return null
        if (!tupleName.startsWith("Tuple") || tupleName.removePrefix("Tuple").toIntOrNull() != tuple.children.size) return null
        val at = s.astOrigins[v]
        fun <T : ASTNode> origin(n: T): T = n.also { at?.let { p -> s.astOrigins[n] = p } }
        val params = tuple.children.mapIndexed { i, pt ->
            val mods = if (pt.isMutParam) listOf(Modifier.MUTABLE) else emptyList()
            val type = if (pt.isMutParam) origin(Type(pt.identifier, pt.constraint, pt.children)) else pt
            origin(FunctionDeclParameterExpr(origin(Identifier("p$i")), type, mods))
        }
        val def = origin(FunctionDefExpr(v.type.children[1], params, null))
        return origin(FunctionDecl(v.name, def, v.modifiers - Modifier.MUTABLE))
    }

    private fun <T> identitySet(): MutableSet<T> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** [old]'s statement in [s] now holds [new], with [old]'s origin and intrinsics. */
    private fun replace(s: SourceContext, old: ASTNode, new: ASTNode) {
        val statements = s.ast.statements
        val i = statements.indexOfFirst { (it as? Statement)?.expr === old }
        if (i < 0) return
        s.astOrigins[old]?.let { s.astOrigins[new] = it }
        s.astIntrinsicMarked[old]?.let { s.astIntrinsicMarked[new] = it }
        s.intrinsicInvocationsOf(old).takeIf { it.isNotEmpty() }?.let { s.astIntrinsicInvocations[new] = it }
        val statement = Statement(new as Expr)
        s.astOrigins[statements[i]]?.let { s.astOrigins[statement] = it }
        val list = statements.toMutableList()
        list[i] = statement
        s.ast = RootASTNode(list)
    }
}
