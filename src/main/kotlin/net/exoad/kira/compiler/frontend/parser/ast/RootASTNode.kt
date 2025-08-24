package net.exoad.kira.compiler.frontend.parser.ast

class RootASTNode(val statements: List<ASTNode>) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        statements.forEach { it.accept(visitor) }
    }
}