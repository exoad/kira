package net.exoad.kira.dialect

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.source.SourceContext

/**
 * The stage 3 proof: the old text's tree, with each block and node [ReturnRewrite] rewrote read
 * as the rewrite says it now is, equals the new text's tree. A return the parser reads as a
 * body's last value ([ReturnStatement.implicit]) is the return it was written as.
 */
class ReturnProof(private val oc: SourceContext, private val nc: SourceContext, private val rw: ReturnRewrite) {
    /** The function the first difference is in, when it is in one. */
    var failedIn: FunctionDefExpr? = null

    private val defs = ArrayDeque<FunctionDefExpr>()

    fun compare(): String? = list("root", oc.ast.statements, nc.ast.statements)

    private fun expected(o: List<*>): List<*> = rw.expectedLists[o]?.takeIf { rw.isActive(it.second) }?.first ?: o

    private fun list(path: String, o0: List<*>, n: List<*>): String? {
        val o = expected(o0)
        if (o.size != n.size) return fail("$path: ${o.size} entries became ${n.size}")
        for (i in o.indices) value("$path[$i]", o[i], n[i])?.let { return it }
        return null
    }

    private fun fail(why: String): String {
        if (failedIn == null) failedIn = defs.firstOrNull()
        return why
    }

    private fun value(path: String, o: Any?, n: Any?): String? {
        if (o is ASTNode && n is ASTNode) return node(path, o, n)
        if (o is List<*> && n is List<*>) return list(path, o, n)
        if (o is Array<*> && n is Array<*>) return list(path, o.toList(), n.toList())
        if (o is Map<*, *> && n is Map<*, *>) {
            if (o.keys != n.keys) return fail("$path: keys ${o.keys} became ${n.keys}")
            for (k in o.keys) value("$path[$k]", o[k], n[k])?.let { return it }
            return null
        }
        if (o != null && n != null && o.javaClass == n.javaClass && AstWalk.isAstPart(o)) {
            for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
            return null
        }
        if (o is net.exoad.kira.source.SourceLocation && n is net.exoad.kira.source.SourceLocation) return null
        if (o == n) return null
        return fail("$path: '$o' became '$n'")
    }

    private fun node(path: String, o0: ASTNode, n: ASTNode): String? {
        val o = rw.expectedNodes[o0]?.takeIf { rw.isActive(it.second) }?.first ?: o0
        if (o.javaClass != n.javaClass) return fail("$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}")
        val wanted = rw.parens[o] ?: (o in oc.astParenthesized)
        if (wanted != (n in nc.astParenthesized)) return fail("$path: parenthesized $wanted became ${!wanted}")
        val om = oc.intrinsicInvocationsOf(o).map { it.intrinsicKey.name }
        val nm = nc.intrinsicInvocationsOf(n).map { it.intrinsicKey.name }
        if (om != nm) return fail("$path: marks $om became $nm")
        val def = o as? FunctionDefExpr
        if (def != null) defs.addLast(def)
        try {
            for (f in AstWalk.fieldsOf(o.javaClass)) {
                // Written `return x` and a body's last `x` are one return.
                if (o is ReturnStatement && f.name == "implicit") continue
                value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
            }
        } finally {
            if (def != null) defs.removeLast()
        }
        return null
    }
}
