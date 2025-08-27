package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class ForIterationExpr(val initializer: Identifier, val target: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitForIterationExpr(this)
    }

    override fun toString(): String
    {
        return "ForIter{ $initializer -> $target }"
    }
}

