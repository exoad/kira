package net.exoad.kira.compiler.frontend

import net.exoad.kira.Keywords
import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics

data class FileLocation(val lineNumber: Int, val column: Int)
{
    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column" }
    }

    fun toConciseString(): String
    {
        return "$lineNumber:$column"
    }

    override fun toString(): String
    {
        return "Line $lineNumber, Column $column"
    }
}

/**
 * Semantical tokens representing each part of text that was parsed
 */
sealed class Token(val type: Type, val content: String, val pointerPosition: Int, val canonicalLocation: FileLocation)
{
    enum class Type(val rawDiagnosticsRepresentation: String? = null)
    {
        INTEGER_LITERAL,
        STRING_LITERAL,
        FLOAT_LITERAL,
        BOOL_TRUE_LITERAL,
        BOOL_FALSE_LITERAL,
        EOF,
        DOT("'.' (Dot)"),
        IDENTIFIER,
        OP_ADD("'+' (Plus)"),
        OP_SUB("'-' (Minus)"),
        OP_MUL("'*' (Multiply)"),
        OP_DIV("'/' (Divide)"),
        OP_MOD("'%' (Modulo)"),
        OP_ASSIGN("'=' (Assignment)"),
        K_IF("'if'"),
        K_ELSE("'else'"),
        K_WHILE("'while'"),
        L_PAREN("'(' (Opening Parenthesis)"), // opening
        R_PAREN("')' (Closing Parenthesis"), // closing
        L_BRACE("'{' (Opening Braces)"), // opening
        R_BRACE("'}' (Closing Braces)"), //closing
        TYPE_ANNOTATION("':' (Type Annotator)"),
        STATEMENT_DELIMITER("';' (Semicolon)");

        fun diagnosticsName(): String
        {
            return rawDiagnosticsRepresentation ?: name
        }

        companion object
        {
            fun isBinaryOperator(token: Type): Boolean
            {
                return when(token)
                {
                    OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD -> true
                    else                                   -> false
                }
            }

            fun isLiteral(token: Type): Boolean
            {
                return when(token)
                {
                    STRING_LITERAL, INTEGER_LITERAL -> true
                    else                            -> false
                }
            }
        }
    }

    class Raw(type: Type, rawString: String, pointerPosition: Int, canonicalLocation: FileLocation) :
        Token(type, rawString, pointerPosition, canonicalLocation)

    class Symbol(type: Type, symbol: Symbols, pointerPosition: Int, canonicalLocation: FileLocation) :
        Token(type, symbol.rep.toString(), pointerPosition, canonicalLocation)

    override fun toString(): String
    {
        return String.format(
            "%-${26}s %-${32}s %${5}d",
            type.name,
            "'$content'",
            pointerPosition
        )
    }
}

/**
 * The lexers turns the input string received from [KiraPreprocessor] into [Token]s
 * and assigns them based on the symbol.
 *
 * It passes this list of symbols onto the [KiraParser]
 */
open class KiraLexer(val readBuffer: String)
{
    private var pointer = 0
    private var lineNumber = 1
    private var column = 1
    private var underPointer: Char = if(readBuffer.isEmpty()) Symbols.NULL.rep else readBuffer.first()

    /**
     * Advances [pointer] to the next character in [readBuffer] and updates [lineNumber] and [column]
     */
    fun advancePointer()
    {
        if(underPointer == '\n')
        {
            lineNumber++
            column = 1
        }
        else
        {
            column++
        }
        pointer++
        underPointer = if(pointer >= readBuffer.length) Symbols.NULL.rep else readBuffer[pointer]
    }

    fun skipWhitespace()
    {
        while(underPointer != Symbols.NULL.rep && underPointer.isWhitespace())
        {
            advancePointer()
        }
    }

    fun peekNext(): Char
    {
        return if(pointer + 1 < readBuffer.length)
        {
            readBuffer[pointer + 1]
        }
        else
        {
            Symbols.NULL.rep
        }
    }

    /**
     * Maximal Munch approach to reading floating point and integer types by preferring
     * reading floating point first
     */
    fun lexNumberLiteral(): Token
    {
        val start = pointer
        val startLoc = FileLocation(lineNumber, column)
        var isFloat = false
        while(underPointer.isDigit())
        {
            advancePointer()
        }
        if(underPointer == Symbols.PERIOD.rep)
        {
            val afterDot = peekNext()
            if(afterDot.isDigit())
            {
                isFloat = true
                advancePointer()
                while(underPointer.isDigit())
                {
                    advancePointer()
                }
            }
        }
        // TODO: add exponent notation ?
        val content = readBuffer.substring(start, pointer)
        return if(isFloat)
        {
            Token.Raw(Token.Type.FLOAT_LITERAL, content, start, startLoc)
        }
        else
        {
            Token.Raw(Token.Type.INTEGER_LITERAL, content, start, startLoc)
        }
    }

    fun lexStringLiteral(): Token
    {
        val start = pointer
        val startLoc = FileLocation(lineNumber, column)
        advancePointer() // skip opening "
        val buffer = StringBuilder()
        while(underPointer != Symbols.NULL.rep && underPointer != Symbols.DOUBLE_QUOTE.rep && underPointer != '\n')
        {
            if(underPointer != Symbols.BACK_SLASH.rep)
            {
                buffer.append(underPointer)
                advancePointer()
            }
            else
            {
                Diagnostics.panic("KiraLexer::lexStringLiteral", "Escaped characters are not yet supported!")
            }
        }
        if(underPointer == Symbols.DOUBLE_QUOTE.rep)
        {
            advancePointer()
        }
        else
        {
            Diagnostics.panic(
                "KiraLexer::lexStringLiteral",
                "Unterminated string at $pointer. Insert '${Symbols.DOUBLE_QUOTE.rep}' to terminate it"
            )
        }
        return Token.Raw(Token.Type.STRING_LITERAL, buffer.toString(), start, startLoc)
    }

    fun lexIdentifier(): Token
    {
        val start = pointer
        val startLoc = FileLocation(lineNumber, column)
        while(underPointer != Symbols.NULL.rep && (underPointer.isLetterOrDigit() || underPointer == Symbols.UNDERSCORE.rep))
        {
            advancePointer()
        }
        return Token.Raw(Token.Type.IDENTIFIER, readBuffer.substring(start, pointer), start, startLoc)
    }

    fun nextToken(): Token
    {
        while(underPointer != Symbols.NULL.rep)
        {
            skipWhitespace()
            if(underPointer == Symbols.NULL.rep)
            {
                return Token.Symbol(Token.Type.EOF, Symbols.NULL, pointer, FileLocation(lineNumber, column))
            }
            val char = underPointer
            val start = pointer
            val startLoc = FileLocation(lineNumber, column)
            if(char.isLetter() || char == Symbols.UNDERSCORE.rep) // identifiers and keywords usually have the same stuffs
            {
                val identifier = lexIdentifier()
                val keywordTokenType = Keywords.common[identifier.content]
                return if(keywordTokenType != null)
                {
                    Token.Raw(
                        keywordTokenType,
                        identifier.content,
                        identifier.pointerPosition,
                        identifier.canonicalLocation
                    )
                }
                else
                {
                    identifier
                }
            }
            if(char.isDigit())
            {
                return lexNumberLiteral()
            }
            if(char == Symbols.DOUBLE_QUOTE.rep)
            {
                return lexStringLiteral()
            }
            advancePointer()
            return when(char)
            {
                Symbols.COLON.rep             -> Token.Symbol(
                    Token.Type.TYPE_ANNOTATION,
                    Symbols.COLON,
                    start,
                    startLoc
                )
                Symbols.PLUS.rep              -> Token.Symbol(Token.Type.OP_ADD, Symbols.PLUS, start, startLoc)
                Symbols.HYPHEN.rep            -> Token.Symbol(Token.Type.OP_SUB, Symbols.HYPHEN, start, startLoc)
                Symbols.ASTERISK.rep          -> Token.Symbol(Token.Type.OP_MUL, Symbols.ASTERISK, start, startLoc)
                Symbols.SLASH.rep             -> Token.Symbol(Token.Type.OP_DIV, Symbols.SLASH, start, startLoc)
                Symbols.PERCENT.rep           -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERCENT, start, startLoc)
                Symbols.OPEN_BRACE.rep        -> Token.Symbol(Token.Type.L_BRACE, Symbols.OPEN_BRACE, start, startLoc)
                Symbols.PERIOD.rep            -> Token.Symbol(Token.Type.DOT, Symbols.PERIOD, start, startLoc)
                Symbols.PERCENT.rep           -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERCENT, start, startLoc)
                Symbols.CLOSE_BRACE.rep       -> Token.Symbol(Token.Type.R_BRACE, Symbols.CLOSE_BRACE, start, startLoc)
                Symbols.OPEN_PARENTHESIS.rep  -> Token.Symbol(
                    Token.Type.L_PAREN,
                    Symbols.OPEN_PARENTHESIS,
                    start,
                    startLoc
                )
                Symbols.CLOSE_PARENTHESIS.rep -> Token.Symbol(
                    Token.Type.R_PAREN,
                    Symbols.CLOSE_PARENTHESIS,
                    start,
                    startLoc
                )
                Symbols.SEMICOLON.rep         -> Token.Symbol(
                    Token.Type.STATEMENT_DELIMITER,
                    Symbols.SEMICOLON,
                    start,
                    startLoc
                )
                Symbols.EQUALS.rep            -> Token.Symbol(Token.Type.OP_ASSIGN, Symbols.EQUALS, start, startLoc)
                else                          -> Diagnostics.panic(
                    "KiraLexer::nextToken",
                    "Token '$char' is not known at Line $lineNumber, Column $column"
                )
            }
        }
        return Token.Symbol(Token.Type.EOF, Symbols.NULL, pointer, FileLocation(lineNumber, column))
    }

    fun tokenize(): List<Token>
    {
        val res = mutableListOf<Token>()
        lateinit var token: Token
        do
        {
            token = nextToken()
            res.add(token)
        } while(token.type != Token.Type.EOF)
        return res
    }
}
