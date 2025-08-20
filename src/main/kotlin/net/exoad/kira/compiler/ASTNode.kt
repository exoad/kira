package net.exoad.kira.compiler

abstract class ASTNode
{
    abstract fun accept(visitor: ASTVisitor)
}