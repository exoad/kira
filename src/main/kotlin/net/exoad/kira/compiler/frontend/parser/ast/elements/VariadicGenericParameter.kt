package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor

class VariadicGenericParameter(override val name: String, val constraints: UnionType?) : GenericParameter(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitVariadicGenericParameter(this)
    }

    override fun toString(): String {
        return "VariadicGenericParam{ $name ++ $constraints }"
    }
}