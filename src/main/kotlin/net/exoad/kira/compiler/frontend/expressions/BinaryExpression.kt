package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.Token

class BinaryExpressionNode(
    val leftExpression: ExpressionNode,
    val rightExpression: ExpressionNode,
    val operator: BinaryOperator
) : ExpressionNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBinaryExpression(this)
    }

    override fun toString(): String
    {
        return "BinaryExpression{ $leftExpression ${operator.symbol} $rightExpression }"
    }
}

enum class BinaryOperator(val symbol: Symbols, val tokenType: Token.Type, val precedence: Int)
{
    ADD(Symbols.PLUS, Token.Type.OP_ADD, 1),
    SUB(Symbols.HYPHEN, Token.Type.OP_SUB, 1),
    MUL(Symbols.ASTERISK, Token.Type.OP_MUL, 2),
    DIV(Symbols.SLASH, Token.Type.OP_DIV, 2),
    MOD(Symbols.PERCENT, Token.Type.OP_MOD, 2);

    companion object
    {
        fun byTokenType(tokenType: Token.Type): BinaryOperator
        {
            return when(tokenType)
            {
                Token.Type.OP_ADD -> ADD
                Token.Type.OP_SUB -> SUB
                Token.Type.OP_MUL -> MUL
                Token.Type.OP_DIV -> DIV
                Token.Type.OP_MOD -> MOD
                else              -> Diagnostics.panic(
                    "BinaryOperator::byTokenType",
                    "$tokenType is not a binary operator!"
                )
            }
        }

        fun byTokenTypeMaybe(tokenType: Token.Type): BinaryOperator?
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

