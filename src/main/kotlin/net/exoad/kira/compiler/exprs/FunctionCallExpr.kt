package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier

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