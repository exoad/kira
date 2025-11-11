package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class ObjectInitExpr(val typeName: Identifier, val positionalArgs: List<Expr>) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitObjectInitExpr(this)
    }

    override fun toString(): String {
        return "ObjectInit{ $typeName -> $positionalArgs }"
    }
}
