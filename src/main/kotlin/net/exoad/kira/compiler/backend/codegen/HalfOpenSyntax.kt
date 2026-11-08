package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr

/** The removed `a.until(b)` and `xs.indices()` by their shape, for the C and JS backends, which read no typed model. */
object HalfOpenSyntax {
    /** Whether [e] is a call of either removed name, any arity. */
    fun isEither(e: Expr): Boolean = shape(e, "until") || shape(e, "indices")

    private fun shape(e: Expr, name: String): Boolean {
        val call = e as? FunctionCallExpr ?: return false
        val m = call.name as? MemberAccessExpr ?: return false
        val id = m.member as? Identifier ?: return false
        return id.javaClass == Identifier::class.java && id.value == name && call.typeArguments.isEmpty()
    }
}
