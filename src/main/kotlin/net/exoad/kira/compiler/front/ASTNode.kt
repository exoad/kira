package net.exoad.kira.compiler.front

abstract class ASTNode
{
    abstract fun accept(visitor: ASTVisitor)
}