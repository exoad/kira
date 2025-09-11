package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr

abstract class FirstClassDecl(
    override val name: Expr,
    open val modifiers: List<Modifier>,
) : Decl(name) {
    init {
        require(name is Identifier || name is IntrinsicExpr) {
            "First class declarations must either use an identifier or intrinsic to be named.\nGot $name"
        }
    }

    abstract fun isStub(): Boolean
}