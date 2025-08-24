package net.exoad.kira.compiler.frontend.parser

import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.core.Symbols
import net.exoad.kira.source.FileLocation

class TokenBuffer(
    private val tokens: List<Token>,
    private val windowSize: Int = 16,
)
{
    private val window = Array<Token?>(windowSize) { null }
    private val windowMask = windowSize - 1
    private var position = 0
    private val eofToken = Token.Symbol(Token.Type.S_EOF, Symbols.NULL, 0, FileLocation(1, 1))
    private var windowFilled = 0

    init
    {
        require(windowSize in 4..64) { "Window size must be between 4 and 64!" }
        require(windowSize and (windowSize - 1) == 0) { "Window size must be a power of 2!" }
        fillInitialWindow()
    }

    private fun fillInitialWindow()
    {
        val tokensToLoad = minOf(windowSize, tokens.size)
        for(i in 0 until tokensToLoad)
        {
            window[i] = tokens[i]
        }
        windowFilled = tokensToLoad
    }

    fun peek(offset: Int = 0): Token
    {
        require(offset >= 0) { "Negative offset not supported: $offset" }
        require(offset < windowSize) {
            "Offset $offset exceeds window size $windowSize"
        }
        val absoluteIndex = position + offset
        if(absoluteIndex >= tokens.size)
        {
            return eofToken
        }
        val cachedToken = window[absoluteIndex and windowMask]
        if(cachedToken != null && absoluteIndex < position + windowFilled)
        {
            return cachedToken
        }
        return loadToken(absoluteIndex)
    }

    private fun loadToken(absoluteIndex: Int): Token
    {
        if(absoluteIndex >= tokens.size)
        {
            return eofToken
        }
        val token = tokens[absoluteIndex]
        window[absoluteIndex and windowMask] = token
        return token
    }

    fun advance(): Token
    {
        val currentToken = peek(0)
        if(position < tokens.size)
        {
            position++
            val nextTokenIndex = position + windowSize - 1
            if(nextTokenIndex < tokens.size)
            {
                window[nextTokenIndex and windowMask] = tokens[nextTokenIndex]
            }
        }

        return currentToken
    }

    fun advance(count: Int): Token
    {
        require(count > 0) { "Advancement count must be positive! Got: $count" }
        val oldPosition = position
        position = minOf(position + count, tokens.size)
        if(count >= windowSize / 2)
        {
            reloadWindow()
        }
        else
        {
            for(i in 1..count)
            {
                val nextTokenIndex = oldPosition + windowSize - 1 + i
                if(nextTokenIndex < tokens.size)
                {
                    val windowIndex = nextTokenIndex and windowMask
                    window[windowIndex] = tokens[nextTokenIndex]
                }
            }
        }
        return peek(0)
    }

    private fun reloadWindow()
    {
        window.fill(null)
        val startIndex = position
        val endIndex = minOf(position + windowSize, tokens.size)
        for(i in startIndex until endIndex)
        {
            window[i and windowMask] = tokens[i]
        }
        windowFilled = endIndex - startIndex
    }

    fun getCurrentPosition(): Int
    {
        return position
    }

    fun isAtEnd(): Boolean
    {
        return position >= tokens.size
    }

    fun current(): Token
    {
        return peek(0)
    }

    fun createCheckpoint(): TokenBufferCheckpoint
    {
        return TokenBufferCheckpoint(position)
    }

    fun restoreCheckpoint(checkpoint: TokenBufferCheckpoint): Boolean
    {
        val targetPosition = checkpoint.position
        val positionDiff = position - targetPosition
        if(positionDiff >= 0 && positionDiff < windowSize)
        {
            position = targetPosition
            return true
        }
        return false
    }

    fun getDebugInfo(): String = buildString {
        appendLine("TokenBuffer Debug Info:")
        appendLine("  Position: $position")
        appendLine("  Window Size: $windowSize")
        appendLine("  Tokens Total: ${tokens.size}")
        appendLine("  Window Filled: $windowFilled")
        appendLine("  At End: ${isAtEnd()}")
        appendLine("  Current Token: ${current().content} (${current().type})")
        appendLine("  Window Contents:")
        for(i in 0 until windowSize)
        {
            appendLine("    [$i]: ${window[i]?.content ?: "null"} (${window[i]?.type ?: "null"})${if(i == (position and windowMask)) " <-- CURRENT" else ""}")
        }
    }
}

data class TokenBufferCheckpoint(val position: Int)
