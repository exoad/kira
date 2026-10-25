package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.TypeParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.analysis.types.substitute
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr

/**
 * Type arguments satisfy their bounds (design 3.4): `rules.generics.bound`. Every spelled
 * type (`Box<Str>` where `class Box<T: Num>`) and every call with explicit type arguments
 * (`id<Str>(x)` where `fx id<T: Num>`) is checked; a bound may name another parameter of the
 * same declaration, so it is read with the arguments substituted. An inferred `@_infer`
 * argument is phase C's (types.call.bound) and is not repeated. A type parameter satisfies a
 * bound through its own bounds.
 */
internal class GenericBoundsPass : RulePass {
    override val name: String = "generics"

    override fun run(program: TypedProgram) {
        val r = Rules(program)
        for (m in program.modules) {
            AstTree.walk(m.source.ast) { n ->
                when (n) {
                    is Type -> if (n !is ConstTypeArg) typeNode(r, n)
                    is FunctionCallExpr -> if (n.typeArguments.isNotEmpty()) call(r, n)
                    else -> {}
                }
            }
        }
    }

    private fun typeNode(r: Rules, node: Type) {
        val t = r.model.typeRefs[node] as? KType.Nominal ?: return
        val params = t.sym.typeParams
        if (params.none { it.bounds.isNotEmpty() }) {
            return
        }
        check(r, t.sym.name, params, t.typeArgs(), node)
    }

    private fun call(r: Rules, e: FunctionCallExpr) {
        val rc = r.model.calls[e] ?: return
        val fn = rc.fn ?: return
        if (fn.typeParams.none { it.bounds.isNotEmpty() }) {
            return
        }
        check(r, fn.name, fn.typeParams, rc.typeArgs, e)
    }

    private fun check(r: Rules, owner: String, params: List<TypeParamSymbol>, args: List<KType>, at: ASTNode) {
        val sub = params.zip(args).toMap()
        for ((p, arg) in params.zip(args)) {
            if (arg.containsError()) {
                continue
            }
            for (b in p.bounds) {
                val bound = b.substitute(sub)
                if (!satisfies(r, arg, bound)) {
                    r.report(
                        "rules.generics.bound",
                        "${arg.display()} does not satisfy the bound of ${owner}'s type parameter ${p.name}: ${p.name} must be a ${bound.display()}.",
                        at,
                    )
                }
            }
        }
    }

    private fun satisfies(r: Rules, arg: KType, bound: KType): Boolean {
        if (r.facts.satisfies(arg, bound)) {
            return true
        }
        val boundNominal = bound as? KType.Nominal ?: return false
        return arg is KType.Param && r.facts.boundNominals(arg).any { r.facts.isSubtype(it, boundNominal) }
    }
}
