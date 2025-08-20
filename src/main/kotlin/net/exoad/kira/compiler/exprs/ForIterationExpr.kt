package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier

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

