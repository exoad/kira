package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier

open class WithExpr(val members: List<WithExprMember>) :
    Expr()
{ // the value of the map represent everything on the right side of the key (aka the identifier) or WithMemberExpr
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitWithExpr(this)
    }

    override fun toString(): String
    {
        return "With{ $members }"
    }
}

open class WithExprMember(val name: Identifier, val value: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitWithExprMember(this)
    }

    override fun toString(): String
    {
        return "WithMember{ $name -> $value }"
    }
}