package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.front.ASTNode
import net.exoad.kira.compiler.front.ASTVisitor

/**
 * Expression implementation
 */
abstract class Expr : ASTNode()

/**
 * A simple expression placeholder value to signify for things like [net.exoad.kira.compiler.front.statements.Statement] that need an [Expr] passed in.
 *
 * It signifies that this is just a single keyword statement
 */
object NoExpr : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitNoExpr(this)
    }

    override fun toString(): String
    {
        return "NoExpr{ }"
    }
}