package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

open class FunctionCallExpr(val name: Identifier, val parameters: List<Expr>) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionCallExpr(this)
    }

    override fun toString(): String
    {
        return "FunctionCallExpr{ $name -> $parameters }"
    }
}