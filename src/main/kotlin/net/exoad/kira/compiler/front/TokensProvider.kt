package net.exoad.kira.compiler.front

/**
 * Global handler for carrying the tokens produced by [KiraLexer] to make them useful and available
 * to other aspects of the compiler, primarily [KiraParser]
 */
object TokensProvider
{
    var tokens: List<Token> = emptyList()
    lateinit var rootASTNode: RootASTNode
}