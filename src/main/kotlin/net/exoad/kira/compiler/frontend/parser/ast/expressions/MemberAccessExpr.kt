package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

open class MemberAccessExpr(val origin: Expr, val member: Expr) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitMemberAccessExpr(this)
    }

    override fun toString(): String {
        return "MemberAccess{ $origin -> $member }"
    }
}