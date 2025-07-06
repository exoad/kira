package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier

open class ForIterationExpr(val initializer: Identifier, val target: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitForIterationExpr(this)
    }

    override fun toString(): String
    {
        return "ForIterationExpr{ $initializer -> $target }"
    }
}

