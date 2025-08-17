package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

open class FunctionCallNamedParameterExpr(val name: Identifier, val value: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionCallNamedParameterExpr(this)
    }

    override fun toString(): String
    {
        return "FunctionCallNamedParameterExpr{ $name -> $value }"
    }
}

open class FunctionCallPositionalParameterExpr(val position: Int, val value: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionCallPositionalParameterExpr(this)
    }

    override fun toString(): String
    {
        return "FunctionCallPositionalParameterExpr{ $position -> $value }"
    }
}