package net.exoad.kira.compiler.backend.codegen.c

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.KiraCodeGenerator
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
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
            scopedModuleName = "${moduleDecl.getName()}_${moduleDecl.getName()}"
        }

        fun peekScopedModuleName(): String {
            return scopedModuleName
        }

        fun fetchTemplateFileContents(): String {
            if (!::templateFileContents.isInitialized) {
                Public.javaClass.getResource(TEMPLATE_FILE)?.let {
                    templateFileContents = it.readText()
                }
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

    override fun visitRootASTNode(node: RootASTNode) {
        buffer.appendLine(fetchTemplateFileContents())
        node.statements.forEach { it.accept(this) }
    }

    fun clean() {
        buffer.clear()
    }

    override fun toString(): String {
        return buffer.toString()
    }

    override fun visitStatement(statement: Statement) {
        statement.expr.accept(this)
        buffer.appendLine(";")
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {
        TODO("Not yet implemented")
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {
        TODO("Not yet implemented")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        TODO("Not yet implemented")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        TODO("Not yet implemented")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        TODO("Not yet implemented")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement) {
        TODO("Not yet implemented")
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement) {
        TODO("Not yet implemented")
    }

    override fun visitUseStatement(useStatement: UseStatement) {
        TODO("Not yet implemented")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement) {
        TODO("Not yet implemented")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement) {
        TODO("Not yet implemented")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {
        TODO("Not yet implemented")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {
        TODO("Not yet implemented")
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        TODO("Not yet implemented")
    }

    override fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {
        TODO("Not yet implemented")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {
        TODO("Not yet implemented")
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {
        TODO("Not yet implemented")
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {
        TODO("Not yet implemented")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr) {
        TODO("Not yet implemented")
    }

    override fun visitArrayIndexExpr(arrayIndexExpr: net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr) {
        arrayIndexExpr.originExpr.accept(this)
        buffer.append("[")
        arrayIndexExpr.indexExpr.accept(this)
        buffer.append("]")
    }

    override fun visitThrowExpr(throwExpr: ThrowExpr) {
        TODO("Not yet implemented")
    }

    override fun visitTryExpr(tryExpr: TryExpr) {
        TODO("Not yet implemented")
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr) {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {
        TODO("Not yet implemented")
    }

    override fun visitNoExpr(noExpr: NoExpr) {
        TODO("Not yet implemented")
    }

    override fun visitWithExpr(withExpr: WithExpr) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr) {
        TODO("Not yet implemented")
    }

    override fun visitWithExprMember(withExprMember: WithExprMember) {
        TODO("Not yet implemented")
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {
        buffer.append(integerLiteral.value)
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral) {
        buffer.append("\"${stringLiteral.value}\"")
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral) {
        buffer.append(if (boolLiteral.value) "1" else "0")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {
        buffer.append(floatLiteral.value)
    }

    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {
        TODO("Not yet implemented")
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral) {
        TODO("Not yet implemented")
    }

    override fun visitListLiteral(listLiteral: ListLiteral) {
        TODO("Not yet implemented")
    }

    override fun visitMapLiteral(mapLiteral: MapLiteral) {
        TODO("Not yet implemented")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        TODO("Not yet implemented")
    }

    override fun visitType(type: Type) {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun visitClassDecl(classDecl: ClassDecl) {
        buffer.append("typedef struct \n{\n")
        if (classDecl.members.isNotEmpty()) {
            classDecl.members.forEach { it.accept(this) }
        } else {
            buffer.append("char _/*empty_field*/[0];")
        }
        buffer.append("\n} ${classDecl.name.identifier}")
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl) {
        buffer.append(
            "/*--BEGIN_MODULE: '${moduleDecl.uri.value}'--*/"
        )
        pushScopedModuleName(moduleDecl)
    }

    override fun visitEnumDecl(enumDecl: EnumDecl) {
        TODO("Not yet implemented")
    }

    override fun visitTraitDecl(traitDecl: TraitDecl) {
        TODO("Not yet implemented")
    }
}