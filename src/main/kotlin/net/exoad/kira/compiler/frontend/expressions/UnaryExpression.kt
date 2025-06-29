package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.compiler.frontend.Token

class UnaryExpressionNode(val operator: UnaryOperator, val operand: ExpressionNode) : ExpressionNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitUnaryExpression(this)
    }

    override fun toString(): String
    {
        return "UnaryExpression{ $operator -> $operand }"
    }
}

enum class UnaryOperator(val symbol: Symbols, val tokenType: Token.Type, val precedence: Int)
{
    NEG(Symbols.HYPHEN, Token.Type.OP_SUB, 3),
    POS(Symbols.PLUS, Token.Type.OP_ADD, 3);

    companion object
    {
        fun byTokenType(tokenType: Token.Type): UnaryOperator
        {
            return when(tokenType)
            {
                Token.Type.OP_SUB -> NEG
                Token.Type.OP_ADD -> POS
                else              -> Diagnostics.panic(
                    "UnaryOperator::byTokenType",
                    "$tokenType is not an unary operator!"
                )
            }
        }

        fun byTokenTypeMaybe(tokenType: Token.Type): UnaryOperator?
        {
            return try
            {
                byTokenType(tokenType)
            }
            catch(ignored: Exception)
            {
                null
            }
        }
    }
}