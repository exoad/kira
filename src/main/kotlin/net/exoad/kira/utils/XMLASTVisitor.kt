package net.exoad.kira.utils

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.RootASTNode
import net.exoad.kira.compiler.front.elements.*
import net.exoad.kira.compiler.front.exprs.*
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionFirstClassDecl
import net.exoad.kira.compiler.front.exprs.decl.ModuleDecl
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

    private fun node(tag: String, attrs: String = "", children: () -> Unit)
    {
        xmlOpen(tag, attrs)
        children()
        xmlClose(tag)
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
        node("KiraProgram")
        {
            rootASTNode.statements.forEach { it.accept(this) }
        }
    }

    override fun visitStatement(statement: Statement)
    {
        node("Statement")
        {
            statement.expr.accept(this)
        }
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    {
        node("IfSelectionStatement")
        {
            node("Condition")
            {
                ifSelectionStatement.expr.accept(this)
            }
            node("Then")
            {
                ifSelectionStatement.thenStatements.forEach { it.accept(this) }
            }
            node("Branches")
            {
                ifSelectionStatement.elseBranches.forEach { it.accept(this) }
            }
        }
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    {
        node("ElseIfBranch")
        {
            node("Condition")
            {
                ifElseIfBranchNode.condition.accept(this)
            }
            node("Body")
            {
                ifElseIfBranchNode.statements.forEach { it.accept(this) }
            }
        }
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    {
        node("ElseBranch")
        {
            node("Body")
            {
                elseBranchNode.statements.forEach { it.accept(this) }
            }
        }
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    {
        node("WhileIterationStatement")
        {
            node("Condition")
            {
                whileIterationStatement.condition.accept(this)
            }
            node("Body")
            {
                whileIterationStatement.statements.forEach { it.accept(this) }
            }
        }
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    {
        node("DoWhileIterationStatement")
        {
            node("Condition")
            {
                doWhileIterationStatement.condition.accept(this)
            }
            node("Body")
            {
                doWhileIterationStatement.statements.forEach { it.accept(this) }
            }
        }
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement)
    {
        node("ReturnStatement")
        {
            returnStatement.expr.accept(this)
        }
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement)
    {
        node("ForIterationStatement")
        {
            forIterationStatement.expr.accept(this)
            node("Body")
            {
                forIterationStatement.body.forEach { it.accept(this) }
            }
        }
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr)
    {
        node("BinaryExpr", """op="${escapeXml(binaryExpr.operator.toString())}"""")
        {
            node("Left")
            {
                binaryExpr.leftExpr.accept(this)
            }
            node("Right")
            {
                binaryExpr.rightExpr.accept(this)
            }
        }
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr)
    {
        node("UnaryExpr", """op="${escapeXml(unaryExpr.operator.toString())}"""")
        {
            unaryExpr.operand.accept(this)
        }
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

    override fun visitTypeSpecifier(typeSpecifier: TypeSpecifier)
    {
        if(typeSpecifier.childGenericTypeSpecifier.isNotEmpty())
        {
            node("Type", """name="${typeSpecifier.name}"""")
            {
                typeSpecifier.childGenericTypeSpecifier.forEach { it.accept(this) }
            }
        }
        else
        {
            xmlLeaf("Type", typeSpecifier.name)
        }
    }

    override fun visitVariableDecl(variableDecl: VariableFirstClassDecl)
    {
        node(
            "VariableDecl", when(variableDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${variableDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        {
            variableDecl.name.accept(this)
            variableDecl.typeSpecifier.accept(this)
            if(variableDecl.value != null)
            {
                node("Value")
                {
                    variableDecl.value!!.accept(this)
                }
            }
        }
    }

    override fun visitFunctionDecl(functionDecl: FunctionFirstClassDecl)
    {
        node(
            "FunctionDecl", when(functionDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${functionDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        {
            functionDecl.name.accept(this)
            functionDecl.returnTypeSpecifier.accept(this)
            node("Parameters")
            {
                functionDecl.parameters.forEach { it.accept(this) }
            }
            node("Body")
            {
                functionDecl.body?.forEach { it.accept(this) }
            }
        }
    }

    override fun visitClassDecl(classDecl: ClassDecl)
    {
        node(
            "ClassDecl", when(classDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${classDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        {
            classDecl.name.accept(this)
            node("Members")
            {
                classDecl.members.forEach { it.accept(this) }
            }
        }
        xmlOpen(
            "ClassDecl", when(classDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${classDecl.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl)
    {
        node("ModuleDecl")
        {
            node("URI")
            {
                moduleDecl.uri.accept(this)
            }
        }
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr)
    {
        node("AssignmentExpr")
        {
            assignmentExpr.target.accept(this)
            assignmentExpr.value.accept(this)
        }
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr)
    {
        node("FunctionCallExpr")
        {
            functionCallExpr.name.accept(this)
            node("Parameters")
            {
                functionCallExpr.parameters.forEach { it.accept(this) }
            }
        }
    }

    override fun visitIntrinsicCallExpr(intrinsicCallExpr: IntrinsicCallExpr)
    {

        node(
            "IntrinsicCallExpr", """name="${
                Builtin.Intrinsics.entries.find { it.name == intrinsicCallExpr.name.intrinsicKey.name }?.name
                    ?: intrinsicCallExpr.name.intrinsicKey.name
            }""""
        ) {
            node("Parameters")
            {
                intrinsicCallExpr.parameters.forEach { it.accept(this) }
            }
        }
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr)
    {
        node("CompoundAssignmentExpr", """op="${escapeXml(compoundAssignmentExpr.operator.toString())}"""")
        {
            node("LValue")
            {
                compoundAssignmentExpr.left.accept(this)
            }
            node("RValue")
            {
                compoundAssignmentExpr.right.accept(this)
            }
        }
    }

    override fun visitFunctionParameterExpr(functionParameterExpr: FunctionParameterExpr)
    {
        node(
            "FunctionParameterExpr", when(functionParameterExpr.modifiers.isNotEmpty())
            {
                true -> """modifiers="${functionParameterExpr.modifiers.joinToString(", ") { it.name }}""""
                else -> ""
            }
        )
        {
            functionParameterExpr.name.accept(this)
            functionParameterExpr.typeSpecifier.accept(this)
        }
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr)
    {
        node("MemberAccessExpr")
        {
            node("Origin")
            {
                memberAccessExpr.origin.accept(this)
            }
            node("Member")
            {
                memberAccessExpr.member.accept(this)
            }
        }
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr)
    {
        node("ForIterationExpr")
        {
            node("Initializer")
            {
                forIterationExpr.initializer.accept(this)
            }
            node("Target")
            {
                forIterationExpr.target.accept(this)
            }
        }
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr)
    {
        node("RangeExpr")
        {
            node("Begin")
            {
                rangeExpr.begin.accept(this)
            }
            node("End")
            {
                rangeExpr.end.accept(this)
            }
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