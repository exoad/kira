package net.exoad.kira.compiler.frontend.parser.ast.literals

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.TypeSpecifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

open class FunctionLiteral(
    // so this is already anonymous by itself ?? in kira parser a raw function literal can be passed anonymously
    open val returnTypeSpecifier: TypeSpecifier,
    open val parameters: List<FunctionDeclParameterExpr>,
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