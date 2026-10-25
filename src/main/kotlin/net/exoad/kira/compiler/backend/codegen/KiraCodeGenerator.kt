package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TernaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfElseBranchStatementNode
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement

abstract class KiraCodeGenerator(compilationUnit: CompilationUnit) : KiraASTVisitor() {
    init {
        net.exoad.kira.compiler.UnitLink.link(compilationUnit)
    }

    open val compilationUnit: CompilationUnit = compilationUnit

    abstract fun visitRootASTNode(node: RootASTNode)

    /**
     * A returned ternary, `c ? a : d ? b : e`, as the if/else-if chain of returns it reads as,
     * for a backend that lowers no if value (C, JS); null for an if value with statement arms.
     */
    protected fun returnsOf(e: IfExpr): IfSelectionStatement? {
        if (e !is TernaryExpr) return null
        val branches = mutableListOf<IfElseBranchStatementNode>()
        var rest = e.no
        while (rest is TernaryExpr) {
            branches += ElseIfBranchStatement(rest.condition, listOf(ReturnStatement(rest.yes)))
            rest = rest.no
        }
        branches += ElseBranchStatement(listOf(ReturnStatement(rest)))
        return IfSelectionStatement(e.condition, listOf(ReturnStatement(e.yes)), branches)
    }
}
