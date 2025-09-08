package net.exoad.kira.compiler.frontend.parser.ast

interface ASTNode {
    fun accept(visitor: KiraASTVisitor)
}