package net.exoad.kira.compiler.front

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.DiagnosticsException
import net.exoad.kira.compiler.SourceContext
import net.exoad.kira.compiler.front.elements.AnonymousFunction
import net.exoad.kira.compiler.front.elements.ArrayLiteral
import net.exoad.kira.compiler.front.elements.BoolLiteral
import net.exoad.kira.compiler.front.elements.FloatLiteral
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.IntegerLiteral
import net.exoad.kira.compiler.front.elements.ListLiteral
import net.exoad.kira.compiler.front.elements.Literal
import net.exoad.kira.compiler.front.elements.MapLiteral
import net.exoad.kira.compiler.front.elements.StringLiteral
import net.exoad.kira.compiler.front.elements.TypeSpecifier
import net.exoad.kira.compiler.front.exprs.AssignmentExpr
import net.exoad.kira.compiler.front.exprs.BinaryExpr
import net.exoad.kira.compiler.front.exprs.CompoundAssignmentExpr
import net.exoad.kira.compiler.front.exprs.EnumMemberExpr
import net.exoad.kira.compiler.front.exprs.ForIterationExpr
import net.exoad.kira.compiler.front.exprs.FunctionCallExpr
import net.exoad.kira.compiler.front.exprs.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.front.exprs.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.front.exprs.FunctionParameterExpr
import net.exoad.kira.compiler.front.exprs.IntrinsicCallExpr
import net.exoad.kira.compiler.front.exprs.MemberAccessExpr
import net.exoad.kira.compiler.front.exprs.NoExpr
import net.exoad.kira.compiler.front.exprs.RangeExpr
import net.exoad.kira.compiler.front.exprs.TypeCastExpr
import net.exoad.kira.compiler.front.exprs.TypeCheckExpr
import net.exoad.kira.compiler.front.exprs.UnaryExpr
import net.exoad.kira.compiler.front.exprs.WithExpr
import net.exoad.kira.compiler.front.exprs.WithExprMember
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.EnumDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionDecl
import net.exoad.kira.compiler.front.exprs.decl.ModuleDecl
import net.exoad.kira.compiler.front.exprs.decl.ObjectDecl
import net.exoad.kira.compiler.front.exprs.decl.VariableDecl
import net.exoad.kira.compiler.front.statements.BreakStatement
import net.exoad.kira.compiler.front.statements.ContinueStatement
import net.exoad.kira.compiler.front.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.front.statements.ElseBranchStatement
import net.exoad.kira.compiler.front.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.front.statements.ForIterationStatement
import net.exoad.kira.compiler.front.statements.IfSelectionStatement
import net.exoad.kira.compiler.front.statements.ReturnStatement
import net.exoad.kira.compiler.front.statements.Statement
import net.exoad.kira.compiler.front.statements.UseStatement
import net.exoad.kira.compiler.front.statements.WhileIterationStatement
import kotlin.reflect.KClass

data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: AbsoluteFileLocation,
)

enum class SemanticSymbolKind
{
    VARIABLE,
    FUNCTION,
    CLASS,
    OBJECT,
    ENUM,
    PARAMETER,
    TYPE_SPECIFIER
}

enum class Scope
{
    MODULE,
    CLASS,
    OBJECT,
    OBJECT_MAJOR, // object is the object declaration created using the "object scope"
    ENUM,
    FUNCTION,
}

class SymbolTable
{
    private val scopeStack: ArrayDeque<MutableMap<String, SemanticSymbol>> = ArrayDeque()
    private val scopeTypeStack: ArrayDeque<Scope> = ArrayDeque()

    init
    {
        enter(Scope.MODULE) // global scope!
    }

    fun enter(scopeType: Scope)
    {
        scopeStack.addFirst(mutableMapOf())
        scopeTypeStack.addFirst(scopeType)
    }

    fun clean()
    {
        // this doesnt look like the best optimal implementation here
        scopeStack.clear()
        scopeTypeStack.clear()
    }

    fun exit()
    {
        scopeStack.removeFirst()
        scopeTypeStack.removeFirst()
    }

    fun declare(identifier: String, symbol: SemanticSymbol): Boolean
    {
        for(scope in scopeStack)
        {
            if(scope.containsKey(identifier))
            {
                return false // already declared in any active scope
            }
        }
        scopeStack.first()[identifier] = symbol
        return true
    }

    fun resolve(identifier: String): SemanticSymbol?
    {
        for(scope in scopeStack)
        {
            if(scope.containsKey(identifier))
            {
                return scope[identifier]
            }
        }
        return null
    }

    fun peek(): Map<String, SemanticSymbol>
    {
        return scopeStack.first()
    }

    fun peekScope(): Scope
    {
        return scopeTypeStack.first()
    }
}

data class SemanticAnalyzerResults(
    val diagnostics: List<DiagnosticsException>,
    val symbolTable: SymbolTable,
    val isHealthy: Boolean,
)

/**
 * The 4th phase after the parsing process that traverses the generated AST by the [KiraParser]
 * to make sure everything follows the rules of the language and everything makes sense.
 */
class KiraSemanticAnalyzer(private val context: SourceContext) : ASTVisitor()
{
    private val diagnosticsPump = mutableListOf<DiagnosticsException>()
    val symbolTable: SymbolTable = SymbolTable()

    init
    {
        symbolTable.declare(
            "Int32",
            SemanticSymbol(
                name = "Int32",
                kind = SemanticSymbolKind.TYPE_SPECIFIER,
                type = Token.Type.IDENTIFIER,
                declaredAt = AbsoluteFileLocation.bakedIn()
            )
        )
        symbolTable.declare(
            "String",
            SemanticSymbol(
                name = "String",
                kind = SemanticSymbolKind.TYPE_SPECIFIER,
                type = Token.Type.IDENTIFIER,
                declaredAt = AbsoluteFileLocation.bakedIn()
            )
        )
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

    fun validateAST(): SemanticAnalyzerResults
    {
        try
        {
            context.ast.statements.forEach { it.accept(this) }
        }
        catch(_: Exception)
        {
            return SemanticAnalyzerResults(diagnosticsPump, symbolTable, false)
        }
        return SemanticAnalyzerResults(diagnosticsPump, symbolTable, diagnosticsPump.isEmpty()) // todo: need impl!
    }

    fun pumpOnTrue(expr: Boolean, message: String, location: FileLocation?, selectorLength: Int = 1, help: String = "")
    {
        if(expr)
        {
            pump(message, location ?: FileLocation.UNKNOWN, selectorLength, help)
        }
    }

    fun expectSymbol(symbolName: String, symbolKind: SemanticSymbolKind)
    {
        val res = symbolTable.resolve(symbolName)
        pumpOnTrue(
            res == null || res.kind != symbolKind, "Expected a $symbolKind for '$symbolName', but got '$res'",
            location = res?.declaredAt?.toRelative() ?: FileLocation.UNKNOWN,
            selectorLength = res!!.name.length
        )
    }

    fun expectNotDeclared(
        symbolName: String,
        location: FileLocation?,
        helpMessage: String = "Shadowing is not allowed. Rename this or the previous declaration.",
    )
    {
        val res = symbolTable.resolve(symbolName)
        if(res != null)
        {
            pump(
                "'${symbolName}' was already declared at ${res.declaredAt}",
                location = location ?: FileLocation.UNKNOWN,
                selectorLength = symbolName.length,
                help = helpMessage
            )
        }
    }

    fun expectType(symbolName: String, typeName: String)
    {
        val res = symbolTable.resolve(symbolName)
        if(res == null || res.kind != SemanticSymbolKind.TYPE_SPECIFIER || res.name == typeName)
        {
            pump(
                "Expected a $typeName for $symbolName, but got '$res'",
                location = res?.declaredAt?.toRelative() ?: FileLocation.UNKNOWN
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

    override fun visitFunctionParameterExpr(functionParameterExpr: FunctionParameterExpr)
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

    override fun visitFunctionLiteral(functionLiteral: AnonymousFunction)
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

    override fun visitIdentifier(identifier: Identifier)
    {
        expectNotDeclared(identifier.name, context.astOrigins[identifier])
        symbolTable.declare(
            identifier.name, SemanticSymbol(
                name = identifier.name,
                kind = SemanticSymbolKind.VARIABLE,
                type = Token.Type.IDENTIFIER,
                declaredAt = AbsoluteFileLocation.fromRelative(
                    context.astOrigins[identifier] ?: FileLocation.UNKNOWN, context.file
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
        if(symbolTable.resolve(variableDecl.typeSpecifier.name) == null)
        {
            pump(
                "The type '${variableDecl.typeSpecifier.name}' was not found at this scope (${symbolTable.peekScope().name.lowercase()})",
                location = context.astOrigins[variableDecl.typeSpecifier] ?: FileLocation.UNKNOWN,
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
        symbolTable.declare(
            classDecl.name.name,
            SemanticSymbol(
                classDecl.name.name,
                SemanticSymbolKind.CLASS,
                Token.Type.K_CLASS,
                AbsoluteFileLocation.fromRelative(context.astOrigins[classDecl] ?: FileLocation.UNKNOWN, context.file)
            )
        )
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl)
    {
        // TODO("Not yet implemented")
    }

    override fun visitObjectDecl(objectDecl: ObjectDecl)
    {
    }

    override fun visitEnumDecl(enumDecl: EnumDecl)
    {
        // TODO("Not yet implemented")
    }

}
