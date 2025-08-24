package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Intrinsic

open class IntrinsicCallExpr(val name: Intrinsic, val parameters: List<Expr>) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIntrinsicCallExpr(this)
    }

    override fun toString(): String
    {
        return "IntrinsicCallExpr[[ $name ]]{ $parameters }"
    }
}