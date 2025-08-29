package net.exoad.kira.compiler.frontend.parser.ast

abstract class ASTNode {
    abstract fun accept(visitor: KiraASTVisitor)
}