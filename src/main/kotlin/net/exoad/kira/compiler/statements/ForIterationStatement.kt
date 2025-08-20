package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.ForIterationExpr

class ForIterationStatement(val forIterationExpr: ForIterationExpr, val body: List<Statement>) :
    Statement(forIterationExpr)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitForIterationStatement(this)
    }

    override fun toString(): String
    {
        return "ForIterationStatement{ $forIterationExpr -> $body }"
    }
}