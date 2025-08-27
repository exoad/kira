package net.exoad.kira.compiler.frontend.parser.ast.statements

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

open class Statement(open val expr: Expr) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitStatement(this)
    }

    override fun toString(): String
    {
        return "STMT{ $expr }"
    }
}