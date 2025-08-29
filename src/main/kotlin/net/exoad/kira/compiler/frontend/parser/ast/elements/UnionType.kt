package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

class UnionType(val types: List<TypeSpecifier>) : Expr() {
    override fun toString(): String {
        return types.joinToString(" | ") { it.toString() }
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitUnionType(this)
    }
}