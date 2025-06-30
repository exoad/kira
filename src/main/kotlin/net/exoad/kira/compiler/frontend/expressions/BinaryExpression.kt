package net.exoad.kira.compiler.frontend.expressions

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.ASTVisitor
import net.exoad.kira.compiler.frontend.ExpressionNode
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

// precedence guide: https://images.hive.blog/DQmZkdJ5YJGuQzyZvBW1gLRaKwJrKLiSNWkETb8AyBw4Z5A/image.png
enum class BinaryOperator(val symbol: Array<Symbols>, val tokenType: Token.Type, val precedence: Int)
{
    // arithmetic operators
    ADD(arrayOf(Symbols.PLUS), Token.Type.OP_ADD, 4),
    SUB(arrayOf(Symbols.HYPHEN), Token.Type.OP_SUB, 4),
    MUL(arrayOf(Symbols.ASTERISK), Token.Type.OP_MUL, 5),
    DIV(arrayOf(Symbols.SLASH), Token.Type.OP_DIV, 5),
    MOD(arrayOf(Symbols.PERCENT), Token.Type.OP_MOD, 5),

    // logical operators
    EQL(arrayOf(Symbols.EQUALS, Symbols.EQUALS), Token.Type.OP_CMP_EQL, 2),
    NEQ(arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS), Token.Type.OP_CMP_NEQ, 2),
    GTE(arrayOf(Symbols.CLOSE_ANGLE, Symbols.EQUALS), Token.Type.OP_CMP_GTE, 3),
    LTE(arrayOf(Symbols.OPEN_BRACE, Symbols.EQUALS), Token.Type.OP_CMP_LTE, 3),
    GRT(arrayOf(Symbols.CLOSE_ANGLE), Token.Type.S_CLOSE_ANGLE, 3),
    LST(arrayOf(Symbols.OPEN_ANGLE), Token.Type.S_OPEN_ANGLE, 3)
    ;

    companion object
    {
        fun byTokenType(tokenType: Token.Type): BinaryOperator
        {
            return when(tokenType)
            {
                Token.Type.OP_ADD        -> ADD
                Token.Type.OP_SUB        -> SUB
                Token.Type.OP_MUL        -> MUL
                Token.Type.OP_DIV        -> DIV
                Token.Type.OP_MOD        -> MOD
                Token.Type.OP_CMP_EQL    -> EQL
                Token.Type.OP_CMP_NEQ    -> NEQ
                Token.Type.OP_CMP_LTE    -> LTE
                Token.Type.OP_CMP_GTE    -> GTE
                Token.Type.S_OPEN_ANGLE  -> LST
                Token.Type.S_CLOSE_ANGLE -> GRT
                else                     -> Diagnostics.panic(
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

