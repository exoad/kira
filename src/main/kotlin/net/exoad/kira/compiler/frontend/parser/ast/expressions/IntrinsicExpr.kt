package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceLocation

/**
 * `@name` or `@name(args)`. [parameters] is null for a bare marker. A marker
 * in modifier position may carry `name = literal` arguments as well, in
 * [namedParameters] (`@_extern(cpp = "f", header = "f.hxx")`).
 */
open class IntrinsicExpr(
    val intrinsicKey: CompilerIntrinsic,
    val sourceLocation: SourceLocation,
    val parameters: List<Expr>?, // if this is null, it is a marker, else it is a function (even if it is 0)
    val namedParameters: Map<String, Expr> = emptyMap(),
) : Identifier(intrinsicKey.name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIntrinsicExpr(this)
    }

    override fun toString(): String {
        return "IntrinsicExpr(key=${intrinsicKey.name}, loc=$sourceLocation, params=${parameters ?: "[]"}" +
            "${if (namedParameters.isNotEmpty()) ", named=$namedParameters" else ""})"
    }
}
