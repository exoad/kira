package net.exoad.kira.utils

import net.exoad.kira.compiler.frontend.ASTNode
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.RootASTNode
import net.exoad.kira.compiler.frontend.elements.*
import net.exoad.kira.compiler.frontend.expressions.AssignmentExpressionNode
import net.exoad.kira.compiler.frontend.expressions.BinaryExpressionNode
import net.exoad.kira.compiler.frontend.expressions.FunctionCallExpressionNode
import net.exoad.kira.compiler.frontend.expressions.UnaryExpressionNode
import net.exoad.kira.compiler.frontend.expressions.declarations.VariableDeclarationNode
import net.exoad.kira.compiler.frontend.statements.*

object ASTPrettyPrinterVisitor : ASTVisitor()
{
    private val builder = StringBuilder()
    private val currentIndent = mutableListOf<String>()

    private fun appendLine(content: String)
    {
        builder.append(currentIndent.joinToString(""))
        builder.appendLine(content)
    }

    fun visitRootASTNode(rootASTNode: RootASTNode)
    {
        appendLine("★")
        rootASTNode.statements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
    }

    override fun visitStatement(statementNode: StatementNode)
    {
        appendLine("Statement")
        pushIndent()
        statementNode.expression.accept(this)
        popIndent()
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    {
        appendLine("IfSelectionStatement")
        pushIndent()
        ifSelectionStatement.expression.accept(this)
        popIndent()
        ifSelectionStatement.thenStatements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
        ifSelectionStatement.elseBranches.forEach { branches ->
            pushIndent()
            branches.accept(this)
            popIndent()
        }
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    {
        appendLine("ElseIfBranchSelectionStatement")
        pushIndent()
        ifElseIfBranchNode.condition.accept(this)
        popIndent()
        ifElseIfBranchNode.statements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    {
        appendLine("ElseBranchSelectionStatement")
        popIndent()
        elseBranchNode.statements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    {
        appendLine("WhileIterationStatement")
        pushIndent()
        whileIterationStatement.condition.accept(this)
        popIndent()
        whileIterationStatement.statements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    {
        appendLine("DoWhileIterationStatement")
        pushIndent()
        doWhileIterationStatement.condition.accept(this)
        popIndent()
        doWhileIterationStatement.statements.forEach { statement ->
            pushIndent()
            statement.accept(this)
            popIndent()
        }
    }

    override fun visitBinaryExpression(binaryExpressionNode: BinaryExpressionNode)
    {
        appendLine("BinaryExpression{ ${binaryExpressionNode.operator} }")
        pushIndent()
        binaryExpressionNode.leftExpression.accept(this)
        popIndent()
        pushIndent()
        binaryExpressionNode.rightExpression.accept(this)
        popIndent()
    }

    override fun visitUnaryExpression(unaryExpressionNode: UnaryExpressionNode)
    {
        appendLine("UnaryExpression{ ${unaryExpressionNode.operator} }")
        pushIndent()
        unaryExpressionNode.operand.accept(this)
        popIndent()
    }

    override fun visitIntegerLiteral(integerLiteralNode: IntegerLiteralNode)
    {
        appendLine("LInt{ ${integerLiteralNode.value} }")
    }

    override fun visitStringLiteral(stringLiteralNode: StringLiteralNode)
    {
        appendLine("LString{ \"${stringLiteralNode.value}\" }")
    }

    override fun visitBoolLiteral(booleanLiteralNode: BoolLiteralNode)
    {
        appendLine("LBool{ ${booleanLiteralNode.value} }")
    }

    override fun visitFloatLiteral(floatLiteralNode: FloatLiteralNode)
    {
        appendLine("LFloat{ ${floatLiteralNode.value} }")
    }

    override fun visitIdentifier(identifierNode: IdentifierNode)
    {
        appendLine("Identifier{ '${identifierNode.name}' }")
    }

    override fun visitType(typeNode: TypeNode)
    {
        appendLine("Type{ '${typeNode.name}' }")
    }

    override fun visitVariableDeclaration(variableDeclarationNode: VariableDeclarationNode)
    {
        appendLine("VariableDeclaration")
        pushIndent()
        variableDeclarationNode.name.accept(this)
        popIndent()
        pushIndent()
        variableDeclarationNode.type.accept(this)
        popIndent()
        pushIndent()
        variableDeclarationNode.value.accept(this)
        popIndent()
    }

    override fun visitAssignmentExpression(assignmentExpressionNode: AssignmentExpressionNode)
    {
        appendLine("AssignmentExpression")
        pushIndent()
        assignmentExpressionNode.target.accept(this)
        popIndent()
        pushIndent()
        assignmentExpressionNode.value.accept(this)
        popIndent()
    }

    override fun visitFunctionCallExpression(functionCallExpressionNode: FunctionCallExpressionNode)
    {
        appendLine("FunctionCallExpression")
        pushIndent()
        functionCallExpressionNode.parameters.forEach { statement ->
            statement.accept(this)
            popIndent()
            pushIndent()
        }
    }

    private fun pushIndent()
    {
        currentIndent.add("    ")
    }

    private fun popIndent()
    {
        if(currentIndent.isNotEmpty())
        {
            currentIndent.removeLast()
        }
    }

    fun build(node: ASTNode): String
    {
        builder.clear()
        currentIndent.clear()

        if(node is RootASTNode)
        {
            visitRootASTNode(node)
        }
        else
        {
            node.accept(this)
        }
        val res = builder.toString()
        builder.clear()
        return res
    }
}