package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.IntrinsicCapability
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourceLocation
import net.exoad.kira.source.SourcePosition
import net.exoad.kira.utils.EnglishUtils

/**
 * The 4th phase after the parsing process that traverses the generated AST by the [net.exoad.kira.compiler.frontend.parser.KiraParser]
 * to make sure everything follows the rules of the language and everything makes sense.
 */
class KiraSemanticAnalyzer(private val compilationUnit: CompilationUnit) : KiraASTVisitor() {
    private val diagnosticsPump = mutableListOf<DiagnosticsException>()
    lateinit var context: SourceContext

    fun validateAST(): SemanticAnalyzerResults {
        try {
            for (source in compilationUnit.allSources()) {
                context = source
                source.ast.accept(this)
            }
        } catch (_: Exception) {
            return SemanticAnalyzerResults(diagnosticsPump, compilationUnit.symbolTable, false)
        }
        return SemanticAnalyzerResults(diagnosticsPump, compilationUnit.symbolTable, diagnosticsPump.isEmpty())
    }

    private fun pump(message: String, location: SourcePosition, selectorLength: Int = 1, help: String = "") {
        diagnosticsPump.add(
            Diagnostics.recordPanic(
                "",
                if (help.isEmpty()) message else "$message\n\nHelp: $help",
                location = location,
                selectorLength = selectorLength,
                context = context
            )
        )
    }

    fun pumpOnTrue(
        expr: Boolean,
        message: String,
        location: SourcePosition?,
        selectorLength: Int = 1,
        help: String = "",
    ) {
        if (expr) {
            pump(message, location ?: SourcePosition.UNKNOWN, selectorLength, help)
        }
    }

    fun expectSymbol(symbolName: String, symbolKind: SemanticSymbolKind) {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        pumpOnTrue(
            res == null || res.kind != symbolKind, "Expected a $symbolKind for '$symbolName', but got '$res'",
            location = res?.declaredAt?.toPosition() ?: SourcePosition.UNKNOWN,
            selectorLength = res!!.name.length
        )
    }

    fun expectDeclared(
        symbolName: String,
        location: SourcePosition?,
        helpMessage: String = "'$symbolName' is not available at this scope. Or it has not been declared.",
    ) {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if (res == null) {
            pump(
                "'${symbolName}' is an unknown symbol here.",
                location = location ?: SourcePosition.UNKNOWN,
                selectorLength = symbolName.length,
                help = helpMessage
            )
        }
    }

    fun expectTypeNotDeclaredInModule(symbolName: String, location: SourcePosition?) {
        val moduleScope = compilationUnit.symbolTable.findScope(SemanticScope.Module(context.getModuleUri()))
        if (moduleScope?.symbols?.containsKey(symbolName) ?: true) {
            pump(
                "'${symbolName}' was already declared in the current module.",
                location = location ?: SourcePosition.UNKNOWN,
                selectorLength = symbolName.length,
                help = "Rename this type or remove the previous declaration. Shadowing types in the module scope is not allowed."
            )
        }
    }

    fun expectNotDeclared(
        symbolName: String,
        location: SourcePosition?,
        helpMessage: String = "Shadowing is not allowed. Rename this or the previous declaration.",
    ) {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if (res != null) {
            pump(
                "'${symbolName}' was already declared at ${res.declaredAt}",
                location = location ?: SourcePosition.UNKNOWN,
                selectorLength = symbolName.length,
                help = helpMessage
            )
        }
    }

    fun expectType(symbolName: String, typeName: String) {
        val res = compilationUnit.symbolTable.resolve(symbolName)
        if (res == null || res.kind != SemanticSymbolKind.TYPE_SPECIFIER || res.name == typeName) {
            pump(
                "Expected ${EnglishUtils.prependIndefiniteArticle(typeName)} for $symbolName, but got '$res'",
                location = res?.declaredAt?.toPosition() ?: SourcePosition.UNKNOWN
            )
        }
    }

    override fun visitStatement(statement: Statement) {
        statement.expr.accept(this)
    }

    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitReturnStatement(returnStatement: ReturnStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitForIterationStatement(forIterationStatement: ForIterationStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitUseStatement(useStatement: UseStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitBreakStatement(breakStatement: BreakStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitContinueStatement(continueStatement: ContinueStatement) {
        // TODO("Not yet implemented")
    }

    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {
        pumpOnTrue(
            intrinsicExpr.intrinsicKey.capabilities.all { it != IntrinsicCapability.Functor } && intrinsicExpr.parameters != null,
            message = "The intrinsic '${intrinsicExpr.intrinsicKey.rep}' cannot be used as a functor.",
            location = intrinsicExpr.sourceLocation.toPosition(),
            selectorLength = intrinsicExpr.intrinsicKey.rep.length,
            help =
                "Supported capabilities for this intrinsic: ${intrinsicExpr.intrinsicKey.capabilities.joinToString(",") { it::class.simpleName!! }}"
        )
        pumpOnTrue(
            intrinsicExpr.intrinsicKey.capabilities.contains(IntrinsicCapability.Functor) && !intrinsicExpr.intrinsicKey.capabilities.contains(
                IntrinsicCapability.Marker
            ) && (intrinsicExpr.parameters?.isEmpty() ?: true),
            message = "The intrinsic '${intrinsicExpr.intrinsicKey.rep}' can only be used as a functor. Supply arguments to it. ",
            location = intrinsicExpr.sourceLocation.toPosition(),
            selectorLength = intrinsicExpr.intrinsicKey.rep.length,
        )
    }

    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitRangeExpr(rangeExpr: RangeExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitNoExpr(noExpr: NoExpr) {
        // ignore
    }

    override fun visitWithExpr(withExpr: WithExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr) {
        // TODO("Not yet implemented")
    }

    override fun visitWithExprMember(withExprMember: WithExprMember) {
        // TODO("Not yet implemented")
    }

    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {
        // should be true
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral) {
        // should be true
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral) {
        // should be true
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {
        // should be true
    }

    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {
        // should be true
    }

    override fun visitArrayLiteral(arrayLiteral: ArrayLiteral) {
        // TODO("Not yet implemented")
    }

    override fun visitListLiteral(listLiteral: ListLiteral) {
        // TODO("Not yet implemented")
    }

    override fun visitMapLiteral(mapLiteral: MapLiteral) {
        // TODO("Not yet implemented")
    }

    override fun visitNullLiteral(nullLiteral: NullLiteral) {
        // TODO("Not yet implemented")
    }

    override fun visitType(type: Type) {
        // todo
    }

    override fun visitIdentifier(identifier: Identifier) {
        expectNotDeclared(identifier.value, context.astOrigins[identifier])
        compilationUnit.symbolTable.declare(
            identifier.value, SemanticSymbol(
                name = identifier.value,
                kind = SemanticSymbolKind.VARIABLE,
                type = Token.Type.IDENTIFIER,
                declaredAt = SourceLocation.fromPosition(
                    context.astOrigins[identifier] ?: SourcePosition.UNKNOWN, context.file
                )
            )
        )
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

    override fun visitVariableDecl(variableDecl: VariableDecl) {
        variableDecl.name.accept(this)
        if (variableDecl.type.identifier is Identifier) {
            if (compilationUnit.symbolTable.resolve((variableDecl.type.identifier as Identifier).value) == null) {
                pump(
                    "The type '${variableDecl.type.identifier}' was not found at this scope (${compilationUnit.symbolTable.where().name.lowercase()})",
                    location = context.astOrigins[variableDecl.type] ?: SourcePosition.UNKNOWN,
                    selectorLength = (variableDecl.type.identifier as Identifier).length()
                )
            }
            variableDecl.type.accept(this)
            // check if the value of the variable matches the type
            //
            // we need to check for a multitude of conditions
            // 1. if it's a raw literal or object value, check if just the type names match
            // 2. if it's a function call, check the return type of that function type
            // (there are more edge cases, but i am not too sure
            if (variableDecl.value != null) {
                variableDecl.value!!.accept(this)
                val typeName = (variableDecl.type.identifier as Identifier).value
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

    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl) {
//        TODO("Not yet implemented")
    }

    override fun visitClassDecl(classDecl: ClassDecl) {
        if (classDecl.name.identifier is Identifier) {
            expectTypeNotDeclaredInModule(
                (classDecl.name.identifier as Identifier).value,
                context.astOrigins[classDecl]
            )
            compilationUnit.symbolTable.declare(
                (classDecl.name.identifier as Identifier).value,
                SemanticSymbol(
                    (classDecl.name.identifier as Identifier).value,
                    SemanticSymbolKind.TYPE_SPECIFIER,
                    Token.Type.K_CLASS,
                    SourceLocation.fromPosition(
                        context.astOrigins[classDecl] ?: SourcePosition.UNKNOWN,
                        context.file
                    ),
                    relativelyVisible = classDecl.modifiers.contains(Modifier.PUBLIC)
                )
            )
//            classDecl.name.accept(this)
            compilationUnit.symbolTable.enter(SemanticScope.Class((classDecl.name.identifier as Identifier).value))
            classDecl.members.forEach { it.accept(this) }
            compilationUnit.symbolTable.exit()
        }
    }

    private val fullUriMatcher = Regex("^[a-zA-Z0-9_]+:[a-zA-Z0-9_]+(?:/[a-zA-Z0-9_]+)*$")
    override fun visitModuleDecl(moduleDecl: ModuleDecl) {
        val uri = moduleDecl.uri.value
        pumpOnTrue(
            !uri.matches(fullUriMatcher),
            "A module URI must be in the format 'author:project/submodule/submodule/...' and contain only [a-zA-Z0-9_] characters.",
            context.astOrigins[moduleDecl.name]!!.offsetBy(0, 1), // skip leading quotation
            selectorLength = uri.length,
            help = "Here is the regex that is used: ^[a-zA-Z0-9_]+:[a-zA-Z0-9_]+(?:/[a-zA-Z0-9_]+)*$"
        )
    }

    override fun visitEnumDecl(enumDecl: EnumDecl) {
        // TODO("Not yet implemented")
    }

    override fun visitTraitDecl(traitDecl: TraitDecl) {
        TODO("Not yet implemented")
    }
}
