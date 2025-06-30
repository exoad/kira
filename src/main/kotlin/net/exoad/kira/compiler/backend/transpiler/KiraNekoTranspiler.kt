package net.exoad.kira.compiler.backend.transpiler

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.GeneratedProvider
import net.exoad.kira.compiler.frontend.TokensProvider
import net.exoad.kira.compiler.frontend.elements.*
import net.exoad.kira.compiler.frontend.expressions.*
import net.exoad.kira.compiler.frontend.expressions.declarations.VariableDeclarationNode
import net.exoad.kira.compiler.frontend.statements.*
import java.io.File

object KiraNekoTranspiler : KiraTranspiler(
    canonicalName = "Kira to NekoVM Transpiler",
    targetLanguage = "Neko",
    fileExtension = "neko"
)
{
    val sb = StringBuilder()

    override fun visitStatement(statementNode: StatementNode)
    {
        statementNode.expression.accept(this)
        sb.appendLine(";")
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    {
        TODO("Not yet implemented")
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    {
        TODO("Not yet implemented")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    {
        TODO("Not yet implemented")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    {
        sb.append("while ")
        whileIterationStatement.expression.accept(this)
        sb.appendLine(" {")
        whileIterationStatement.statements.forEach { it.accept(this) }
        sb.appendLine("}")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    {
        TODO("Not yet implemented")
    }

    override fun visitBinaryExpression(binaryExpressionNode: BinaryExpressionNode)
    {
        sb.append("(")
        binaryExpressionNode.leftExpression.accept(this)
        sb.append(binaryExpressionNode.operator.symbol.joinToString("") { it.toString() })
        binaryExpressionNode.rightExpression.accept(this)
        sb.append(")")
    }

    override fun visitUnaryExpression(unaryExpressionNode: UnaryExpressionNode)
    {
        sb.append("(")
        sb.append(unaryExpressionNode.operator.symbol.rep)
        unaryExpressionNode.operand.accept(this)
        sb.append(")")
    }

    override fun visitAssignmentExpression(assignmentExpressionNode: AssignmentExpressionNode)
    {
        assignmentExpressionNode.target.accept(this)
        sb.append(" = ")
        assignmentExpressionNode.value.accept(this)
    }

    override fun visitFunctionCallExpression(functionCallExpressionNode: FunctionCallExpressionNode)
    {
        TODO("Not yet implemented")
    }

    override fun visitIntrinsicCallExpression(intrinsicCallExpression: IntrinsicCallExpression)
    {
        when(intrinsicCallExpression.name.intrinsicKey)
        {
            Builtin.Intrinsics.TRACE ->
            {
                if(intrinsicCallExpression.parameters.size > 1)
                {
                    Diagnostics.Logging.warn(
                        "KiraNekoTranspiler::visitIntrinsicCallExpression",
                        "The intrinsic '${Builtin.Intrinsics.TRACE.rep}' only accepts one argument. Ignoring the rest..."
                    )
                }
                sb.append(
                    "\$print(\"[${
                        intrinsicCallExpression.name.absoluteFileLocation.srcFile.replace(
                            "\\",
                            "\\\\"
                        )
                    }:${intrinsicCallExpression.name.absoluteFileLocation.lineNumber}:${intrinsicCallExpression.name.absoluteFileLocation.column}]\"+"
                )
                intrinsicCallExpression.parameters.first().accept(this)
                sb.append(")")
            }
        }
    }

    override fun visitIntegerLiteral(integerLiteralNode: IntegerLiteralNode)
    {
        sb.append(integerLiteralNode.value)
    }

    override fun visitStringLiteral(stringLiteralNode: StringLiteralNode)
    {
        sb.append('"')
        sb.append(stringLiteralNode.value)
        sb.append('"')
    }

    override fun visitBoolLiteral(booleanLiteralNode: BoolLiteralNode)
    {
        sb.append(booleanLiteralNode.value)
    }

    override fun visitFloatLiteral(floatLiteralNode: FloatLiteralNode)
    {
        sb.append(floatLiteralNode.value)
    }

    override fun visitIdentifier(identifierNode: IdentifierNode)
    {
        sb.append(identifierNode.name)
    }

    override fun visitType(typeNode: TypeNode)
    {
        sb.append(typeNode.name)
    }

    override fun visitVariableDeclaration(variableDeclarationNode: VariableDeclarationNode)
    {
        sb.append("var ")
        variableDeclarationNode.name.accept(this)
        sb.append("/*")
        variableDeclarationNode.type.accept(this)
        sb.append("*/ =")
        variableDeclarationNode.value.accept(this)
    }

    override fun transpile()
    {
        sb.clear()
        TokensProvider.rootASTNode.statements.forEach {
            it.accept(this)
        }
        val outputFile =
                File("${GeneratedProvider.outputFile}${if(!GeneratedProvider.outputFile.contains(".")) ".$fileExtension" else ""}")
        if(!outputFile.exists())
        {
            outputFile.createNewFile()
        }
        outputFile.writeText(sb.toString())
        Diagnostics.Logging.info("KiraNekoTranspiler", "Transpiled Neko output to ${outputFile.absolutePath}")
    }
}