package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.FunctionParameterExpr
import net.exoad.kira.compiler.front.statements.Statement

open class FunctionLiteral(
    open val returnTypeSpecifier: TypeSpecifier,
    open val parameters: List<FunctionParameterExpr>,
    open val body: List<Statement>?, // if this is null, then this is just a "noimpl" function
) : Literal()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFunctionLiteral(this)
    }

    override fun toString(): String
    {
        return "LFx{ $returnTypeSpecifier -> $parameters -> $body }"
    }
}