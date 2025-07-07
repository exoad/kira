package net.exoad.kira.compiler.front

import net.exoad.kira.compiler.front.elements.AnonymousFunction
import net.exoad.kira.compiler.front.elements.BoolLiteral
import net.exoad.kira.compiler.front.elements.FloatLiteral
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.IntegerLiteral
import net.exoad.kira.compiler.front.elements.StringLiteral
import net.exoad.kira.compiler.front.elements.TypeSpecifier
import net.exoad.kira.compiler.front.exprs.AssignmentExpr
import net.exoad.kira.compiler.front.exprs.BinaryExpr
import net.exoad.kira.compiler.front.exprs.CompoundAssignmentExpr
import net.exoad.kira.compiler.front.exprs.ForIterationExpr
import net.exoad.kira.compiler.front.exprs.FunctionCallExpr
import net.exoad.kira.compiler.front.exprs.FunctionParameterExpr
import net.exoad.kira.compiler.front.exprs.IntrinsicCallExpr
import net.exoad.kira.compiler.front.exprs.MemberAccessExpr
import net.exoad.kira.compiler.front.exprs.RangeExpr
import net.exoad.kira.compiler.front.exprs.UnaryExpr
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionFirstClassDecl
import net.exoad.kira.compiler.front.exprs.decl.ModuleDecl
import net.exoad.kira.compiler.front.exprs.decl.VariableFirstClassDecl
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
    val name: Identifier,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: FileLocation
)

enum class SemanticSymbolKind
{ VARIABLE, FUNCTION, CLASS, PARAMETER }

/**
 * Symbol table to track everything within the program including scope, type, value.
 *
 * - Scope and everything is tracked using a stack (backed by [ArrayDeque]
 */
class SymbolTable
{
    private val scopeStack: ArrayDeque<MutableMap<Identifier, SemanticSymbol>> = ArrayDeque()

    init
    {
        enter() // global scope!
    }

    /**
     * Go into a scope (i.e. get deeper)
     */
    fun enter()
    {
        scopeStack.addFirst(mutableMapOf())
    }

    /**
     * Removes everything from this symbol table
     */
    fun clean()
    {
        // this doesnt look like the best optimal implementation here
        scopeStack.clear()
    }

    /**
     * Get out of a scope (i.e. get shallower)
     */
    fun exit()
    {
        scopeStack.removeFirst()
    }

    fun declare(identifier: Identifier, symbol: SemanticSymbol): Boolean
    {
        if(scopeStack.first().containsKey(identifier))
        {
            return false
        }
        scopeStack.first()[identifier] = symbol
        return true
    }

    fun resolve(identifier: Identifier): SemanticSymbol?
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

    fun peek(): Map<Identifier, SemanticSymbol>
    {
        return scopeStack.first()
    }
}

/**
 * The 4th phase after the parsing process that traverses the generated AST by the [KiraParser]
 * to make sure everything follows the rules of the language and everything makes sense.
 */
object KiraSemanticAnalyzer
{
    fun validateAST(rootASTNode: RootASTNode): Boolean
    {
        val analyzer = AnalyzerBase()
        return true // todo: need impl!
    }

    private class AnalyzerBase() : ASTVisitor()
    {
        override fun visitStatement(statement: Statement)
        {
            TODO("Not yet implemented")
        }

        override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitReturnStatement(returnStatement: ReturnStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitForIterationStatement(forIterationStatement: ForIterationStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitUseStatement(useStatement: UseStatement)
        {
            TODO("Not yet implemented")
        }

        override fun visitBinaryExpr(binaryExpr: BinaryExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitUnaryExpr(unaryExpr: UnaryExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitIntrinsicCallExpr(intrinsicCallExpr: IntrinsicCallExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitFunctionParameterExpr(functionParameterExpr: FunctionParameterExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitForIterationExpr(forIterationExpr: ForIterationExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitRangeExpr(rangeExpr: RangeExpr)
        {
            TODO("Not yet implemented")
        }

        override fun visitIntegerLiteral(integerLiteral: IntegerLiteral)
        {
            TODO("Not yet implemented")
        }

        override fun visitStringLiteral(stringLiteral: StringLiteral)
        {
            TODO("Not yet implemented")
        }

        override fun visitBoolLiteral(boolLiteral: BoolLiteral)
        {
            TODO("Not yet implemented")
        }

        override fun visitFloatLiteral(floatLiteral: FloatLiteral)
        {
            TODO("Not yet implemented")
        }

        override fun visitFunctionLiteral(functionLiteral: AnonymousFunction)
        {
            TODO("Not yet implemented")
        }

        override fun visitIdentifier(identifier: Identifier)
        {
            TODO("Not yet implemented")
        }

        override fun visitTypeSpecifier(typeSpecifier: TypeSpecifier)
        {
            TODO("Not yet implemented")
        }

        override fun visitVariableDecl(variableDecl: VariableFirstClassDecl)
        {
            TODO("Not yet implemented")
        }

        override fun visitFunctionDecl(functionDecl: FunctionFirstClassDecl)
        {
            TODO("Not yet implemented")
        }

        override fun visitClassDecl(classDecl: ClassDecl)
        {
            TODO("Not yet implemented")
        }

        override fun visitModuleDecl(moduleDecl: ModuleDecl)
        {
            TODO("Not yet implemented")
        }

    }
}