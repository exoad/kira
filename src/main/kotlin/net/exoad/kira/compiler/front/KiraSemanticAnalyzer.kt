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
        if(scopeStack.first().containsKey(identifier))
        {
            return false
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

    private fun pump(message: String, location: FileLocation, selectorLength: Int = 1)
    {
        diagnosticsPump.add(
            Diagnostics.recordPanic(
                "",
                message,
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
        // TODO("Not yet implemented")
    }

    override fun visitStringLiteral(stringLiteral: StringLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitBoolLiteral(boolLiteral: BoolLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFloatLiteral(floatLiteral: FloatLiteral)
    {
        // TODO("Not yet implemented")
    }

    override fun visitFunctionLiteral(functionLiteral: AnonymousFunction)
    {
        // TODO("Not yet implemented")
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
        if(symbolTable.resolve(identifier.name) == null)
        {
            pump(
                "The identifier '${identifier.name}' was not found at this scope (${symbolTable.peekScope().name.lowercase()})",
                location = context.astOrigins[identifier] ?: FileLocation(-1, -1)
            )
        }
    }

    override fun visitTypeSpecifier(typeSpecifier: TypeSpecifier)
    {
        if(symbolTable.resolve(typeSpecifier.name) == null)
        {
            pump(
                "The type '${typeSpecifier.name}' was not found at this scope (${symbolTable.peekScope().name.lowercase()})",
                location = context.astOrigins[typeSpecifier] ?: FileLocation.UNKNOWN
            )
        }
    }

    override fun visitVariableDecl(variableDecl: VariableDecl)
    {
        val name = variableDecl.name.name
        if(!symbolTable.declare(
                name, SemanticSymbol(
                    name = name,
                    kind = SemanticSymbolKind.VARIABLE,
                    type = Token.Type.IDENTIFIER,
                    declaredAt = AbsoluteFileLocation.fromRelative(
                        context.astOrigins[variableDecl] ?: FileLocation.UNKNOWN, context.file
                    )
                )
            )
        )
        {
            pump(
                "Variable '${name}' was already declared in the ${symbolTable.peekScope().name.lowercase()} scope at ${
                    symbolTable.resolve(
                        name
                    )?.declaredAt
                }",
                location = context.astOrigins[variableDecl] ?: FileLocation.UNKNOWN
            )
        }
    }

    override fun visitFunctionDecl(functionDecl: FunctionDecl)
    {
        // TODO("Not yet implemented")
    }

    override fun visitClassDecl(classDecl: ClassDecl)
    {
        // TODO("Not yet implemented")
    }

    override fun visitModuleDecl(moduleDecl: ModuleDecl)
    {
        // TODO("Not yet implemented")
    }

    override fun visitObjectDecl(objectDecl: ObjectDecl)
    {
        // TODO("Not yet implemented")
    }

    override fun visitEnumDecl(enumDecl: EnumDecl)
    {
        // TODO("Not yet implemented")
    }

}
