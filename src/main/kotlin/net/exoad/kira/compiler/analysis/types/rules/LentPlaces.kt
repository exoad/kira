package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.IndexKind
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypeFacts
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr

/**
 * A lent result is a place (40-round3 R-A), the rules pre-pass that runs first and fills
 * `TypedModel.lentPlaces`, which every reader reaches through `TypedModel.readPlace`.
 *
 * An accessor in [Rules.ACCESSORS] (`List`/`Arr`/`View`/`MutView` `get`, `Maybe.unwrap`,
 * `Result.unwrap`, `Result.unwrapErr`) returns, in C++, a reference into its receiver
 * (`kira::at`, `{self}[i]`, `kira::unwrap`), so its result is the receiver's storage, not a
 * temporary: DECISIONS 2 makes `a.@_op_get_(i)` and `a[i]` one call, and `a[i]` is a place
 * already. For `acc(r, ...)`:
 *
 * 1. `r` a place `q`, or itself a lent result, or a view lent from one (`xs.view()`,
 *    `v.from(1)`): the result is `q` with the step the other spelling records, `Index(kind, i)`
 *    for `get` (the kind of the storage's own type: `xs.view().get(0)` is an element of the
 *    `List` `xs`, `v.get(0)` of the view parameter `v`), `Field(value)` for `unwrap`,
 *    `Field(error)` for `unwrapErr`.
 * 2. Otherwise (a call result, a construction): nothing; the result is the temporary it is.
 *
 * An index or a field read through a lent result (`xs.view()[0]`, `ks.get(0).child`,
 * `gll.get(0)[1]`) is a place on the same terms; the typer records none there (or one with no
 * receiver, which aliases nothing).
 *
 * The table is read-only: `places` stays the table of assignable places, so `mut xs.get(0)`,
 * `xs.get(0) = v` and a `mut fx` on `xs.get(0)` are refused exactly as before. Where the
 * storage of a lent result on a second-class receiver lives for the view rule (its origins
 * with the step appended) is ViewPass's to record, in `viewOrigins`.
 */
internal class LentPlaces : RulePass {
    override val name: String = "lent"

    override fun run(program: TypedProgram) {
        Fill(program.model, TypeFacts(program.builtins)).run(Bodies.of(program))
    }

    private class Fill(val model: TypedModel, val facts: TypeFacts) {
        fun run(bodies: List<Body>) {
            // Post-order, so an inner lent result is recorded before the access built on it.
            fun visit(n: ASTNode) {
                AstTree.children(n).forEach { visit(it) }
                (n as? Expr)?.let { record(it) }
            }
            bodies.forEach { b -> b.roots.forEach { visit(it) } }
        }

        private fun record(e: Expr) {
            val lent = when (e) {
                is FunctionCallExpr -> model.calls[e]?.let { accessed(it) }
                // `a.m(...)` parsed as the member access around the call: its value is the call's.
                is MemberAccessExpr -> (e.member as? FunctionCallExpr)?.let { model.lentPlaces[it] } ?: field(e)
                is ArrayIndexExpr -> if (improvable(model.places[e])) {
                    base(e.originExpr)?.let { (q, t) -> Place.Index(q, kindOf(t), e.indexExpr) }
                } else {
                    null
                }
                else -> null
            }
            if (lent != null) {
                model.lentPlaces[e] = lent
            }
        }

        /** A field read off a lent result (`ks.get(0).child`): the typer's place for it has no receiver. */
        private fun field(e: MemberAccessExpr): Place? {
            val f = (model.members[e] as? MemberRef.Field)?.field ?: return null
            if (!improvable(model.places[e])) {
                return null
            }
            val (q, _) = base(e.origin) ?: return null
            return Place.Field(q, f)
        }

        /** No place, or one rooted at a receiver-less field (a call result's), which aliases nothing. */
        private fun improvable(p: Place?): Boolean {
            if (p == null) {
                return true
            }
            val root = p.root()
            return root is Place.Field && root.receiver == null
        }

        /** The place an accessor call's result is, or null (rule 1). */
        private fun accessed(rc: ResolvedCall): Place? {
            val key = (rc.fn?.foreign as? Foreign.Magic)?.key ?: return null
            if (key !in Rules.ACCESSORS) {
                return null
            }
            val recv = rc.receiver ?: return null
            val (q, t) = base(recv) ?: return null
            val member = key.substringAfter('.')
            return when (member) {
                "get" -> Place.Index(q, kindOf(t), (rc.args.firstOrNull() as? ArgBinding.Given)?.expr)
                "unwrap", "unwrapErr" -> {
                    val cls = (model.types[recv] as? KType.Nominal)?.sym as? ClassSymbol ?: return null
                    val name = if (member == "unwrap") "value" else "error"
                    cls.fields.firstOrNull { it.name == name }?.let { Place.Field(q, it) }
                }
                else -> null
            }
        }

        /**
         * The place whose storage [x] reads, and that storage's type: [x]'s own (assignable or
         * lent) place, or, for a view lent from a place (`xs.view()`, `v.from(1)`), that place.
         */
        private fun base(x: Expr): Pair<Place, KType?>? {
            model.readPlace(x)?.takeUnless { improvable(it) }?.let { return it to model.types[x] }
            val call = x as? FunctionCallExpr ?: (x as? MemberAccessExpr)?.member as? FunctionCallExpr ?: return null
            val rc = model.calls[call] ?: return null
            if (rc.fn?.foreign !is Foreign.Magic || rc.fn?.name !in Rules.LENDERS) {
                return null
            }
            return rc.receiver?.let { base(it) }
        }

        private fun kindOf(t: KType?): IndexKind = when {
            t == null -> IndexKind.OTHER
            t == KType.Str -> IndexKind.STR
            facts.isList(t) -> IndexKind.LIST
            facts.isArr(t) -> IndexKind.ARR
            facts.isView(t) -> IndexKind.VIEW
            facts.isMutView(t) -> IndexKind.MUT_VIEW
            facts.isMap(t) -> IndexKind.MAP
            else -> IndexKind.OTHER
        }
    }
}
