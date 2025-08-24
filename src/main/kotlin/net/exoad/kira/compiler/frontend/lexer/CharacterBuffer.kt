package net.exoad.kira.compiler.frontend.lexer

class CharacterBuffer(content: String)
{
    private val chars = (content + '\u0000').toCharArray()
    private var position = 0
    val current: Char get() = chars[position]
    val isAtEnd: Boolean get() = chars[position] == '\u0000'

    fun advance(): Char
    {
        val char = chars[position]
        if(char != '\u0000') position++
        return char
    }

    fun peek(offset: Int = 0): Char
    {
        return chars[position + offset]
    }
}