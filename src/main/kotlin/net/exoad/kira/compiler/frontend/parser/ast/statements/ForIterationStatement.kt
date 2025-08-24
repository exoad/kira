package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr

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