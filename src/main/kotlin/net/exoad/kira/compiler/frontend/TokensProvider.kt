package net.exoad.kira.compiler.frontend

object TokensProvider
{
    var tokens: List<Token> = emptyList()
    lateinit var rootASTNode: RootASTNode
}