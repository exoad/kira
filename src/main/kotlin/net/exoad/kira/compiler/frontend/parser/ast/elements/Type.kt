package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr

open class Type(
    open val identifier: Expr,
    open val constraint: Type?,
    open val children: List<Type>
) : Expr() {
    constructor(name: Expr) : this(name, null, emptyList())

    init {
        require(identifier is Identifier || identifier is IntrinsicExpr) {
            "A type can only be named using either intrinsics or identifiers."
        }
    }

    /**
     * Signifies that this type has no children and no constraint
     */
    fun isHermit(): Boolean {
        return children.isEmpty() && constraint == null
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitType(this)
    }

    override fun toString(): String {
        return "<$identifier${if (constraint != null) "++${constraint}" else ""} $children>"
    }
}
