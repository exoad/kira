package net.exoad.kira.compiler.frontend.parser.ast

import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*


abstract class KiraASTVisitor {
    // Every visit defaults to doing nothing; a visitor overrides the nodes it handles.
    // STATEMENTS
    open fun visitStatement(statement: Statement) {}
    open fun visitIfSelectionStatement(ifSelectionStatement: IfSelectionStatement) {}
    open fun visitIfElseIfBranchStatement(ifElseIfBranchNode: ElseIfBranchStatement) {}
    open fun visitElseBranchStatement(elseBranchNode: ElseBranchStatement) {}
    open fun visitWhileIterationStatement(whileIterationStatement: WhileIterationStatement) {}
    open fun visitReturnStatement(returnStatement: ReturnStatement) {}
    open fun visitForIterationStatement(forIterationStatement: ForIterationStatement) {}
    open fun visitUseStatement(useStatement: UseStatement) {}
    open fun visitBreakStatement(breakStatement: BreakStatement) {}
    open fun visitContinueStatement(continueStatement: ContinueStatement) {}

    // Expressions
    open fun visitBinaryExpr(binaryExpr: BinaryExpr) {}
    open fun visitUnaryExpr(unaryExpr: UnaryExpr) {}
    open fun visitAssignmentExpr(assignmentExpr: AssignmentExpr) {}
    open fun visitFunctionCallExpr(functionCallExpr: FunctionCallExpr) {}
    open fun visitIntrinsicExpr(intrinsicExpr: IntrinsicExpr) {}
    open fun visitCompoundAssignmentExpr(compoundAssignmentExpr: CompoundAssignmentExpr) {}
    open fun visitFunctionParameterExpr(functionDeclParameterExpr: FunctionDeclParameterExpr) {}
    open fun visitMemberAccessExpr(memberAccessExpr: MemberAccessExpr) {}
    open fun visitArrayIndexExpr(arrayIndexExpr: ArrayIndexExpr) {}
    open fun visitThrowExpr(throwExpr: ThrowExpr) {}
    open fun visitTryExpr(tryExpr: TryExpr) {}
    open fun visitForIterationExpr(forIterationExpr: ForIterationExpr) {}
    open fun visitRangeExpr(rangeExpr: RangeExpr) {}
    open fun visitObjectInitExpr(objectInitExpr: ObjectInitExpr) {}
    open fun visitTypeCheckExpr(typeCheckExpr: TypeCheckExpr) {}
    open fun visitTypeCastExpr(typeCastExpr: TypeCastExpr) {}
    open fun visitNoExpr(noExpr: NoExpr) {}
    open fun visitWithExpr(withExpr: WithExpr) {}
    open fun visitFunctionCallNamedParameterExpr(functionCallNamedParameterExpr: FunctionCallNamedParameterExpr) {}
    open fun visitFunctionCallPositionalParameterExpr(functionCallPositionalParameterExpr: FunctionCallPositionalParameterExpr) {}
    open fun visitFunctionDefExpr(functionDefExpr: FunctionDefExpr) {}

    // LITERALS
    open fun visitIntegerLiteral(integerLiteral: IntegerLiteral) {}
    open fun visitStringLiteral(stringLiteral: StringLiteral) {}
    open fun visitFloatLiteral(floatLiteral: FloatLiteral) {}
    open fun visitBuilderExpr(builderExpr: BuilderExpr) {}
    open fun visitNullLiteral(nullLiteral: NullLiteral) {} // this will always be the same instance of null (null isnt a true value)

    // ELEMENTS
    open fun visitType(type: Type) {}
    open fun visitIdentifier(identifier: Identifier) {}

    // DECLARATIONS
    open fun visitVariableDecl(variableDecl: VariableDecl) {}
    open fun visitFunctionDecl(functionDecl: FunctionDecl) {}
    open fun visitClassDecl(classDecl: ClassDecl) {}
    open fun visitModuleDecl(moduleDecl: ModuleDecl) {}
    open fun visitTraitDecl(traitDecl: TraitDecl) {}
    open fun visitTypeAliasDecl(typeAliasDecl: TypeAliasDecl) {}

    // THE AST CONTRACT (design 2.4). Each new syntax form is a new node, and
    // its visit defaults to a throw: a backend that does not lower the form
    // fails loudly, and can never lower it as something else. The semantic
    // analyzer and the XML dumper override every one of these.
    open fun visitStructDecl(structDecl: StructDecl) {
        throw UnsupportedConstruct(structDecl, "struct declaration")
    }

    open fun visitIfExpr(ifExpr: IfExpr) {
        throw UnsupportedConstruct(ifExpr, "if-expression")
    }

    open fun visitLambdaExpr(lambdaExpr: LambdaExpr) {
        throw UnsupportedConstruct(lambdaExpr, "lambda expression")
    }

    open fun visitPlaceAssignmentExpr(placeAssignmentExpr: PlaceAssignmentExpr) {
        throw UnsupportedConstruct(placeAssignmentExpr, "assignment to a member or index place")
    }

    open fun visitThisExpr(thisExpr: ThisExpr) {
        throw UnsupportedConstruct(thisExpr, "'this' expression")
    }

    open fun visitCharLiteral(charLiteral: CharLiteral) {
        throw UnsupportedConstruct(charLiteral, "char literal")
    }

    open fun visitInterpolatedStringLiteral(interpolatedStringLiteral: InterpolatedStringLiteral) {
        throw UnsupportedConstruct(interpolatedStringLiteral, "interpolated string literal")
    }
}