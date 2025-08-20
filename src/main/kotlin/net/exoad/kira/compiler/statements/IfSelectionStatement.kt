package net.exoad.kira.compiler.statements

import net.exoad.kira.compiler.ASTNode
import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.exprs.Expr

open class IfSelectionStatement(
    condition: Expr, val thenStatements: List<Statement>,
    val elseBranches: List<IfElseBranchStatementNode> = emptyList(),
) :
    Statement(condition)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIfSelectionStatement(this)
    }

    override fun toString(): String
    {
        return "IfStatement{ $expr -> $thenStatements { $elseBranches } }"
    }
}

sealed class IfElseBranchStatementNode : ASTNode()

class ElseIfBranchStatement(val condition: Expr, val statements: List<Statement>) :
    IfElseBranchStatementNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIfElseIfBranchStatement(this)
    }
}

class ElseBranchStatement(val statements: List<Statement>) : IfElseBranchStatementNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitElseBranchStatement(this)
    }
}