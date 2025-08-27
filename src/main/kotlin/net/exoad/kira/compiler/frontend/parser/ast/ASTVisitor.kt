package net.exoad.kira.compiler.frontend.parser.ast

import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*

abstract class ASTVisitor
{
    // STATEMENTS
    abstract fun visitStatement(statement: Statement)
    abstract fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement)
    abstract fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement)
    abstract fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement)
    abstract fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement)
    abstract fun visitDoWhileIterationStatement(doWhileIterationStatement: DoWhileIterationStatement)
    abstract fun visitReturnStatement(returnStatement: ReturnStatement)
    abstract fun visitForIterationStatement(forIterationStatement: ForIterationStatement)
    abstract fun visitUseStatement(useStatement: UseStatement)
    abstract fun visitBreakStatement(breakStatement: BreakStatement)
    abstract fun visitContinueStatement(continueStatement: ContinueStatement)

    // Expressions
    abstract fun visitBinaryExpr(binaryExpr: BinaryExpr)
    abstract fun visitUnaryExpr(unaryExpr: UnaryExpr)
    abstract fun visitAssignmentExpr(assignmentExpr: AssignmentExpr)
    abstract fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr)
    abstract fun visitIntrinsicCallExpr(intrinsicCallExpr: IntrinsicCallExpr)
    abstract fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr)
    abstract fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr)
    abstract fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr)
    abstract fun visitForIterationExpr(forIterationExpr: ForIterationExpr)
    abstract fun visitRangeExpr(rangeExpr: RangeExpr)
    abstract fun visitEnumMemberExpr(enumMemberExpr: EnumMemberExpr)
    abstract fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr)
    abstract fun visitTypeCastExpr(typeCastExpr: TypeCastExpr)
    abstract fun visitNoExpr(noExpr: NoExpr)
    abstract fun visitWithExpr(withExpr: WithExpr)
    abstract fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr)
    abstract fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr)
    abstract fun visitWithExprMember(withExprMember: WithExprMember)

    // LITERALS
    abstract fun visitIntegerLiteral(integerLiteral: IntegerLiteral)
    abstract fun visitStringLiteral(stringLiteral: StringLiteral)
    abstract fun visitBoolLiteral(boolLiteral: BoolLiteral)
    abstract fun visitFloatLiteral(floatLiteral: FloatLiteral)
    abstract fun visitFunctionLiteral(functionLiteral: FunctionLiteral)
    abstract fun visitArrayLiteral(arrayLiteral: ArrayLiteral)
    abstract fun visitListLiteral(listLiteral: ListLiteral)
    abstract fun visitMapLiteral(mapLiteral: MapLiteral)
    abstract fun visitNullLiteral(nullLiteral: NullLiteral) // this will always be the same instance of null (null isnt a true value)

    // IDENTIFIERS
    abstract fun visitIdentifier(identifier: Identifier)
    abstract fun visitTypeSpecifier(typeSpecifier: TypeSpecifier)

    // DECLARATIONS
    abstract fun visitVariableDecl(variableDecl: VariableDecl)
    abstract fun visitFunctionDecl(functionDecl: FunctionDecl)
    abstract fun visitClassDecl(classDecl: ClassDecl)
    abstract fun visitModuleDecl(moduleDecl: ModuleDecl)
    abstract fun visitEnumDecl(enumDecl: EnumDecl)
    abstract fun visitNamespaceDecl(namespaceDecl: NamespaceDecl)
}