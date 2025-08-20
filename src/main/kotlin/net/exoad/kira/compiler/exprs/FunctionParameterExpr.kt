package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.elements.Identifier
import net.exoad.kira.compiler.elements.Modifiers
import net.exoad.kira.compiler.elements.TypeSpecifier

open class FunctionParameterExpr(val name: Identifier, val typeSpecifier: TypeSpecifier, val modifiers: List<Modifiers> = emptyList()) :
    Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionParameterExpr(this)
    }

    override fun toString(): String
    {
        return "FunctionParameterExpr[[ $modifiers ]]{ $name -> $typeSpecifier }"
    }
}