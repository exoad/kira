package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement

/**
 * Phase A, second half: resolves each module's `use` statements to [ModuleSymbol]s, applies
 * the [TyperOptions] globs (profile, header-only, C++ namespace), and answers the scope
 * question every later phase asks: what does a name mean from inside a module?
 *
 * Scope order: a module's own members, then its member aliases (`a.b.Name as Name`), then the
 * `pub` members of the ambient stdlib (every `kira:*` module, by URI), unless an imported project
 * module has that name pub too: old text meant the project's, so bare it is refused. An imported
 * module's names are reached through its alias (`b.Name`); with [DialectGates.aliasOnlyImports]
 * off (old text) they are also seen bare, a project's before the stdlib's, as `use` had them. Type
 * parameters come before all of these; TypeResolver handles them.
 */
class ModuleGraph(private val program: TypedProgram) {
    /** The result of a name lookup. */
    sealed interface Lookup {
        /** [symbol], found in [from]. */
        data class Found(val symbol: Symbol, val from: ModuleSymbol) : Lookup

        /** Only a non-`pub` member of a used module has this name. */
        data class NotVisible(val symbol: Symbol, val from: ModuleSymbol) : Lookup

        /** Two used modules export this name. The first is [first]. */
        data class Ambiguous(val first: Found, val others: List<Found>) : Lookup

        data object Missing : Lookup
    }

    fun link() {
        val options = program.options
        for (m in program.modules) {
            // Each alias names one thing, a module or a module's member; a module may have two.
            val aliased = HashMap<String, Pair<Any, UseStatement>>()
            fun claim(use: UseStatement, thing: Any, what: String): Boolean {
                val alias = use.alias ?: return true
                val prior = aliased.putIfAbsent(alias.value, thing to use) ?: return true
                if (prior.first === thing) return true
                program.report(
                    "types.use.alias-taken",
                    "'${alias.value}' already names ${prior.second.uri.value} here; give $what another alias.",
                    use,
                )
                return false
            }
            for (use in m.uses) {
                val uri = use.moduleUri
                val target = program.module(uri)
                val member = use.member
                when {
                    target == null -> program.report(
                        "types.use.unknown-module",
                        "No module named '${use.uri.value}' is part of this compilation.",
                        use,
                    )
                    member != null -> {
                        // `a.b.Name as Alias`: one pub name of the module, seen here as Alias.
                        val sym = target.members[member]
                        when {
                            sym == null -> program.report("types.use.unknown-member", "Module '${ModuleNames.dotted(target.uri)}' has no member '$member'.", use)
                            !isPub(sym) -> program.report("types.use.not-visible", "'$member' is not pub in module '${ModuleNames.dotted(target.uri)}'.", use)
                            claim(use, sym, use.uri.value) -> {
                                m.memberImports[use.alias!!.value] = sym
                                program.model.refs[use.alias] = sym
                            }
                        }
                    }
                    target === m -> program.report(
                        "types.use.self",
                        "Module '$uri' uses itself.",
                        use,
                        Severity.WARNING,
                    )
                    claim(use, target, use.uri.value) -> {
                        if (target !in m.imports) m.imports.add(target)
                        use.alias?.let {
                            m.importAliases[it.value] = target
                            program.model.refs[it] = target
                        }
                    }
                }
            }
            m.profile = if (options.freestanding.any { ModuleGlob.matches(it, m.uri) }) Profile.FREESTANDING else Profile.HOSTED
            // As CppOptions.isHeaderOnly: a kira:* stdlib module is always header-only.
            m.isHeaderOnly = m.isStdlib || options.headerOnly.any { ModuleGlob.matches(it, m.uri) }
            m.cppNamespace = ModuleSymbol.namespaceOf(options.namespaces, m.uri)
        }
    }

    /** Every stdlib module, by URI: their `pub` members are visible everywhere. */
    fun ambient(): List<ModuleSymbol> = program.modules.filter { it.isStdlib }.sortedBy { it.uri }

    /** Every module [m] reaches through its import lines, member aliases' too, directly or not ([m] itself excluded). */
    fun transitiveUses(m: ModuleSymbol): Set<ModuleSymbol> {
        val seen = LinkedHashSet<ModuleSymbol>()
        val queue = ArrayDeque(m.reached)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next === m || !seen.add(next)) {
                continue
            }
            queue.addAll(next.reached)
        }
        return seen
    }

    /** [name] as seen from [module], among the symbols [accept] takes. */
    fun lookup(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean = { true }): Lookup {
        module.members[name]?.takeIf(accept)?.let { return Lookup.Found(it, module) }
        module.memberImports[name]?.takeIf(accept)?.let { return Lookup.Found(it, it.module) }
        if (!DialectGates.aliasOnlyImports) {
            return bareImports(module, name, accept)
        }
        for (std in ambient()) {
            if (std === module) {
                continue
            }
            // An imported project module's pub name too: old text meant that one, so bare it is neither (outOfScope says why).
            std.members[name]?.takeIf { accept(it) && isPub(it) }?.let { return if (projectExport(module, name, accept) == null) Lookup.Found(it, std) else Lookup.Missing }
        }
        return nested(module, name, accept) ?: Lookup.Missing
    }

    /** The first module [module] imports, not the stdlib, that has [name] pub among what [accept] takes. */
    fun projectExport(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean): ModuleSymbol? =
        module.imports.firstOrNull { m -> !m.isStdlib && m.members[name]?.let { accept(it) && isPub(it) } == true }

    /**
     * A nested type (`Outer_Inner`, the parser's name for `Outer.Inner`) through its outermost
     * type, wherever that is seen from here: `Shape.Corner` with Shape a member alias.
     */
    private fun nested(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean): Lookup? {
        val cut = name.indexOf('_')
        if (cut <= 0 || name.none { it.isLowerCase() }) return null
        val outer = lookup(module, name.substring(0, cut)) { it is ClassSymbol || it is TraitSymbol } as? Lookup.Found ?: return null
        val home = outer.symbol.module
        if (home === module) return null
        val sym = home.members[name]?.takeIf(accept) ?: return null
        return if (isPub(sym)) Lookup.Found(sym, home) else Lookup.NotVisible(sym, home)
    }

    /**
     * Old text's rule (`use`): after a module's own names, every pub name of the modules it
     * imports, a project's before the stdlib's, then the ambient stdlib. Each answer the imports
     * give goes to [importTierHits].
     */
    private fun bareImports(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean): Lookup {
        val exported = mutableListOf<Lookup.Found>()
        for (used in module.imports) {
            val s = used.members[name]?.takeIf { accept(it) && isPub(it) } ?: continue
            if (exported.none { it.symbol === s }) {
                exported.add(Lookup.Found(s, used))
            }
        }
        // A used project module's name beats a used stdlib module's, as it beats the ambient stdlib's: the stdlib grows under a project.
        val tier = exported.filter { !it.from.isStdlib }.ifEmpty { exported }
        if (tier.size > 1) {
            return Lookup.Ambiguous(tier.first(), tier.drop(1)).also { importTierHits?.invoke(module, name, it) }
        }
        tier.firstOrNull()?.let { found -> return found.also { importTierHits?.invoke(module, name, it) } }
        for (std in ambient()) {
            if (std === module) {
                continue
            }
            std.members[name]?.takeIf { accept(it) && isPub(it) }?.let { return Lookup.Found(it, std) }
        }
        for (used in module.imports) {
            used.members[name]?.takeIf(accept)?.let { return Lookup.NotVisible(it, used).also { hit -> importTierHits?.invoke(module, name, hit) } }
        }
        return Lookup.Missing
    }

    /**
     * Why [name] means nothing bare in [module] though an import has it: the message naming both
     * fixes, a member alias or the qualified name, and the stdlib's when it has the name too; null
     * when no imported module has it.
     */
    fun outOfScope(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean = { true }): String? {
        val used = projectExport(module, name, accept) ?: module.imports.firstOrNull { m -> m.members[name]?.let { accept(it) && isPub(it) } == true }
            ?: module.imports.firstOrNull { m -> m.members[name]?.let(accept) == true } ?: return null
        fun fixes(m: ModuleSymbol): String {
            val alias = module.importAliases.entries.firstOrNull { it.value === m }?.key
            return "add `${ModuleNames.dotted(m.uri)}.$name as $name`" + (alias?.let { " or write `$it.$name`" } ?: "")
        }
        val dotted = ModuleNames.dotted(used.uri)
        val pub = if (isPub(used.members.getValue(name))) "" else " (it is not pub there: mark it `pub` first)"
        // The stdlib's same name, which lookup refused for the project's.
        val std = if (used.isStdlib) null else ambient().firstOrNull { it !== module && it.members[name]?.let { s -> accept(s) && isPub(s) } == true }
        if (std != null) {
            val stdDotted = ModuleNames.dotted(std.uri)
            return "'$name' is not in scope, as $dotted and $stdDotted both have it: ${fixes(used)}, or for $stdDotted's ${fixes(std)}."
        }
        return "'$name' is not in scope: ${fixes(used)}$pub."
    }

    companion object {
        /** kira-dialect's ear on old text: each bare name an import answered, in its module, with the answer. */
        @Volatile
        var importTierHits: ((ModuleSymbol, String, Lookup) -> Unit)? = null

        /** Whether a top-level symbol is `pub`. */
        fun isPub(symbol: Symbol): Boolean = when (symbol) {
            is ClassSymbol -> symbol.isPub
            is TraitSymbol -> symbol.isPub
            is AliasSymbol -> symbol.isPub
            is GlobalSymbol -> symbol.isPub
            is FnSymbol -> symbol.isPub
            else -> false
        }
    }
}
