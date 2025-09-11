package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.FunctionLiteral

open class FunctionDecl(
    override val name: Expr,
    open val value: FunctionLiteral,
    override val modifiers: List<Modifier> = emptyList(),
) : FirstClassDecl(name, modifiers) {
    fun isAnonymous(): Boolean {
        return name is AnonymousIdentifier
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionDecl(this)
    }

    override fun toString(): String {
        return "__FX__${modifiers.ifEmpty { "" }}{ ${
            if (isAnonymous()) "(_Anon_)"
            else
                ""
        } " +
                "$name -> $value}"
    }

    override fun isStub(): Boolean {
        return value.body == null
    }
}