package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier

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