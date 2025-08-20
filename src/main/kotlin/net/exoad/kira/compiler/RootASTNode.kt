package net.exoad.kira.compiler

class RootASTNode(val statements: List<ASTNode>) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        statements.forEach { it.accept(visitor) }
    }
}