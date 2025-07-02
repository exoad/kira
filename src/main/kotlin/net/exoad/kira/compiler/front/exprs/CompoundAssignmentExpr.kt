package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.Token

class CompoundAssignmentExpr(val left: Expr, val operator: BinaryOp, val right: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
    }

    override fun toString(): String
    {
        return "CompoundAssignmentExpr{ $left $operator $right}"
    }

    companion object
    {
        fun findBinaryOp(tokenType: Token.Type): BinaryOp
        {
            return when(tokenType)
            {
                Token.Type.OP_ASSIGN_MUL      -> BinaryOp.MUL
                Token.Type.OP_ASSIGN_DIV      -> BinaryOp.DIV
                Token.Type.OP_ASSIGN_ADD      -> BinaryOp.ADD
                Token.Type.OP_ASSIGN_SUB      -> BinaryOp.SUB
                Token.Type.OP_ASSIGN_MOD      -> BinaryOp.MOD
                Token.Type.OP_ASSIGN_BIT_XOR  -> BinaryOp.BIT_XOR
                Token.Type.OP_ASSIGN_BIT_SHL  -> BinaryOp.BIT_SHL
                Token.Type.OP_ASSIGN_BIT_OR   -> BinaryOp.CONJUNCTIVE_OR
                Token.Type.OP_ASSIGN_BIT_AND  -> BinaryOp.CONJUNCTIVE_AND
                Token.Type.OP_ASSIGN_BIT_SHR  -> BinaryOp.BIT_SHR
                Token.Type.OP_ASSIGN_BIT_USHR -> BinaryOp.BIT_USHR
                else                          -> Diagnostics.panic(
                    "[[ BUG! ]]: $tokenType is not a valid compound assignment " +
                            "operator"
                ) // should never happen, since this would be an internal error
            }
        }
    }
}