package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr

open class ContinueStatement : Statement(NoExpr)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitContinueStatement(this)
    }

    override fun toString(): String
    {
        return "CONTINUE{ }"
    }
}