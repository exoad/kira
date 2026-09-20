package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.AnonymousIdentifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr

open class FunctionDecl(
    override val name: Expr,
    open val def: FunctionDefExpr,
    override val modifiers: List<Modifier> = emptyList(),
    open val generics: List<Type> = emptyList(),
) : FirstClassDecl(name, modifiers) {
    fun isAnonymous(): Boolean {
        return name is AnonymousIdentifier
    }

    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionDecl(this)
    }

    fun isIntrinsicOverload(): Boolean {
        return name is IntrinsicExpr
    }

    override fun toString(): String {
        val anon = if (isAnonymous()) "_anon_" else ""
        return "Fx(name=$name, mods=${modifiers.ifEmpty { "[]" }}, gens=${generics.ifEmpty { "[]" }}, def=$def${if (anon.isNotEmpty()) ", anon=$anon" else ""})"
    }

    override fun isStub(): Boolean {
        return def.body == null
    }
}