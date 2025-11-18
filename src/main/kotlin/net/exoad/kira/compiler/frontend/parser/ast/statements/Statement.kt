package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class Statement(open val expr: Expr) : ASTNode {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitStatement(this)
    }

    override fun toString(): String {
        return "Statement(expr=$expr)"
    }
}