package net.exoad.kira.compiler.frontend

import net.exoad.kira.Keywords
import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.frontend.KiraLexer.column
import net.exoad.kira.compiler.frontend.KiraLexer.lineNumber
import net.exoad.kira.compiler.frontend.KiraLexer.peek
import net.exoad.kira.compiler.frontend.KiraLexer.pointer

data class FileLocation(val lineNumber: Int, val column: Int)
{
    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
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
        L_INTEGER,
        L_STRING,
        L_FLOAT,
        L_TRUE_BOOL,
        L_FALSE_BOOL,
        IDENTIFIER,
        OP_ADD("'+' (Plus)"),
        OP_SUB("'-' (Minus)"),
        OP_MUL("'*' (Multiply)"),
        OP_DIV("'/' (Divide)"),
        OP_MOD("'%' (Modulo)"),
        OP_ASSIGN("'=' (Assignment)"),
        OP_CMP_LTE("'<=' (Less Than Or Equal To)"),
        OP_CMP_GTE("'>=' (Greater Than Or Equal To)"),
        OP_CMP_EQL("'==' (Equals To)"),
        OP_CMP_NEQ("'!=' (Not Equals To)"),
        K_IF("'if'"),
        K_ELSE("'else'"),
        K_WHILE("'while'"),
        K_DO("'do'"),
        S_OPEN_PARENTHESIS("'(' (Opening Parenthesis)"), // opening
        S_CLOSE_PARENTHESIS("')' (Closing Parenthesis)"), // closing
        S_OPEN_BRACE("'{' (Opening Brace)"), // opening
        S_CLOSE_BRACE("'}' (Closing Brace)"), //closing
        S_OPEN_ANGLE("'<' (Opening Angle Bracket)"),
        S_CLOSE_ANGLE("'>' (Closing Angle Bracket)"),
        S_COLON("':' (Colon)"),
        S_SEMICOLON("';' (Semicolon)"),
        S_EOF,
        S_BANG("'!' (Bang)"),
        S_DOT("'.' (Dot)"),
        S_COMMA("',' (Comma)"),
        ;

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
                    OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD, OP_CMP_NEQ, OP_CMP_GTE, OP_CMP_LTE, OP_CMP_EQL, S_OPEN_ANGLE, S_CLOSE_ANGLE -> true
                    else                                                                                                                -> false
                }
            }

            fun isLiteral(token: Type): Boolean
            {
                return token.name.startsWith("L_")
            }
        }
    }

    class Raw(type: Type, rawString: String, pointerPosition: Int, canonicalLocation: FileLocation) :
        Token(type, rawString, pointerPosition, canonicalLocation)

    class Symbol(type: Type, symbol: Symbols, pointerPosition: Int, canonicalLocation: FileLocation) :
        Token(type, symbol.rep.toString(), pointerPosition, canonicalLocation)

    class LinkedSymbols(type: Type, symbols: Array<Symbols>, pointerPosition: Int, canonicalLocation: FileLocation) :
        Token(type, symbols.map { it.rep }.joinToString(""), pointerPosition, canonicalLocation)

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
object KiraLexer
{
    private var pointer = 0
    private var lineNumber = 1
    private var column = 1
    private var underPointer: Char =
            if(SrcProvider.srcContent.isEmpty()) Symbols.NULL.rep else SrcProvider.srcContent.first()

    /**
     * Advances [pointer] to the next character in [SrcProvider.srcContent] and updates [lineNumber] and [column]
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
        underPointer =
                if(pointer >= SrcProvider.srcContent.length) Symbols.NULL.rep else SrcProvider.srcContent[pointer]
    }

    fun skipWhitespace()
    {
        while(underPointer != Symbols.NULL.rep && underPointer.isWhitespace())
        {
            advancePointer()
        }
    }

    /**
     * Grabs the [k]th token away from [pointer] as a relative offset.
     */
    fun peek(k: Int = 0): Char
    {
        val index = pointer + k
        return if(index < SrcProvider.srcContent.length)
        {
            SrcProvider.srcContent[index]
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
        // TODO: add hex support
        val start = pointer
        val startLoc = FileLocation(lineNumber, column)
        var isFloat = false
        while(underPointer.isDigit())
        {
            advancePointer()
        }
        if(underPointer == Symbols.PERIOD.rep)
        {
            val afterDot = peek(1)
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
        val content = SrcProvider.srcContent.substring(start, pointer)
        return if(isFloat)
        {
            Token.Raw(Token.Type.L_FLOAT, content, start, startLoc)
        }
        else
        {
            Token.Raw(Token.Type.L_INTEGER, content, start, startLoc)
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
            // escaped sequences are passed "as is" to the parser
            buffer.append(underPointer)
            advancePointer()
        }
        if(underPointer == Symbols.DOUBLE_QUOTE.rep)
        {
            advancePointer()
        }
        else
        {
            Diagnostics.panic(
                "KiraLexer::lexStringLiteral",
                "Unterminated string at $pointer. Insert '${Symbols.DOUBLE_QUOTE.rep}' to terminate it",
                location = startLoc
            )
        }
        return Token.Raw(Token.Type.L_STRING, buffer.toString(), start, startLoc)
    }

    fun lexIdentifier(): Token
    {
        val start = pointer
        val startLoc = FileLocation(lineNumber, column)
        while(underPointer != Symbols.NULL.rep && (underPointer.isLetterOrDigit() || underPointer == Symbols.UNDERSCORE.rep))
        {
            advancePointer()
        }
        return Token.Raw(Token.Type.IDENTIFIER, SrcProvider.srcContent.substring(start, pointer), start, startLoc)
    }

    fun nextToken(): Token
    {
        while(underPointer != Symbols.NULL.rep)
        {
            skipWhitespace()
            if(underPointer == Symbols.NULL.rep)
            {
                return Token.Symbol(Token.Type.S_EOF, Symbols.NULL, pointer, FileLocation(lineNumber, column))
            }
            val char = underPointer
            val start = pointer

            /**
             * A version of [peek] but for the local context. [k] is a relative offset
             */
            fun localPeek(k: Int): Char
            {
                val index = k + start
                return if(index < SrcProvider.srcContent.length)
                {
                    SrcProvider.srcContent[index]
                }
                else
                {
                    Symbols.NULL.rep
                }
            }

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
                Symbols.OPEN_ANGLE.rep        ->
                    if(localPeek(1) == Symbols.EQUALS.rep)
                    {
                        advancePointer()
                        return Token.LinkedSymbols(
                            Token.Type.OP_CMP_LTE,
                            arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS),
                            start,
                            startLoc
                        )
                    }
                    else
                    {
                        return Token.Symbol(Token.Type.S_OPEN_ANGLE, Symbols.OPEN_ANGLE, start, startLoc)
                    }
                Symbols.CLOSE_ANGLE.rep       ->
                    if(localPeek(1) == Symbols.EQUALS.rep)
                    {
                        advancePointer()
                        return Token.LinkedSymbols(
                            Token.Type.OP_CMP_GTE,
                            arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS),
                            start,
                            startLoc
                        )
                    }
                    else
                    {
                        return Token.Symbol(Token.Type.S_CLOSE_ANGLE, Symbols.CLOSE_ANGLE, start, startLoc)
                    }
                Symbols.COLON.rep             -> Token.Symbol(
                    Token.Type.S_COLON,
                    Symbols.COLON,
                    start,
                    startLoc
                )
                Symbols.EXCLAMATION.rep       ->
                    if(localPeek(1) == Symbols.EQUALS.rep)
                    {
                        advancePointer()
                        return Token.LinkedSymbols(
                            Token.Type.OP_CMP_NEQ,
                            arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS),
                            start,
                            startLoc
                        )
                    }
                    else
                    {
                        return Token.Symbol(Token.Type.S_BANG, Symbols.EXCLAMATION, start, startLoc)
                    }
                Symbols.PLUS.rep              -> Token.Symbol(Token.Type.OP_ADD, Symbols.PLUS, start, startLoc)
                Symbols.HYPHEN.rep            -> Token.Symbol(Token.Type.OP_SUB, Symbols.HYPHEN, start, startLoc)
                Symbols.ASTERISK.rep          -> Token.Symbol(Token.Type.OP_MUL, Symbols.ASTERISK, start, startLoc)
                Symbols.SLASH.rep             -> Token.Symbol(Token.Type.OP_DIV, Symbols.SLASH, start, startLoc)
                Symbols.PERCENT.rep           -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERCENT, start, startLoc)
                Symbols.OPEN_BRACE.rep        -> Token.Symbol(
                    Token.Type.S_OPEN_BRACE,
                    Symbols.OPEN_BRACE,
                    start,
                    startLoc
                )
                Symbols.PERIOD.rep            -> Token.Symbol(Token.Type.S_DOT, Symbols.PERIOD, start, startLoc)
                Symbols.COMMA.rep             -> Token.Symbol(Token.Type.S_COMMA, Symbols.COMMA, start, startLoc)
                Symbols.PERCENT.rep           -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERCENT, start, startLoc)
                Symbols.CLOSE_BRACE.rep       -> Token.Symbol(
                    Token.Type.S_CLOSE_BRACE,
                    Symbols.CLOSE_BRACE,
                    start,
                    startLoc
                )
                Symbols.OPEN_PARENTHESIS.rep  -> Token.Symbol(
                    Token.Type.S_OPEN_PARENTHESIS,
                    Symbols.OPEN_PARENTHESIS,
                    start,
                    startLoc
                )
                Symbols.CLOSE_PARENTHESIS.rep -> Token.Symbol(
                    Token.Type.S_CLOSE_PARENTHESIS,
                    Symbols.CLOSE_PARENTHESIS,
                    start,
                    startLoc
                )
                Symbols.SEMICOLON.rep         -> Token.Symbol(
                    Token.Type.S_SEMICOLON,
                    Symbols.SEMICOLON,
                    start,
                    startLoc
                )
                Symbols.EQUALS.rep            ->
                    if(localPeek(1) == Symbols.EQUALS.rep)
                    {
                        advancePointer()
                        return Token.LinkedSymbols(
                            Token.Type.OP_CMP_EQL,
                            arrayOf(Symbols.EQUALS, Symbols.EQUALS),
                            start,
                            startLoc
                        )
                    }
                    else
                    {
                        return Token.Symbol(Token.Type.OP_ASSIGN, Symbols.EQUALS, start, startLoc)
                    }
                else                          -> Diagnostics.panic(
                    "KiraLexer::nextToken",
                    "Token '$char' is not known at Line $lineNumber, Column $column",
                    location = startLoc
                )
            }
        }
        return Token.Symbol(Token.Type.S_EOF, Symbols.NULL, pointer, FileLocation(lineNumber, column))
    }

    fun tokenize()
    {
        val res = mutableListOf<Token>()
        lateinit var token: Token
        do
        {
            token = nextToken()
            res.add(token)
        } while(token.type != Token.Type.S_EOF)
        TokensProvider.tokens = res
    }
}
