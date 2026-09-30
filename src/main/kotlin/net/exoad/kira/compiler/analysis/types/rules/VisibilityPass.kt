package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import java.util.IdentityHashMap

/**
 * Visibility of values and members (design 3.4; the spec: every declaration is private by
 * default). Phase B already reports a non-`pub` type used from another module
 * (types.type.not-visible), so types are not repeated here.
 *
 * - `rules.visibility.member`: a non-`pub` field or method used outside the class, struct or
 *   trait that declares it. A subclass is outside too: the C++ member is private.
 *   Constructing a value (`T { ... }`) supplies its `require` fields whatever their
 *   visibility, so construction is not a use here.
 * - `rules.visibility.module`: a non-`pub` function or global of another module, reached
 *   through `use` (phase C resolves it so the program still types; this pass refuses it).
 */
internal class VisibilityPass : RulePass {
    override val name: String = "visibility"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (b in Bodies.of(program)) {
            // `T { field = v }` and `f(name = v)` name what they supply; neither is a use of the field.
            val argumentNames = IdentityHashMap<Identifier, Boolean>()
            AstScan.walk(b.roots) { n, _ -> if (n is FunctionCallNamedParameterExpr) argumentNames[n.name] = true }
            AstScan.walk(b.roots) { n, _ ->
                when (n) {
                    is Identifier -> if (n !is IntrinsicExpr && !argumentNames.containsKey(n)) reference(r, b, n, r.model.refs[n])
                    is MemberAccessExpr -> when (val m = r.model.members[n]) {
                        is MemberRef.Field -> member(r, b, m.field, m.field.owner, m.field.isPub, n.member)
                        is MemberRef.Method -> member(r, b, m.fn, m.fn.owner, m.fn.isPub, n.member)
                        is MemberRef.ModuleMember -> moduleValue(r, b, m.symbol, n.member)
                        else -> {}
                    }
                    is FunctionCallExpr -> {
                        val rc = r.model.calls[n] ?: return@walk
                        val fn = rc.fn ?: return@walk
                        val owner = fn.owner
                        if (owner != null) {
                            member(r, b, fn, owner, fn.isPub, (n.name as? MemberAccessExpr)?.member ?: n.name)
                        }
                    }
                    is BinaryExpr, is UnaryExpr -> r.model.opCalls[n]?.fn?.let { fn -> if (fn.owner == null) moduleValue(r, b, fn, n) }
                    else -> {}
                }
            }
        }
    }

    private fun reference(r: Rules, b: Body, id: Identifier, sym: Symbol?) {
        when (sym) {
            is FnSymbol -> if (sym.owner == null) moduleValue(r, b, sym, id) else member(r, b, sym, sym.owner, sym.isPub, id)
            is GlobalSymbol -> moduleValue(r, b, sym, id)
            is FieldSymbol -> member(r, b, sym, sym.owner, sym.isPub, id)
            else -> {}
        }
    }

    private fun member(r: Rules, b: Body, sym: Symbol, owner: TypeSymbol?, isPub: Boolean, at: ASTNode) {
        if (isPub || owner == null || owner === b.owner) {
            return
        }
        val kind = if (sym is FieldSymbol) "Field" else "Method"
        r.report(
            "rules.visibility.member",
            "$kind '${sym.name}' of ${owner.name} is not pub, so it is reachable only inside ${owner.name} (a declaration is private " +
                "by default). Mark it `pub`, or reach it through a pub method.",
            at,
        )
    }

    private fun moduleValue(r: Rules, b: Body, sym: Symbol, at: ASTNode) {
        val isPub = when (sym) {
            is FnSymbol -> sym.isPub
            is GlobalSymbol -> sym.isPub
            else -> return
        }
        if (isPub || sym.module === b.module) {
            return
        }
        val kind = if (sym is FnSymbol) "Function" else "Constant"
        r.report(
            "rules.visibility.module",
            "$kind '${sym.name}' is declared in ${sym.module.uri} but is not pub, so ${b.module.uri} cannot use it. Mark it `pub`.",
            at,
        )
    }
}
