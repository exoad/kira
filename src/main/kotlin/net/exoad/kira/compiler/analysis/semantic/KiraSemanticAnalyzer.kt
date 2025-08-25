package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.*
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.BoolLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FunctionLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.ListLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.MapLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.Symbols
import net.exoad.kira.source.AbsoluteFileLocation
import net.exoad.kira.source.FileLocation
import net.exoad.kira.source.SourceContext

/**
 * The 4th phase after the parsing process that traverses the generated AST by the [net.exoad.kira.compiler.frontend.parser.KiraParser]
 * to make sure everything follows the rules of the language and everything makes sense.
 */
class KiraSemanticAnalyzer(private val compilationUnit: CompilationUnit) : ASTVisitor()
{
    private val diagnosticsPump = mutableListOf<DiagnosticsException>()
    lateinit var context: SourceContext

    fun validateAST(): SemanticAnalyzerResults
    {
        try
        {
            for(source in compilationUnit.allSources())
            {
                context = source
                source.ast.statements.forEach { it.accept(this) }
            }
        }
        catch(_: Exception)
        {
            return SemanticAnalyzerResults(diagnosticsPump, compilationUnit.symbolTable, false)
        }
        return SemanticAnalyzerResults(diagnosticsPump, compilationUnit.symbolTable, diagnosticsPump.isEmpty())
    }

    private fun pump(message: String, location: FileLocation, selectorLength: Int = 1, help: String = "")
    {
        diagnosticsPump.add(
            Diagnostics.recordPanic(
                "",
                if(help.isEmpty()) message else "$message\n\nHelp: $help",
                location = location,
                selectorLength = selectorLength,
                context = context
            )
        )
    }

    fun pumpOnTrue(expr: Boolean, message: String, location: FileLocation?, selectorLength: Int = 1, help: String = "")
    {
        if(expr)
        {
            pump(message, location ?: FileLocation.Companion.UNKNOWN, selectorLength, help)
        }
    }

    fun expectSymbol(symbolName: String, symbolKind: SemanticSymbolKind)
    {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        pumpOnTrue(
            res == null || res.kind != symbolKind, "Expected a $symbolKind for '$symbolName', but got '$res'",
            location = res?.declaredAt?.toRelative() ?: FileLocation.Companion.UNKNOWN,
            selectorLength = res!!.name.length
        )
    }

    fun expectDeclared(
        symbolName: String,
        location: FileLocation?,
        helpMessage: String = "'$symbolName' is not available at this scope. Or it has not been declared.",
    )
    {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if(res == null)
        {
            pump(
                "'${symbolName}' is an unknown symbol here.",
                location = location ?: FileLocation.Companion.UNKNOWN,
                selectorLength = symbolName.length,
                help = helpMessage
            )
        }
    }

    fun expectNotDeclared(
        symbolName: String,
        location: FileLocation?,
        helpMessage: String = "Shadowing is not allowed. Rename this or the previous declaration.",
    )
    {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if(res != null)
        {
            pump(
                "'${symbolName}' was already declared at ${res.declaredAt}",
                location = location ?: FileLocation.Companion.UNKNOWN,
                selectorLength = symbolName.length,
                help = helpMessage
            )
        }
    }

    fun expectType(symbolName: String, typeName: String)
    {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if(res == null || res.kind != SemanticSymbolKind.TYPE_SPECIFIER || res.name == typeName)
        {
            pump(
                "Expected a $typeName for $symbolName, but got '$res'",
                location = res?.declaredAt?.toRelative() ?: FileLocation.Companion.UNKNOWN
            )
        }
    }

    override fun visitStatement(statement: Statement)
    {
        statement.expr.accept(this)
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitUseStatement(useStatement: UseStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement)
    {
        // TODO("Not yet implemented")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitIntrinsicCallExpr(intrinsicCallExpr: IntrinsicCallExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitNoExpr(noExpr: NoExpr)
    {
        // ignore
    }

    override fun visitWithExpr(withExpr: WithExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr)
    {
        // TODO("Not yet implemented")
    }

    override fun visitWithExprMember(withExprMember: WithExprMember)
    {
        // TODO("Not yet implemented")
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral)
    {
        // should be true
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral)
    {
        // should be true
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral)
    {
        // should be true
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral)
    {
        // should be true
    }

    override fun visitFunctionLiteral(functionLiteral: FunctionLiteral)
    {
        // should be true
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitListLiteral(listLiteral: ListLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitMapLiteral(mapLiteral: MapLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitIdentifier(identifier: Identifier)
    {
        expectNotDeclared(identifier.name, context.astOrigins[identifier])
        compilationUnit.symbolTable.declare(
            identifier.name, SemanticSymbol(
                name = identifier.name,
                kind = SemanticSymbolKind.VARIABLE,
                type = Token.Type.IDENTIFIER,
                declaredAt = AbsoluteFileLocation.Companion.fromRelative(
                    context.astOrigins[identifier] ?: FileLocation.Companion.UNKNOWN, context.file
                )
            )
        )
    }

    override fun visitTypeSpecifier(typeSpecifier: TypeSpecifier)
    {
    }

    /**
     * Used for [visitVariableDecl] which uses this map to find all of the literal types and how they can match
     */
    private val variableBuiltinPrimitives = mapOf(
        StringLiteral::class to { type: String -> type == "String" },
        IntegerLiteral::class to { type: String -> type == "Int32" || type == "Int64" },
        FloatLiteral::class to { type: String -> type == "Float32" || type == "Float64" },
        BoolLiteral::class to { type: String -> type == "Bool" },
    )

    override fun visitVariableDecl(variableDecl: VariableDecl)
    {
        variableDecl.name.accept(this)
        if(compilationUnit.symbolTable.resolve(variableDecl.typeSpecifier.name) == null)
        {
            pump(
                "The type '${variableDecl.typeSpecifier.name}' was not found at this scope (${compilationUnit.symbolTable.peekScope().name.lowercase()})",
                location = context.astOrigins[variableDecl.typeSpecifier] ?: FileLocation.Companion.UNKNOWN,
                selectorLength = variableDecl.typeSpecifier.name.length
            )
        }
        variableDecl.typeSpecifier.accept(this)
        // check if the value of the variable matches the type
        //
        // we need to check for a multitude of conditions
        // 1. if it's a raw literal or object value, check if just the type names match
        // 2. if it's a function call, check the return type of that function type
        // (there are more edge cases, but i am not too sure
        if(variableDecl.value != null)
        {
            variableDecl.value!!.accept(this)
            val typeName = variableDecl.typeSpecifier.name
            val literalClass = variableDecl.value!!::class
            pumpOnTrue(
                !(variableBuiltinPrimitives[literalClass]?.invoke(typeName) ?: true),
                "Type mismatch. Got a ${
                    literalClass.simpleName?.removeSuffix("Literal")?.lowercase()
                } literal, but expected a '$typeName'",
                context.astOrigins[variableDecl.value],
                help = "Correct the declaration type or the value itself.",
            )
        }
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl)
    {
    }

    override fun visitClassDecl(classDecl: ClassDecl)
    {
        expectNotDeclared(classDecl.name.name, context.astOrigins[classDecl])
        compilationUnit.symbolTable.declareGlobal(
            classDecl.name.name,
            SemanticSymbol(
                classDecl.name.name,
                SemanticSymbolKind.TYPE_SPECIFIER,
                Token.Type.K_CLASS,
                AbsoluteFileLocation.Companion.fromRelative(
                    context.astOrigins[classDecl] ?: FileLocation.Companion.UNKNOWN,
                    context.file
                )
            )
        )
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl)
    {
        val parts = moduleDecl.uri.value.split(Symbols.COLON.rep)
        pumpOnTrue(
            parts.isEmpty(),
            "A module declaration must specify an author followed by a submodule name: 'author:submodule'",
            context.astOrigins[moduleDecl.name]!!,
            help = "Use the format 'author_name:submodule_name'."
        )
    }

    override fun visitObjectDecl(objectDecl: ObjectDecl)
    {
    }

    override fun visitEnumDecl(enumDecl: EnumDecl)
    {
        // TODO("Not yet implemented")
    }
}
