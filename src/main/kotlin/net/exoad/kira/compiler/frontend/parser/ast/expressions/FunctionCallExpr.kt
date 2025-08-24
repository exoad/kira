package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class FunctionCallExpr(
    val name: Identifier,
    val positionalParameters: List<FunctionCallPositionalParameterExpr>,
    val namedParameters: List<FunctionCallNamedParameterExpr>,
) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionCallExpr(this)
    }

    override fun toString(): String
    {
        return "FunctionCallExpr{ $name -> {$positionalParameters} {$namedParameters} }"
    }
}