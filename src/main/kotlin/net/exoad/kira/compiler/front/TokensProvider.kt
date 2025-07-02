package net.exoad.kira.compiler.front

object TokensProvider
{
    var tokens: List<Token> = emptyList()
    lateinit var rootASTNode: RootASTNode
}