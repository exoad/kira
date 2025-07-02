package net.exoad.kira.compiler.front.statements

import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr

open class IfSelectionStatement(
    condition: Expr, val thenStatements: List<StatementNode>,
    val elseBranches: List<IfElseBranchStatementNode> = emptyList(),
) :
    StatementNode(expr = condition)
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

class ElseIfBranchStatement(val condition: Expr, val statements: List<StatementNode>) :
    IfElseBranchStatementNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIfElseIfBranchStatement(this)
    }
}

class ElseBranchStatement(val statements: List<StatementNode>) : IfElseBranchStatementNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitElseBranchStatement(this)
    }
}