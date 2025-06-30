package net.exoad.kira.compiler.frontend.statements

import net.exoad.kira.compiler.frontend.ASTNode
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

open class IfSelectionStatement(
    condition: ExpressionNode, val thenStatements: List<StatementNode>,
    val elseBranches: List<IfElseBranchStatementNode> = emptyList(),
) :
    StatementNode(expression = condition)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIfSelectionStatement(this)
    }

    override fun toString(): String
    {
        return "IfStatement{ $expression -> $thenStatements { $elseBranches } }"
    }
}

sealed class IfElseBranchStatementNode : ASTNode()

class ElseIfBranchStatement(val condition: ExpressionNode, val statements: List<StatementNode>) :
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