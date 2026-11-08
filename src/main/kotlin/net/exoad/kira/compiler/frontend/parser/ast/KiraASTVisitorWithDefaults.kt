package net.exoad.kira.compiler.frontend.parser.ast

import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*


// The analyser alone may ignore nodes it does not handle. The code generators and the
// XML dumper extend KiraASTVisitor, so a new node fails their compile until handled.
abstract class KiraASTVisitorWithDefaults : KiraASTVisitor() {
    // STATEMENTS
    override fun visitStatement(statement: Statement) {}
    override fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {}
    override fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {}
    override fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {}
    override fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {}
    override fun visitReturnStatement(returnStatement: ReturnStatement) {}
    override fun visitUseStatement(useStatement: UseStatement) {}
    override fun visitBreakStatement(breakStatement: BreakStatement) {}
    override fun visitContinueStatement(continueStatement: ContinueStatement) {}

    // Expressions
    override fun visitBinaryExpr(binaryExpr: BinaryExpr) {}
    override fun visitUnaryExpr(unaryExpr: UnaryExpr) {}
    override fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {}
    override fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {}
    override fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {}
    override fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {}
    override fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {}
    override fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {}
    override fun visitArrayIndexExpr(arrayIndexExpr: ArrayIndexExpr) {}
    override fun visitThrowExpr(throwExpr: ThrowExpr) {}
    override fun visitTryExpr(tryExpr: TryExpr) {}
    override fun visitRangeExpr(rangeExpr: RangeExpr) {}
    override fun visitObjectInitExpr(objectInitExpr: ObjectInitExpr) {}
    override fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {}
    override fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {}
    override fun visitNoExpr(noExpr: NoExpr) {}
    override fun visitWithExpr(withExpr: WithExpr) {}
    override fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {}
    override fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr) {}
    override fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {}

    // LITERALS
    override fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {}
    override fun visitStringLiteral(stringLiteral: StringLiteral) {}
    override fun visitFloatLiteral(floatLiteral: FloatLiteral) {}
    override fun visitBuilderExpr(builderExpr: BuilderExpr) {}
    override fun visitNullLiteral(nullLiteral: NullLiteral) {}

    // ELEMENTS
    override fun visitType(type: Type) {}
    override fun visitIdentifier(identifier: Identifier) {}

    // DECLARATIONS
    override fun visitVariableDecl(variableDecl: VariableDecl) {}
    override fun visitFunctionDecl(functionDecl: FunctionDecl) {}
    override fun visitClassDecl(classDecl: ClassDecl) {}
    override fun visitModuleDecl(moduleDecl: ModuleDecl) {}
    override fun visitTraitDecl(traitDecl: TraitDecl) {}
    override fun visitTypeAliasDecl(typeAliasDecl: TypeAliasDecl) {}
}
