package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr

open class BreakStatement : Statement(NoExpr)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBreakStatement(this)
    }

    override fun toString(): String
    {
        return "BreakStatement{ }"
    }
}

open class ContinueStatement : Statement(NoExpr)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitContinueStatement(this)
    }

    override fun toString(): String
    {
        return "ContinueStatement{ }"
    }
}