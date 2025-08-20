package net.exoad.kira.compiler.exprs

import net.exoad.kira.compiler.ASTVisitor
import net.exoad.kira.compiler.Token
import net.exoad.kira.compiler.elements.BinaryOp

class CompoundAssignmentExpr(val left: Expr, val operator: BinaryOp, val right: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitCompoundAssignmentExpr(this)
    }

    override fun toString(): String
    {
        return "CompoundAssignmentExpr{ $left $operator $right}"
    }

    companion object
    {
        // we dont use varargs in these kind fo situations because it can make problematic code be problematic at runtime but not at compile time
        fun findBinaryOp(tokenType: Array<Token.Type>): BinaryOp?
        {
            return when
            {
                tokenType.size == 3 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_EQUAL -> BinaryOp.SHR
                tokenType.size == 4 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[3] == Token.Type.S_EQUAL -> BinaryOp.USHR
                tokenType.size == 1                        -> when(tokenType[0])
                {
                    Token.Type.OP_ASSIGN_MUL     -> BinaryOp.MUL
                    Token.Type.OP_ASSIGN_DIV     -> BinaryOp.DIV
                    Token.Type.OP_ASSIGN_ADD     -> BinaryOp.ADD
                    Token.Type.OP_ASSIGN_SUB     -> BinaryOp.SUB
                    Token.Type.OP_ASSIGN_MOD     -> BinaryOp.MOD
                    Token.Type.OP_ASSIGN_BIT_XOR -> BinaryOp.XOR
                    Token.Type.OP_ASSIGN_BIT_SHL -> BinaryOp.SHL
                    Token.Type.OP_ASSIGN_BIT_OR  -> BinaryOp.CONJUNCTIVE_OR
                    Token.Type.OP_ASSIGN_BIT_AND -> BinaryOp.CONJUNCTIVE_AND
                    else                         -> null
                }
                else                                         -> null
            }
        }
    }
}