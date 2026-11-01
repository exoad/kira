package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr

/** `a.until(b)` and `xs.indices()` by their shape, for the C and JS backends, which read no typed model. */
object HalfOpenSyntax {
    /** `a.until(b)`'s begin and end, else null. */
    fun until(e: Expr): Pair<Expr, Expr>? {
        val (origin, call) = shape(e, "until") ?: return null
        return call.positionalParameters.singleOrNull()?.let { origin to it.value }
    }

    /** Whether [e] is `xs.indices()`. */
    fun isIndices(e: Expr): Boolean = shape(e, "indices")?.second?.positionalParameters?.isEmpty() == true

    /** Whether [e] is either, any arity. */
    fun isEither(e: Expr): Boolean = shape(e, "until") != null || shape(e, "indices") != null

    private fun shape(e: Expr, name: String): Pair<Expr, FunctionCallExpr>? {
        val call = e as? FunctionCallExpr ?: return null
        val m = call.name as? MemberAccessExpr ?: return null
        val id = m.member as? Identifier ?: return null
        return if (id.javaClass == Identifier::class.java && id.value == name && call.typeArguments.isEmpty()) m.origin to call else null
    }
}
