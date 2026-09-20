package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class IfSelectionStatement(
    condition: Expr,
    val thenStatements: List<Statement>,
    val elseBranches: List<IfElseBranchStatementNode> = emptyList(),
) :
    Statement(condition) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIfSelectionStatement(this)
    }

    override fun toString(): String {
        return "IfSelection(condition=$expr, then=$thenStatements, else=$elseBranches)"
    }
}

sealed class IfElseBranchStatementNode : ASTNode()

class ElseIfBranchStatement(val condition: Expr, val statements: List<Statement>) : IfElseBranchStatementNode() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIfElseIfBranchStatement(this)
    }

    override fun toString(): String {
        return "ElseIfBranch(condition=$condition, body=$statements)"
    }
}

class ElseBranchStatement(val statements: List<Statement>) : IfElseBranchStatementNode() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitElseBranchStatement(this)
    }

    override fun toString(): String {
        return "ElseBranch(body=$statements)"
    }
}