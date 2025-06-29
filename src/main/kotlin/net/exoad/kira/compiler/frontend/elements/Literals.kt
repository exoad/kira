package net.exoad.kira.compiler.frontend.elements

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode

abstract class LiteralNode<T>(open val value: T) : ExpressionNode()

open class IntegerLiteralNode(override val value: Long) : LiteralNode<Long>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitIntegerLiteral(this)
    }

    override fun toString(): String
    {
        return "LInteger{ $value }"
    }
}

open class FloatLiteralNode(override val value: Double) : LiteralNode<Double>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitFloatLiteral(this)
    }

    override fun toString(): String
    {
        return "LFloat{ $value }"
    }
}

open class StringLiteralNode(override val value: String) : LiteralNode<String>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitStringLiteral(this)
    }

    override fun toString(): String
    {
        return "LString{ $value }"
    }
}

open class BoolLiteralNode(override val value: Boolean) : LiteralNode<Boolean>(value)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBoolLiteral(this)
    }

    override fun toString(): String
    {
        return "LBool{ $value }"
    }
}