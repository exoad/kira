package net.exoad.kira.compiler.front

import net.exoad.kira.compiler.front.elements.AnonymousFunction
import net.exoad.kira.compiler.front.elements.ArrayLiteral
import net.exoad.kira.compiler.front.elements.BoolLiteral
import net.exoad.kira.compiler.front.elements.FloatLiteral
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.IntegerLiteral
import net.exoad.kira.compiler.front.elements.ListLiteral
import net.exoad.kira.compiler.front.elements.MapLiteral
import net.exoad.kira.compiler.front.elements.NullLiteral
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
    abstract fun visitFunctionParameterExpr(functionParameterExpr: FunctionParameterExpr)
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
    abstract fun visitFunctionLiteral(functionLiteral: AnonymousFunction)
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
    abstract fun visitObjectDecl(objectDecl: ObjectDecl)
    abstract fun visitEnumDecl(enumDecl: EnumDecl)
}