package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/**
 * `T { a, b }` construction. [namedArgs] holds the spec's named construction
 * `T { y = 2, x = 1 }`; positional arguments come first, then named.
 */
open class ObjectInitExpr(
    val typeName: Type,
    val positionalArgs: List<Expr>,
    val namedArgs: List<FunctionCallNamedParameterExpr> = emptyList(),
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitObjectInitExpr(this)
    }

    override fun toString(): String {
        return "ObjectInit{ $typeName -> $positionalArgs${if (namedArgs.isNotEmpty()) " named=$namedArgs" else ""} }"
    }
}
