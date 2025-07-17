package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

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