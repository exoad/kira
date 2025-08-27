package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier

open class FunctionDeclParameterExpr(
    val name: Identifier,
    val typeSpecifier: TypeSpecifier,
    val modifiers: List<Modifiers> = emptyList(),
) :
    Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionParameterExpr(this)
    }

    override fun toString(): String
    {
        return "FxParam${if(modifiers.isNotEmpty()) "[[ $modifiers ]]" else ""}{ $name -> $typeSpecifier }"
    }
}