package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

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