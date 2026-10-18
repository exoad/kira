package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement

/**
 * Phase A, second half: resolves each module's `use` statements to [ModuleSymbol]s, applies
 * the [TyperOptions] globs (profile, header-only, C++ namespace), and answers the scope
 * question every later phase asks: what does a name mean from inside a module?
 *
 * Scope order (design 3.2): a module's own members, then the `pub` members of the project
 * modules it `use`s, then of the stdlib modules it `use`s (each in `use` order), then the
 * `pub` members of the ambient stdlib (every `kira:*` module, by URI). Type parameters come
 * before all of these; TypeResolver handles them.
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

    /** Every module [m] reaches through `use`, directly or not ([m] itself excluded). */
    fun transitiveUses(m: ModuleSymbol): Set<ModuleSymbol> {
        val seen = LinkedHashSet<ModuleSymbol>()
        val queue = ArrayDeque(m.imports)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next === m || !seen.add(next)) {
                continue
            }
            queue.addAll(next.imports)
        }
        return seen
    }

    /** [name] as seen from [module], among the symbols [accept] takes. */
    fun lookup(module: ModuleSymbol, name: String, accept: (Symbol) -> Boolean = { true }): Lookup {
        module.members[name]?.takeIf(accept)?.let { return Lookup.Found(it, module) }
        module.memberImports[name]?.takeIf(accept)?.let { return Lookup.Found(it, it.module) }
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
            return Lookup.Ambiguous(tier.first(), tier.drop(1))
        }
        tier.firstOrNull()?.let { return it }
        for (std in ambient()) {
            if (std === module) {
                continue
            }
            std.members[name]?.takeIf { accept(it) && isPub(it) }?.let { return Lookup.Found(it, std) }
        }
        for (used in module.imports) {
            used.members[name]?.takeIf(accept)?.let { return Lookup.NotVisible(it, used) }
        }
        return Lookup.Missing
    }

    companion object {
        /** Whether a top-level symbol is `pub`. */
        fun isPub(symbol: Symbol): Boolean = when (symbol) {
            is ClassSymbol -> symbol.isPub
            is TraitSymbol -> symbol.isPub
            is EnumSymbol -> symbol.isPub
            is AliasSymbol -> symbol.isPub
            is GlobalSymbol -> symbol.isPub
            is FnSymbol -> symbol.isPub
            else -> false
        }
    }
}
