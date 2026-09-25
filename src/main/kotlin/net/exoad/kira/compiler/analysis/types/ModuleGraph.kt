package net.exoad.kira.compiler.analysis.types

/**
 * Phase A, second half: resolves each module's `use` statements to [ModuleSymbol]s, applies
 * the [TyperOptions] globs (profile, header-only, C++ namespace), and answers the scope
 * question every later phase asks: what does a name mean from inside a module?
 *
 * Scope order (design 3.2): a module's own members, then the `pub` members of the modules it
 * `use`s (in `use` order), then the `pub` members of the ambient stdlib (every `kira:*`
 * module, by URI). Type parameters come before all of these; TypeResolver handles them.
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
            for (use in m.uses) {
                val uri = use.uri.value
                val target = program.module(uri)
                when {
                    target == null -> program.report(
                        "types.use.unknown-module",
                        "No module named '$uri' is part of this compilation.",
                        use,
                    )
                    target === m -> program.report(
                        "types.use.self",
                        "Module '$uri' uses itself.",
                        use,
                        Severity.WARNING,
                    )
                    target !in m.imports -> m.imports.add(target)
                }
            }
            m.profile = if (options.freestanding.any { ModuleGlob.matches(it, m.uri) }) Profile.FREESTANDING else Profile.HOSTED
            m.isHeaderOnly = options.headerOnly.any { ModuleGlob.matches(it, m.uri) }
            m.cppNamespace = ModuleGlob.lookup(options.namespaces, m.uri) ?: ModuleSymbol.defaultNamespace(m.uri)
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
        val exported = module.imports.mapNotNull { used ->
            used.members[name]?.takeIf { accept(it) && isPub(it) }?.let { Lookup.Found(it, used) }
        }.distinctBy { System.identityHashCode(it.symbol) }
        if (exported.size > 1) {
            return Lookup.Ambiguous(exported.first(), exported.drop(1))
        }
        exported.firstOrNull()?.let { return it }
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
