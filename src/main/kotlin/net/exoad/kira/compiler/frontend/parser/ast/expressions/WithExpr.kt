package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class WithExpr(val members: List<WithExprMember>) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitWithExpr(this)
    }

    override fun toString(): String {
        return "With{ $members }"
    }
}

open class WithExprMember(val name: Identifier, val value: Expr) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitWithExprMember(this)
    }

    override fun toString(): String {
        return "WithMember{ $name -> $value }"
    }
}