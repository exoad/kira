package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers
import net.exoad.kira.compiler.front.elements.Type
import net.exoad.kira.compiler.front.exprs.FunctionParameterExpr
import net.exoad.kira.compiler.front.statements.Statement

open class FunctionFirstClassDecl(
    override val name: Identifier,
    open val returnType: Type,
    open val parameters: List<FunctionParameterExpr>,
    open val body: List<Statement>?, // if this is null, then this is just a "noimpl" function
    override val modifiers: List<Modifiers> = emptyList()
) : FirstClassDecl(name, modifiers)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionDecl(this)
    }

    override fun toString(): String
    {
        return "FunctionDecl[[ $modifiers ]]{ $name -> $returnType -> $parameters -> $body}"
    }

    override fun isStub(): Boolean
    {
        return body == null
    }
}