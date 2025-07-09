package net.exoad.kira.utils

import net.exoad.kira.Builtin
import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.RootASTNode
import net.exoad.kira.compiler.front.elements.*
import net.exoad.kira.compiler.front.exprs.*
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.EnumDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionDecl
import net.exoad.kira.compiler.front.exprs.decl.ModuleDecl
import net.exoad.kira.compiler.front.exprs.decl.ObjectDecl
import net.exoad.kira.compiler.front.exprs.decl.VariableDecl
import net.exoad.kira.compiler.front.statements.*
import java.text.SimpleDateFormat

/**
 * Builds a cool looking xml of the ast without external dependencies (can only write, not read tho)
 *
 * This might be an ir ir, meaning you could use this make other languages. but thats just the ast!
 *
 * usually this is just generated right after the parser phase, but you can always pass in a root ast node
 * or something, and it will traverse and spit something out.
 *
 * it is dumped using [net.exoad.kira.ArgsOptions.dumpAST] to a file you want.
 *
 * spitting it out makes developing kira much easier than scrolling through a terminal sometimes
 */
object XMLASTVisitor :
    ASTVisitor() // having to come back here to implement members from the ASTVisitor is just idk, is it too much boilerplate?
// antlr and stuffs already pregenerate the code and all of those "visit" functions for you which is helpful lol
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

    // i love kotlin's trailing lambda features as compared to other languages where you have to supply a lambda within a function
    //
    // todo: can we have the ^ above feature as an actual feature in kira?!
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

    /**
     * A leaf node in xml that has an ending tag because you need [value] or the content and
     * potentially attributes with [attrs]
     */
    private fun xmlLeaf(tag: String, value: String, attrs: String = "")
    {
        appendLine("<$tag${if(attrs.isNotEmpty()) " $attrs" else ""}>${escapeXml(value)}</$tag>")
    }

    /**
     * A leaf node in xml that has no ending tag, and you only want to specify attributes with [attrs]
     */
    private fun xmlSingleLeaf(tag: String, attrs: String?)
    {
        appendLine("<$tag${if(attrs != null) " $attrs" else ""}/>")
    }

    private fun escapeXml(s: String): String
    {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

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

    override fun visitUseStatement(useStatement: UseStatement)
    {
        node("UseStatement")
        {
            node("URI")
            {
                useStatement.uri.accept(this)
            }
        }
    }

    override fun visitBreakStatement(breakStatement: BreakStatement)
    {
        xmlSingleLeaf("BreakStatement", null)
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement)
    {
        xmlSingleLeaf("ContinueStatement", null)
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

    override fun visitFunctionLiteral(functionLiteral: AnonymousFunction)
    {
        node("LFunc")
        {
            functionLiteral.returnTypeSpecifier.accept(this)
            node("Parameters")
            {
                functionLiteral.parameters.forEach { it.accept(this) }
            }
            // todo: this is a bandage situation where function type notation is actually not supported. it makes parsing stubs as types much harder
            // todo: either come up with a complete new system for function type or reuse the already existing one for function literal declarations
            if(functionLiteral.body != null)
            {
                node("Body")
                {
                    functionLiteral.body!!.forEach { it.accept(this) }
                }
            }
        }
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral)
    {
        node("LArray")
        {
            arrayLiteral.value.forEach { it.accept(this) }
        }
    }

    override fun visitListLiteral(listLiteral: ListLiteral)
    {
        node("LList")
        {
            listLiteral.value.forEach { it.accept(this) }
        }
    }

    override fun visitMapLiteral(mapLiteral: MapLiteral)
    {
        node("LMap")
        {
            mapLiteral.value.forEach {
                node("Entry")
                {
                    node("Key")
                    {
                        it.key.accept(this)
                    }
                    node("Value")
                    {
                        it.value.accept(this)
                    }
                }
            }
        }
    }

    override fun visitIdentifier(identifier: Identifier)
    {
        when(identifier)
        {
            is AnonymousIdentifier -> xmlSingleLeaf("Anonymous", "")
            else                   -> xmlLeaf("Identifier", identifier.name)
        }
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

    override fun visitVariableDecl(variableDecl: VariableDecl)
    {
        node(
            "VariableDecl", when(variableDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${variableDecl.modifiers.joinToString(",") { it.name }}""""
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

    override fun visitFunctionDecl(functionDecl: FunctionDecl)
    {
        node(
            "FunctionDecl", "${
                when(functionDecl.modifiers.isNotEmpty())
                {
                    true -> buildString {
                        append("modifiers=\"")
                        append(functionDecl.modifiers.joinToString(",") { it.name })
                        append("\" ")
                    }
                    else -> ""
                }
            }stub=\"${functionDecl.isStub()}\""
        )
        {
            functionDecl.name.accept(this)
            functionDecl.value.accept(this)
        }
    }

    override fun visitClassDecl(classDecl: ClassDecl)
    {
        node(
            "ClassDecl", when(classDecl.modifiers.isNotEmpty())
            {
                true -> """modifiers="${classDecl.modifiers.joinToString(",") { it.name }}""""
                else -> ""
            }
        )
        {
            classDecl.name.accept(this)
            if(classDecl.parent != null)
            {
                node("Parent")
                {
                    classDecl.parent.accept(this)
                }
            }
            node("Members")
            {
                classDecl.members.forEach { it.accept(this) }
            }
        }
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

    override fun visitObjectDecl(objectDecl: ObjectDecl)
    {
        node(
            "ObjectDecl",
            """modifiers="${objectDecl.modifiers.joinToString(",") { it.name }}""""
        )
        {
            objectDecl.name.accept(this)
            node("Members")
            {
                objectDecl.members.forEach { it.accept(this) }
            }
        }
    }

    override fun visitEnumDecl(enumDecl: EnumDecl)
    {
        node(
            "EnumDecl", """modifiers="${
                enumDecl.modifiers.joinToString(",") { it.name }
            }""""
        )
        {
            enumDecl.name.accept(this)
            enumDecl.members.forEach { it.accept(this) }
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
            "IntrinsicCallExpr", """ name ="${
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
        node("CompoundAssignmentExpr", """ op ="${escapeXml(compoundAssignmentExpr.operator.toString())}"""")
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
                true -> """ modifiers ="${functionParameterExpr.modifiers.joinToString(", ") { it.name }}""""
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

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr)
    {
        node("EnumMemberExpr", """ name ="${enumMemberExpr.name.name}"""")
        {
            enumMemberExpr.value?.accept(this)
        }
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr)
    {
        node("TypeCheckExpr")
        {
            node("Expr")
            {
                typeCheckExpr.value.accept(this)
            }
            node("TargetType")
            {
                typeCheckExpr.type.accept(this)
            }
        }
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr)
    {
        node("TypeCastExpr")
        {
            node("Expr")
            {
                typeCastExpr.value.accept(this)
            }
            node("TargetType")
            {
                typeCastExpr.type.accept(this)
            }
        }
    }

    override fun visitNoExpr(noExpr: NoExpr)
    {
        xmlSingleLeaf("NoExpr", null)
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