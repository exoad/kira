package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.analysis.types.rules.AstScan
import net.exoad.kira.compiler.analysis.types.rules.Bodies
import net.exoad.kira.compiler.analysis.types.rules.Body
import net.exoad.kira.compiler.analysis.types.rules.BodyKind
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.core.intrinsics.OnlyIntrinsic
import java.util.IdentityHashMap

/**
 * `@_only` (rules.only.*): the targets each declaration is built for. A marker narrows its owner's set, never widens
 * it. Code built for a set S may use a declaration built for T only when S is within T, whichever target is being
 * built, so the source says what is allowed where. Then, for a build with a target, the declarations that target does
 * not build leave the program before the other rule passes and the backends read it. Target `none` keeps them all.
 */
internal class OnlyTargets(private val program: TypedProgram) {
    private val model = program.model
    private val every: Set<String> = OnlyIntrinsic.TARGETS.toSet()
    private val memo = IdentityHashMap<Symbol, Set<String>>()

    fun run() {
        markers()
        reach()
        program.options.target?.let { prune(it) }
    }

    // ---- availability ------------------------------------------------------------------------

    private fun markersOf(sym: Symbol): List<Marker> = when (sym) {
        is FnSymbol -> sym.markers
        is ClassSymbol -> sym.markers
        is TraitSymbol -> sym.markers
        is GlobalSymbol -> sym.markers
        is AliasSymbol -> sym.markers
        is FieldSymbol -> sym.markers
        else -> emptyList()
    }.filter { it.name == OnlyIntrinsic.name }

    private fun ownerOf(sym: Symbol): Symbol? = when (sym) {
        is FnSymbol -> sym.owner as? Symbol
        is FieldSymbol -> sym.owner as? Symbol
        is ParamSymbol -> sym.fn
        else -> null
    }

    /** The targets [sym] is built for: its own `@_only` within its owner's, else every target. */
    fun availability(sym: Symbol): Set<String> = memo[sym] ?: run {
        val own = markersOf(sym).firstOrNull()?.let { m -> OnlyIntrinsic.namesOf(m.parameters + m.namedParameters.values) }
        val within = ownerOf(sym)?.let { availability(it) } ?: every
        (if (own == null) within else own.intersect(within)).also { memo[sym] = it }
    }

    private fun names(set: Set<String>): String = set.joinToString(", ")

    private fun marker(set: Set<String>): String = set.joinToString(", ", "@_only(", ")") { "\"$it\"" }

    private fun shown(set: Set<String>): String = if (set.containsAll(every)) "every target" else names(set)

    // ---- the markers themselves --------------------------------------------------------------

    private fun declared(): List<Symbol> = buildList {
        for (m in program.modules) {
            for (s in m.declarations) {
                add(s)
                when (s) {
                    is ClassSymbol -> {
                        addAll(s.fields)
                        addAll(s.methods)
                    }
                    is TraitSymbol -> addAll(s.methods)
                    else -> {}
                }
            }
        }
    }

    private fun markers() {
        for (sym in declared()) {
            val marks = markersOf(sym)
            if (marks.isEmpty()) {
                continue
            }
            val m = marks.first()
            val at = sym.decl
            val what = "'${sym.name}'"
            val spelling = if (marks.size > 1) "@_only is written twice on $what" else OnlyIntrinsic.spellingError(m.parameters, m.namedParameters)
            if (spelling != null) {
                program.report("rules.only.names", spelling, at)
                continue
            }
            val place = when {
                sym is AliasSymbol -> "a type alias is resolved away before emission, so it has no target of its own"
                sym is FieldSymbol -> "a field would change a construction's shape per target"
                sym is FnSymbol && sym.owner is TraitSymbol -> "a trait's members are agreed on by every implementer"
                sym is FnSymbol && sym.isOverride -> "the method it overrides decides which targets it exists on"
                else -> null
            }
            if (place != null) {
                program.report("rules.only.place", "@_only does not mark $what: $place.", at)
                continue
            }
            val owner = ownerOf(sym)
            if (owner != null) {
                val own = OnlyIntrinsic.namesOf(m.parameters)
                val outer = availability(owner)
                if (!outer.containsAll(own)) {
                    program.report(
                        "rules.only.widen",
                        "$what is limited to ${names(own)}, but its ${owner.name} is built for ${shown(outer)} only: a member may narrow its owner's targets, never widen them.",
                        at,
                    )
                }
            }
        }
    }

    // ---- reach -------------------------------------------------------------------------------

    private fun reach() {
        for (sym in declared()) {
            when (sym) {
                is FnSymbol -> {
                    val user = availability(sym)
                    val seen = IdentityHashMap<Symbol, Boolean>()
                    sym.params.forEach { p -> typeUse(user, p.type, "'${sym.name}'", p.decl ?: sym.decl, seen) }
                    typeUse(user, sym.ret, "'${sym.name}'", sym.decl, seen)
                }
                is FieldSymbol -> typeUse(availability(sym), sym.type, "the field '${sym.name}'", sym.decl, IdentityHashMap())
                is GlobalSymbol -> typeUse(availability(sym), sym.type, "'${sym.name}'", sym.decl, IdentityHashMap())
                is ClassSymbol -> {
                    val user = availability(sym)
                    val seen = IdentityHashMap<Symbol, Boolean>()
                    sym.superclass?.let { typeUse(user, it, "'${sym.name}'", sym.decl, seen) }
                    sym.traits.forEach { typeUse(user, it, "'${sym.name}'", sym.decl, seen) }
                }
                else -> {}
            }
        }
        for (b in Bodies.of(program)) {
            bodyUse(b)
        }
    }

    private fun userOf(b: Body): Set<String> {
        b.fn?.let { return availability(it) }
        if (b.kind == BodyKind.INITIALLY) {
            return (b.owner as? Symbol)?.let { availability(it) } ?: every
        }
        return when (val declared = b.at?.let { model.declSyms[it] }) {
            null -> every
            is FieldSymbol, is ParamSymbol, is GlobalSymbol -> availability(declared)
            else -> every
        }
    }

    private fun bodyUse(b: Body) {
        val user = userOf(b)
        if (!anyLimited()) {
            return
        }
        val seen = IdentityHashMap<Symbol, Boolean>()
        val argumentNames = IdentityHashMap<Identifier, Boolean>()
        AstScan.walk(b.roots) { n, _ -> if (n is FunctionCallNamedParameterExpr) argumentNames[n.name] = true }
        val touch = { used: Symbol?, at: ASTNode -> use(user, used, b.what, at, seen) }
        AstScan.walk(b.roots) { n, _ ->
            when (n) {
                is Identifier -> if (n !is IntrinsicExpr && !argumentNames.containsKey(n)) touch(model.refs[n], n)
                is MemberAccessExpr -> when (val m = model.members[n]) {
                    is MemberRef.Field -> touch(m.field, n)
                    is MemberRef.Method -> touch(m.fn, n)
                    is MemberRef.ModuleMember -> touch(m.symbol, n)
                    else -> {}
                }
                is FunctionCallExpr -> touch(model.calls[n]?.fn, n)
                is BinaryExpr, is UnaryExpr -> touch(model.opCalls[n]?.fn, n)
                is ObjectInitExpr -> touch(model.inits[n]?.cls, n)
                is Type -> model.typeRefs[n]?.let { typeUse(user, it, b.what, n, seen) }
                else -> {}
            }
        }
    }

    private var limitedAny: Boolean? = null

    /** Whether any declaration of the program carries an `@_only`; a program with none skips every body walk. */
    private fun anyLimited(): Boolean = limitedAny ?: declared().any { markersOf(it).isNotEmpty() }.also { limitedAny = it }

    private fun typeUse(user: Set<String>, t: KType, what: String, at: ASTNode?, seen: IdentityHashMap<Symbol, Boolean>) {
        when (t) {
            is KType.Nominal -> {
                use(user, t.sym as? Symbol, what, at, seen)
                t.args.forEach { if (it is TypeArg.Ty) typeUse(user, it.t, what, at, seen) }
            }
            is KType.Fn -> {
                t.params.forEach { typeUse(user, it.type, what, at, seen) }
                typeUse(user, t.ret, what, at, seen)
            }
            else -> {}
        }
    }

    private fun use(user: Set<String>, used: Symbol?, what: String, at: ASTNode?, seen: IdentityHashMap<Symbol, Boolean>) {
        if (used == null || !anyLimited()) {
            return
        }
        val has = availability(used)
        if (has.containsAll(user) || seen.put(used, true) != null) {
            return
        }
        program.report(
            "rules.only.reach",
            "'${used.name}' is built for ${shown(has)} only, but $what is built for ${shown(user)}: " +
                "mark $what ${marker(has)}, or give '${used.name}' the targets it needs.",
            at,
        )
    }

    // ---- the drop ----------------------------------------------------------------------------

    private fun prune(target: String) {
        val gone = { s: Symbol -> target !in availability(s) }
        for (m in program.modules) {
            m.declarations.removeAll(gone)
            m.members.values.removeAll(gone)
            m.operators.removeAll(gone)
            for (c in m.declarations.filterIsInstance<ClassSymbol>()) {
                c.methods.removeAll(gone)
            }
        }
    }
}
