package net.exoad.kira.compiler.front

class RootASTNode(val statements: List<ASTNode>) : ASTNode()
{
    override fun accept(visitor: ASTVisitor)
    {
        statements.forEach { it.accept(visitor) }
    }
}