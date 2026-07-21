package net.exoad.kira.compiler.backend.codegen.c

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.KiraCodeGenerator
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import java.io.File

class KiraCCodeGenerator(override val compilationUnit: CompilationUnit) : KiraCodeGenerator(compilationUnit) {
    companion object {
        const val TEMPLATE_FILE = "c_generator.c"
        private lateinit var templateFileContents: String
        const val KIRA_LIB_MODULE = "kira:lib"
        private var scopedModuleName = "kira_lib"

        fun pushScopedModuleName(moduleDecl: ModuleDecl) {
            scopedModuleName = "${moduleDecl.getPackageName()}_${moduleDecl.getModuleName()}"
        }

        fun peekScopedModuleName(): String {
            return scopedModuleName
        }

        fun fetchTemplateFileContents(): String {
            if (!::templateFileContents.isInitialized) {
                val resource = Public::class.java.getResource("/$TEMPLATE_FILE")
                    ?: Public::class.java.getResource(TEMPLATE_FILE)

                templateFileContents = resource?.readText()
                    ?: File("src/main/resources/$TEMPLATE_FILE").readText()
            }
            return templateFileContents
        }
    }

    fun generate() {
        val file = File("out.kira.c")
        if (file.exists()) {
            file.delete()
            file.createNewFile()
        }
        compilationUnit.allSources().forEach {
            visitRootASTNode(it.ast)
            file.appendText(this@KiraCCodeGenerator.toString())
            clean()
        }
    }

    private val buffer = StringBuilder()
    private val requiredIncludes = linkedSetOf<String>()
    private val discoveredMagicTypes by lazy {
        compilationUnit.collectIntrinsicMarkedTypeNames("_magic") + compilationUnit.allMagicTypes()
    }
    private var indentLevel = 0

    private fun appendIndented(value: String) {
        repeat(indentLevel) { buffer.append("    ") }
        buffer.append(value)
    }

    private fun appendIndentedLine(value: String = "") {
        appendIndented(value)
        buffer.appendLine()
    }

    private fun binaryOpSymbol(op: BinaryOp): String {
        return op.symbol.joinToString(separator = "") { it.rep.toString() }
    }

    private fun functionLikeName(expr: Expr): String {
        return when (expr) {
            is Identifier -> expr.value
            is IntrinsicExpr -> expr.intrinsicKey.name
            else -> "_anon"
        }
    }

    private fun mapIntrinsicName(rawName: String): String {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        return CIntrinsicsTable.resolveFunction(canonical)
    }

    private fun includeForIntrinsic(rawName: String) {
        val canonical = rawName.removePrefix("@").trim('_').lowercase()
        requiredIncludes.addAll(CIntrinsicsTable.resolveIncludes(canonical))
    }

    private fun mapTypeName(typeName: String): String {
        if (discoveredMagicTypes.contains(typeName)) {
            val mapping = CMagicTypeLowering.resolve(typeName)
            if (mapping != null) {
                requiredIncludes.addAll(mapping.requiredIncludes)
            }
            return when (typeName) {
                "Int8", "Int16", "Int32", "Bool" -> "KInt"
                "Int64" -> "KLong"
                "Float32" -> "KFloat"
                "Float64" -> "KDouble"
                "Void", "Never" -> "KVoid"
                "Str", "String" -> "const char*"
                else -> typeName
            }
        }
        return when (typeName) {
            "Int8", "Int16", "Int32", "Bool" -> "KInt"
            "Int64" -> "KLong"
            "Float32" -> "KFloat"
            "Float64" -> "KDouble"
            "Void" -> "KVoid"
            "Str", "String" -> "const char*"
            else -> typeName
        }
    }

    override fun visitRootASTNode(node: RootASTNode) {
        requiredIncludes.clear()
        buffer.appendLine(fetchTemplateFileContents())
        buffer.appendLine()
        node.statements.forEach { it.accept(this) }

        if (requiredIncludes.isNotEmpty()) {
            val withHeaders = buildString {
                requiredIncludes.forEach { appendLine("#include <$it>") }
                appendLine()
                append(buffer)
            }
            buffer.clear()
            buffer.append(withHeaders)
        }
    }

    fun clean() {
        buffer.clear()
    }

    override fun toString(): String {
        return buffer.toString()
    }

    override fun visitStatement(statement: Statement) {
        if (statement.expr is NoExpr) {
            return
        }
        // Control-flow nodes (if/while/for/...) subclass Statement and override accept(),
        // so they never arrive here as statement.expr. Only Expr-shaped payloads do.
        val isBlockLike = when (statement.expr) {
            is ClassDecl,
            is FunctionDecl,
            is ModuleDecl,
            is EnumDecl,
            is TraitDecl,
            is VariantDecl,
            is TypeAliasDecl,
            is TryExpr -> true

            else -> false
        }

        if (!isBlockLike) {
            appendIndented("")
        }

        statement.expr.accept(this)

        if (isBlockLike) {
            if (buffer.isNotEmpty() && buffer.last() != '\n') buffer.appendLine()
            return
        }

        buffer.appendLine(";")
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {
        appendIndented("if (")
        ifSelectionStatement.expr.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        ifSelectionStatement.thenStatements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
        ifSelectionStatement.elseBranches.forEach { branch ->
            appendIndented("")
            branch.accept(this)
        }
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {
        buffer.append("else if (")
        ifElseIfBranchNode.condition.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        ifElseIfBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        buffer.appendLine("else {")
        indentLevel++
        elseBranchNode.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        appendIndented("while (")
        whileIterationStatement.condition.accept(this)
        buffer.appendLine(") {")
        indentLevel++
        whileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        appendIndentedLine("do {")
        indentLevel++
        doWhileIterationStatement.statements.forEach { it.accept(this) }
        indentLevel--
        appendIndented("} while (")
        doWhileIterationStatement.condition.accept(this)
        buffer.appendLine(");")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement) {
        appendIndented("return")
        if (returnStatement.expr !is NoExpr) {
            buffer.append(" ")
            returnStatement.expr.accept(this)
        }
        buffer.appendLine(";")
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement) {
        val iterExpr = forIterationStatement.forIterationExpr
        if (iterExpr.target is RangeExpr) {
            appendIndented("for (KInt ")
            buffer.append(iterExpr.initializer.value)
            buffer.append(" = ")
            iterExpr.target.begin.accept(this)
            buffer.append("; ")
            buffer.append(iterExpr.initializer.value)
            buffer.append(" <= ")
            iterExpr.target.end.accept(this)
            buffer.append("; ++")
            buffer.append(iterExpr.initializer.value)
            buffer.appendLine(") {")
            indentLevel++
            forIterationStatement.body.forEach { it.accept(this) }
            indentLevel--
            appendIndentedLine("}")
            return
        }

        appendIndentedLine("/* unsupported for-target lowering, emitting loop stub */")
        appendIndentedLine("for (;;) {")
        indentLevel++
        forIterationStatement.body.forEach { it.accept(this) }
        appendIndentedLine("break;")
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitUseStatement(useStatement: UseStatement) {
        appendIndentedLine("/* use ${useStatement.uri.value} */")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement) {
        appendIndentedLine("break;")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement) {
        appendIndentedLine("continue;")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {
        buffer.append("(")
        binaryExpr.leftExpr.accept(this)
        buffer.append(" ${binaryOpSymbol(binaryExpr.operator)} ")
        binaryExpr.rightExpr.accept(this)
        buffer.append(")")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {
        buffer.append(unaryExpr.operator.symbol.rep)
        unaryExpr.operand.accept(this)
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {
        assignmentExpr.target.accept(this)
        buffer.append(" = ")
        assignmentExpr.value.accept(this)
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        includeForIntrinsic(functionLikeName(functionCallExpr.name))
        val functionName = mapIntrinsicName(functionLikeName(functionCallExpr.name))
        buffer.append(functionName)
        buffer.append("(")

        var hasAny = false
        functionCallExpr.positionalParameters.forEachIndexed { index, parameter ->
            if (index > 0) buffer.append(", ")
            parameter.value.accept(this)
            hasAny = true
        }
        functionCallExpr.namedParameters.forEach { parameter ->
            if (hasAny) buffer.append(", ")
            parameter.value.accept(this)
            hasAny = true
        }
        buffer.append(")")
    }

    override fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {
        includeForIntrinsic(intrinsicExpr.intrinsicKey.name)
        val mappedName = mapIntrinsicName(intrinsicExpr.intrinsicKey.name)
        if (intrinsicExpr.parameters == null) {
            buffer.append(mappedName)
            return
        }
        buffer.append(mappedName)
        buffer.append("(")
        intrinsicExpr.parameters.forEachIndexed { idx, expr ->
            if (idx > 0) buffer.append(", ")
            expr.accept(this)
        }
        buffer.append(")")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {
        compoundAssignmentExpr.left.accept(this)
        buffer.append(" ${binaryOpSymbol(compoundAssignmentExpr.operator)}= ")
        compoundAssignmentExpr.right.accept(this)
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {
        functionDeclParameterExpr.typeSpecifier.accept(this)
        buffer.append(" ")
        functionDeclParameterExpr.name.accept(this)
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {
        memberAccessExpr.origin.accept(this)
        buffer.append(".")
        memberAccessExpr.member.accept(this)
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {
        buffer.append("/* for ")
        buffer.append(forIterationExpr.initializer.value)
        buffer.append(" in ")
        forIterationExpr.target.accept(this)
        buffer.append(" */")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr) {
        buffer.append("(")
        rangeExpr.begin.accept(this)
        buffer.append(" .. ")
        rangeExpr.end.accept(this)
        buffer.append(")")
    }

    override fun visitArrayIndexExpr(arrayIndexExpr: net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr) {
        arrayIndexExpr.originExpr.accept(this)
        buffer.append("[")
        arrayIndexExpr.indexExpr.accept(this)
        buffer.append("]")
    }

    override fun visitThrowExpr(throwExpr: ThrowExpr) {
        requiredIncludes.add("stdlib.h")
        buffer.append("/* throw lowered to abort() in C backend */ abort()")
    }

    override fun visitTryExpr(tryExpr: TryExpr) {
        appendIndentedLine("/* try/on not natively supported in C, lowering try-block only */")
        tryExpr.tryBlock.forEach { it.accept(this) }
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr) {
        enumMemberExpr.name.accept(this)
        if (enumMemberExpr.value != null) {
            buffer.append(" = ")
            enumMemberExpr.value.accept(this)
        }
    }

    override fun visitObjectInitExpr(objectInitExpr: net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr) {
        // Simplest handling: generate a call-like representation for now.
        objectInitExpr.typeName.accept(this)
        buffer.append(" {")
        objectInitExpr.positionalArgs.forEachIndexed { i, arg ->
            if (i > 0) buffer.append(", ")
            arg.accept(this)
        }
        buffer.append(" }")
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {
        buffer.append("/* type-check */(")
        typeCheckExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {
        buffer.append("((")
        typeCastExpr.type.accept(this)
        buffer.append(")")
        typeCastExpr.value.accept(this)
        buffer.append(")")
    }

    override fun visitNoExpr(noExpr: NoExpr) {
        // no-op
    }

    override fun visitWithExpr(withExpr: WithExpr) {
        buffer.append("{ ")
        withExpr.members.forEachIndexed { idx, member ->
            if (idx > 0) buffer.append(", ")
            member.accept(this)
        }
        buffer.append(" }")
    }

    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {
        functionCallNamedParameterExpr.value.accept(this)
    }

    override fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr) {
        functionCallPositionalParameterExpr.value.accept(this)
    }

    override fun visitWithExprMember(withExprMember: WithExprMember) {
        buffer.append(".")
        withExprMember.name.accept(this)
        buffer.append(" = ")
        withExprMember.value.accept(this)
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {
        buffer.append(integerLiteral.value)
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral) {
        buffer.append("\"${stringLiteral.value}\"")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {
        buffer.append(floatLiteral.value)
    }

    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {
        buffer.append("/* function literal */")
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral) {
        buffer.append("{")
        arrayLiteral.value.forEachIndexed { i, expr ->
            if (i > 0) buffer.append(", ")
            expr.accept(this)
        }
        buffer.append("}")
    }


    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        buffer.append("NULL")
    }

    override fun visitType(type: Type) {
        val typeName = when (val identifier = type.identifier) {
            is Identifier -> identifier.value
            is IntrinsicExpr -> identifier.intrinsicKey.name
            else -> "Any"
        }
        buffer.append(mapTypeName(typeName))
    }

    override fun visitIdentifier(identifier: Identifier) {
        buffer.append(identifier.value)
    }

    override fun visitVariableDecl(variableDecl: VariableDecl) {
        variableDecl.type.accept(this)
        buffer.append(" ")
        variableDecl.name.accept(this)
        if (variableDecl.value != null) {
            buffer.append(" = ")
            variableDecl.value!!.accept(this)
        }
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl) {
        appendIndented("")
        val functionName = functionLikeName(functionDecl.name)
        val returnsVoid =
            (functionDecl.def.returnTypeSpecifier.identifier as? Identifier)?.value == "Void"
        if (functionName == "main" && returnsVoid) {
            buffer.append("int")
        } else {
            functionDecl.def.returnTypeSpecifier.accept(this)
        }
        buffer.append(" ")
        buffer.append(functionName)
        buffer.append("(")
        functionDecl.def.parameters.forEachIndexed { idx, parameter ->
            if (idx > 0) buffer.append(", ")
            parameter.accept(this)
        }
        buffer.append(")")
        if (functionDecl.def.body == null) {
            buffer.appendLine(";")
            return
        }
        buffer.appendLine(" {")
        indentLevel++
        functionDecl.def.body!!.forEach { it.accept(this) }
        indentLevel--
        appendIndentedLine("}")
    }

    override fun visitClassDecl(classDecl: ClassDecl) {
        buffer.append("typedef struct \n{\n")
        if (classDecl.members.isNotEmpty()) {
            classDecl.members.forEach { it.accept(this) }
        } else {
            buffer.append("char _/*empty_field*/[0];")
        }
        val className = when (val id = classDecl.name.identifier) {
            is Identifier -> id.value
            else -> id.toString()
        }
        buffer.append("\n} $className")
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl) {
        appendIndentedLine(
            "/*--BEGIN_MODULE: '${moduleDecl.uri.value}'--*/"
        )
        pushScopedModuleName(moduleDecl)
    }

    override fun visitEnumDecl(enumDecl: EnumDecl) {
        appendIndented("typedef enum ${enumDecl.name.value} {")
        if (enumDecl.members.isNotEmpty()) {
            buffer.appendLine()
            indentLevel++
            enumDecl.members.forEachIndexed { index, member ->
                appendIndented("")
                member.accept(this)
                if (index < enumDecl.members.size - 1) buffer.append(",")
                buffer.appendLine()
            }
            indentLevel--
            appendIndentedLine("} ${enumDecl.name.value};")
            return
        }
        buffer.appendLine("} ${enumDecl.name.value};")
    }

    override fun visitTraitDecl(traitDecl: TraitDecl) {
        appendIndentedLine("/* trait ${traitDecl.name} has no direct C lowering in baseline backend */")
    }

    override fun visitVariantDecl(variantDecl: VariantDecl) {
        appendIndentedLine("/* variant ${variantDecl.name} lowered as comments in baseline backend */")
    }

    override fun visitTypeAliasDecl(typeAliasDecl: TypeAliasDecl) {
        appendIndented("typedef ")
        typeAliasDecl.target.accept(this)
        buffer.append(" ")
        typeAliasDecl.alias.accept(this)
        buffer.appendLine(";")
    }
}