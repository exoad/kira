package net.exoad.kira.utils

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.RootASTNode
import net.exoad.kira.compiler.front.elements.*
import net.exoad.kira.compiler.front.exprs.*
import net.exoad.kira.compiler.front.exprs.decl.VariableDecl
import net.exoad.kira.compiler.front.statements.*
import java.text.SimpleDateFormat

object XMLASTVisitor : ASTVisitor()
{
    private val builder = StringBuilder()
    private val currentIndent = mutableListOf<String>()

    private fun appendLine(content: String)
    {
        builder.append(currentIndent.joinToString(""))
        builder.appendLine(content)
    }

    private fun xmlOpen(tag: String, attrs: String = "")
    {
        appendLine("<$tag${if(attrs.isNotEmpty()) " $attrs" else ""}>")
        pushIndent()
    }

    private fun xmlClose(tag: String)
    {
        popIndent()
        appendLine("</$tag>")
    }

    private fun xmlLeaf(tag: String, value: String, attrs: String = "")
    {
        appendLine("<$tag${if(attrs.isNotEmpty()) " $attrs" else ""}>${escapeXml(value)}</$tag>")
    }

    private fun xmlSingleLeaf(tag: String, attrs: String)
    {
        appendLine("<$tag${if(attrs.isNotEmpty()) " $attrs" else ""}/>")
    }

    private fun escapeXml(s: String): String =
            s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

    fun visitRootASTNode(rootASTNode: RootASTNode)
    {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!-- Kira AST Generated at ${SimpleDateFormat().format(System.currentTimeMillis())} -->""")
        xmlOpen("KiraProgram")
        rootASTNode.statements.forEach { it.accept(this) }
        xmlClose("KiraProgram")
    }

    override fun visitStatement(statementNode: StatementNode)
    {
        xmlOpen("Statement")
        statementNode.expr.accept(this)
        xmlClose("Statement")
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    {
        xmlOpen("IfSelectionStatement")
        xmlOpen("Condition")
        ifSelectionStatement.expr.accept(this)
        xmlClose("Condition")
        xmlOpen("Then")
        ifSelectionStatement.thenStatements.forEach { it.accept(this) }
        xmlClose("Then")
        ifSelectionStatement.elseBranches.forEach { it.accept(this) }
        xmlClose("IfSelectionStatement")
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    {
        xmlOpen("ElseIfBranch")
        xmlOpen("Condition")
        ifElseIfBranchNode.condition.accept(this)
        xmlClose("Condition")
        xmlOpen("Body")
        ifElseIfBranchNode.statements.forEach { it.accept(this) }
        xmlClose("Body")
        xmlClose("ElseIfBranch")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    {
        xmlOpen("ElseBranch")
        xmlOpen("Body")
        elseBranchNode.statements.forEach { it.accept(this) }
        xmlClose("Body")
        xmlClose("ElseBranch")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    {
        xmlOpen("WhileIterationStatement")
        xmlOpen("Condition")
        whileIterationStatement.condition.accept(this)
        xmlClose("Condition")
        xmlOpen("Body")
        whileIterationStatement.statements.forEach { it.accept(this) }
        xmlClose("Body")
        xmlClose("WhileIterationStatement")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    {
        xmlOpen("DoWhileIterationStatement")
        xmlOpen("Condition")
        doWhileIterationStatement.condition.accept(this)
        xmlClose("Condition")
        doWhileIterationStatement.statements.forEach { it.accept(this) }
        xmlClose("DoWhileIterationStatement")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr)
    {
        xmlOpen("BinaryExpr", """op="${escapeXml(binaryExpr.operator.toString())}"""")
        xmlOpen("Left")
        binaryExpr.leftExpr.accept(this)
        xmlClose("Left")
        xmlOpen("Right")
        binaryExpr.rightExpr.accept(this)
        xmlClose("Right")
        xmlClose("BinaryExpr")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr)
    {
        xmlOpen("UnaryExpr", """op="${escapeXml(unaryExpr.operator.toString())}"""")
        unaryExpr.operand.accept(this)
        xmlClose("UnaryExpr")
    }

    override fun visitIntegerLiteral(integerLiteralNode: IntegerLiteral)
    {
        xmlSingleLeaf("LInt", """value="${integerLiteralNode.value}"""")
    }

    override fun visitStringLiteral(stringLiteralNode: StringLiteral)
    {
        xmlSingleLeaf("LString", """value="${stringLiteralNode.value}"""")
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral)
    {
        xmlSingleLeaf("LBool", """value="${boolLiteral.value}"""")
    }

    override fun visitFloatLiteral(floatLiteralNode: FloatLiteral)
    {
        xmlSingleLeaf("LFloat", """value="${floatLiteralNode.value}"""")
    }

    override fun visitIdentifier(identifierNode: Identifier)
    {
        xmlLeaf("Identifier", identifierNode.name)
    }

    override fun visitType(typeNode: Type)
    {
        xmlLeaf("Type", typeNode.name)
    }

    override fun visitVariableDeclaration(variableDeclNode: VariableDecl)
    {
        xmlOpen("VariableDecl")
        variableDeclNode.name.accept(this)
        variableDeclNode.type.accept(this)
        variableDeclNode.value.accept(this)
        xmlClose("VariableDecl")
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr)
    {
        xmlOpen("AssignmentExpr")
        assignmentExpr.target.accept(this)
        assignmentExpr.value.accept(this)
        xmlClose("AssignmentExpr")
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr)
    {
        xmlOpen("FunctionCallExpr")
        functionCallExpr.parameters.forEach { it.accept(this) }
        xmlClose("FunctionCallExpr")
    }

    override fun visitIntrinsicCallExpr(intrinsicCallExpr: IntrinsicCallExpr)
    {
        val name = Builtin.Intrinsics.entries.find { it.name == intrinsicCallExpr.name.intrinsicKey.name }?.name
            ?: intrinsicCallExpr.name.intrinsicKey.name
        xmlOpen("IntrinsicCallExpr", """name="$name"""")
        xmlOpen("Parameters")
        intrinsicCallExpr.parameters.forEach { it.accept(this) }
        xmlClose("Parameters")
        xmlClose("IntrinsicCallExpr")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr)
    {
        xmlOpen("CompoundAssignmentExpr", """operator="${escapeXml(compoundAssignmentExpr.operator.toString())}"""")
        xmlOpen("LValue")
        compoundAssignmentExpr.left.accept(this)
        xmlClose("LValue")
        xmlOpen("RValue")
        compoundAssignmentExpr.right.accept(this)
        xmlClose("RValue")
        xmlClose("CompoundAssignmentExpr")
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