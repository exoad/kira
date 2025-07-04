package net.exoad.kira.utils

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.RootASTNode
import net.exoad.kira.compiler.front.elements.*
import net.exoad.kira.compiler.front.exprs.*
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionFirstClassDecl
import net.exoad.kira.compiler.front.exprs.decl.VariableFirstClassDecl
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

    override fun visitStatement(statement: Statement)
    {
        xmlOpen("Statement")
        statement.expr.accept(this)
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

    override fun visitReturnStatement(returnStatement: ReturnStatement)
    {
        xmlOpen("ReturnStatement")
        returnStatement.expr.accept(this)
        xmlClose("ReturnStatement")
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

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral)
    {
        xmlSingleLeaf("LInt", """value="${integerLiteral.value}"""")
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral)
    {
        xmlSingleLeaf("LString", """value="${stringLiteral.value}"""")
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral)
    {
        xmlSingleLeaf("LBool", """value="${boolLiteral.value}"""")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral)
    {
        xmlSingleLeaf("LFloat", """value="${floatLiteral.value}"""")
    }

    override fun visitIdentifier(identifier: Identifier)
    {
        xmlLeaf("Identifier", identifier.name)
    }

    override fun visitType(typeNode: Type)
    {
        xmlLeaf("Type", typeNode.name)
    }

    override fun visitVariableDecl(variableDecl: VariableFirstClassDecl)
    {
        xmlOpen(
            "VariableDecl",
            when(variableDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${variableDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        variableDecl.name.accept(this)
        variableDecl.type.accept(this)
        if(variableDecl.value != null)
        {
            xmlOpen("Value")
            variableDecl.value!!.accept(this)
            xmlClose("Value")
        }
        xmlClose("VariableDecl")
    }

    override fun visitFunctionDecl(functionDecl: FunctionFirstClassDecl)
    {
        xmlOpen(
            "FunctionDecl",
            when(functionDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${functionDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        functionDecl.name.accept(this)
        functionDecl.returnType.accept(this)
        xmlOpen("Parameters")
        functionDecl.parameters.forEach { it.accept(this) }
        xmlClose("Parameters")
        xmlOpen("Body")
        functionDecl.body?.forEach { it.accept(this) }
        xmlClose("Body")
        xmlClose("FunctionDecl")
    }

    override fun visitClassDecl(classDecl: ClassDecl)
    {
        xmlOpen(
            "ClassDecl", when(classDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${classDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        classDecl.name.accept(this)
        xmlOpen("Members")
        classDecl.members.forEach { it.accept(this) }
        xmlClose("Members")
        xmlClose("ClassDecl")
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
        functionCallExpr.name.accept(this)
        xmlOpen("Parameters")
        functionCallExpr.parameters.forEach { it.accept(this) }
        xmlClose("Parameters")
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
        xmlOpen("CompoundAssignmentExpr", """op="${escapeXml(compoundAssignmentExpr.operator.toString())}"""")
        xmlOpen("LValue")
        compoundAssignmentExpr.left.accept(this)
        xmlClose("LValue")
        xmlOpen("RValue")
        compoundAssignmentExpr.right.accept(this)
        xmlClose("RValue")
        xmlClose("CompoundAssignmentExpr")
    }

    override fun visitFunctionParameterExpr(functionParameterExpr: FunctionParameterExpr)
    {
        xmlOpen(
            "FunctionParameterExpr",
            when(functionParameterExpr.modifiers.isNotEmpty())
            {
                true -> """modifiers="${functionParameterExpr.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        functionParameterExpr.name.accept(this)
        functionParameterExpr.type.accept(this)
        xmlClose("FunctionParameterExpr")
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr)
    {
        xmlOpen("MemberAccessExpr")
        xmlOpen("Origin")
        memberAccessExpr.origin.accept(this)
        xmlClose("Origin")
        xmlOpen("Member")
        memberAccessExpr.member.accept(this)
        xmlClose("Member")
        xmlClose("MemberAccessExpr")
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
        when(node)
        {
            is RootASTNode -> visitRootASTNode(node)
            else           -> node.accept(this)
        }
        val res = builder.toString()
        builder.clear()
        return res
    }
}