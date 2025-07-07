package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.FunctionParameterExpr
import net.exoad.kira.compiler.front.statements.Statement

open class AnonymousFunction(
    // so this is already anonymous by itself ?? in kira parser a raw function literal can be passed anonymously
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
        return "LFunc{ $returnTypeSpecifier -> $parameters -> $body }"
    }
}