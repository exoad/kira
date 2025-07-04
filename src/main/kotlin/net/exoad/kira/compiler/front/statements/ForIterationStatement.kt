package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.ForIterationExpr

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