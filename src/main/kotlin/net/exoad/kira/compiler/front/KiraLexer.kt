package net.exoad.kira.compiler.front

import net.exoad.kira.Keywords
import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.KiraLexer.column
import net.exoad.kira.compiler.front.KiraLexer.lineNumber
import net.exoad.kira.compiler.front.KiraLexer.peek
import net.exoad.kira.compiler.front.KiraLexer.pointer

data class FileLocation(val lineNumber: Int, val column: Int)
{
    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    override fun toString(): String
    {
        return "line $lineNumber, col $column"
    }
}

data class AbsoluteFileLocation(val lineNumber: Int, val column: Int, val srcFile: String)
{
    init
    {
        assert(lineNumber > 0) { "Line Number must be greater than 0 (BAD: $lineNumber)" }
        assert(column > 0) { "Column Number must be greater than 0 (BAD: $column)" }
    }

    fun toNormalFileLocation(): FileLocation
    {
        return FileLocation(lineNumber, column)
    }

    override fun toString(): String
    {
        return "[$srcFile : line $lineNumber, col $column"
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
        L_NULL,
        IDENTIFIER,
        OP_ADD("'+' (Plus)"),
        OP_SUB("'-' (Minus)"),
        OP_MUL("'*' (Multiply)"),
        OP_DIV("'/' (Divide)"),
        OP_MOD("'%' (Modulo)"),
        OP_ASSIGN("'=' (Assignment)"),
        OP_ASSIGN_ADD("'+=' (Compound Addition Assignment)"),
        OP_ASSIGN_SUB("'-=' (Compound Subtraction Assignment)"),
        OP_ASSIGN_MUL("'*=' (Compound Multiplication Assignment)"),
        OP_ASSIGN_DIV("'/=' (Compound Division Assignment)"),
        OP_ASSIGN_MOD("'%=' (Compound Modulus Assignment)"),
        OP_ASSIGN_BIT_OR("'|=' (Compound Bitwise OR Assignment)"),
        OP_ASSIGN_BIT_AND("'&=' (Compound Bitwise AND Assignment)"),
        OP_ASSIGN_BIT_SHL("'<<=' (Compound Bitwise Left Shift Assignment)"),
        OP_ASSIGN_BIT_SHR("'>>=' (Compound Bitwise Right Shift Assignment)"),
        OP_ASSIGN_BIT_USHR("'>>>=' (Compound Bitwise Unsigned Right Shift Assignment)"),
        OP_ASSIGN_BIT_XOR("'^=' (Compound Bitwise XOR Assignment)"),
        OP_CMP_LEQ("'<=' (Less Than Or Equal To)"),
        OP_CMP_GEQ("'>=' (Greater Than Or Equal To)"),
        OP_CMP_EQL("'==' (Equals To)"),
        OP_CMP_NEQ("'!=' (Not Equals To)"),
        OP_CMP_AND("'&&' (Logical AND)"),
        OP_CMP_OR("'||' (Logical OR)"),
        OP_BIT_SHL("'<<' (Bitwise Shift Left)"),
        OP_BIT_SHR("'>>' (Bitwise Shift Right"),
        OP_BIT_USHR("'>>>' (Bitwise Unsigned Shift Right"),
        OP_BIT_XOR("'^' (Bitwise XOR"),
        K_IF("'if'"),
        K_ELSE("'else'"),
        K_WHILE("'while'"),
        K_DO("'do'"),
        K_MUTABLE("'mut'"),
        S_OPEN_PARENTHESIS("'(' (Opening Parenthesis)"), // opening
        S_CLOSE_PARENTHESIS("')' (Closing Parenthesis)"), // closing
        S_OPEN_BRACE("'{' (Opening Brace)"), // opening
        S_CLOSE_BRACE("'}' (Closing Brace)"), //closing
        S_OPEN_ANGLE("'<' (Opening Angle Bracket)"),
        S_CLOSE_ANGLE("'>' (Closing Angle Bracket)"),
        S_COLON("':' (Colon)"),
        S_SEMICOLON("';' (Semicolon)"),
        S_AND("'&' (And)"),
        S_PIPE("'|' (Pipe)"),
        S_EOF,
        S_AT("'@' (At)"),
        S_BANG("'!' (Bang)"),
        S_DOT("'.' (Dot)"),
        S_TILDE("'~' (Tilde)"),
        S_COMMA("',' (Comma)"),
        ;

        fun diagnosticsName(): String
        {
            return rawDiagnosticsRepresentation ?: name
        }

        companion object
        {
            /**
             * [net.exoad.kira.compiler.front.exprs.CompoundAssignmentExpr]
             */
            val compoundAssignmentTokens = arrayOf(
                OP_ASSIGN_ADD,
                OP_ASSIGN_SUB,
                OP_ASSIGN_MUL,
                OP_ASSIGN_DIV,
                OP_ASSIGN_MOD,
                OP_ASSIGN_BIT_OR,
                OP_ASSIGN_BIT_AND,
                OP_ASSIGN_BIT_SHL,
                OP_ASSIGN_BIT_SHR,
                OP_ASSIGN_BIT_USHR,
                OP_ASSIGN_BIT_XOR,
            )

            fun isBinaryOperator(token: Type): Boolean
            {
                return when(token)
                {
                    OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD,
                    OP_CMP_NEQ, OP_CMP_GEQ, OP_CMP_LEQ, OP_CMP_EQL,
                    S_OPEN_ANGLE, S_CLOSE_ANGLE,
                    OP_CMP_AND, OP_CMP_OR,
                    OP_BIT_XOR, OP_BIT_USHR, OP_BIT_SHL, OP_BIT_SHR,
                    S_AND, S_PIPE,
                         -> true
                    else -> false
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
 * The lexers turns the input string received from [net.exoad.kira.compiler.preprocessor.KiraPreprocessor] into [Token]s
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
        when(underPointer)
        {
            '\n' ->
            {
                lineNumber++
                column = 1
            }
            else -> column++
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
        return when
        {
            index < SrcProvider.srcContent.length -> SrcProvider.srcContent[index]
            else                                  -> Symbols.NULL.rep
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
        return when
        {
            isFloat -> Token.Raw(Token.Type.L_FLOAT, content, start, startLoc)
            else    -> Token.Raw(Token.Type.L_INTEGER, content, start, startLoc)
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
        when(underPointer)
        {
            Symbols.DOUBLE_QUOTE.rep -> advancePointer()
            else                     -> Diagnostics.panic(
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
                return when(index < SrcProvider.srcContent.length)
                {
                    true -> SrcProvider.srcContent[index]
                    else -> Symbols.NULL.rep
                }
            }

            val startLoc = FileLocation(lineNumber, column)
            if(char.isLetter() || char == Symbols.UNDERSCORE.rep) // identifiers and keywords usually have the same stuffs
            {
                val identifier = lexIdentifier()
                val keywordTokenType = Keywords.common[identifier.content]
                return when
                {
                    keywordTokenType != null ->
                    {
                        Token.Raw(
                            keywordTokenType,
                            identifier.content,
                            identifier.pointerPosition,
                            identifier.canonicalLocation
                        )
                    }
                    else                     -> identifier
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
                Symbols.AT.rep                -> Token.Symbol(Token.Type.S_AT, Symbols.AT, start, startLoc)
                Symbols.OPEN_ANGLE.rep        ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep     ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_LEQ,
                                arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }
                        Symbols.OPEN_ANGLE.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_BIT_SHL,
                                arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE),
                                start,
                                startLoc
                            )
                        }
                        else                   -> Token.Symbol(
                            Token.Type.S_OPEN_ANGLE,
                            Symbols.OPEN_ANGLE,
                            start,
                            startLoc
                        )
                    }
                Symbols.CLOSE_ANGLE.rep       ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep      ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_GEQ,
                                arrayOf(Symbols.CLOSE_ANGLE, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }
                        Symbols.CLOSE_ANGLE.rep ->
                        {
                            advancePointer()
                            return when(localPeek(2))
                            {
                                Symbols.CLOSE_ANGLE.rep ->
                                { // support bitwise unsigned right shift
                                    advancePointer()
                                    Token.LinkedSymbols(
                                        Token.Type.OP_BIT_USHR,
                                        arrayOf(Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE),
                                        start, startLoc
                                    )
                                }
                                else                    -> Token.LinkedSymbols(
                                    Token.Type.OP_BIT_SHR,
                                    arrayOf(Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE),
                                    start,
                                    startLoc
                                )
                            }
                        }
                        else                    -> Token.Symbol(
                            Token.Type.S_CLOSE_ANGLE,
                            Symbols.CLOSE_ANGLE,
                            start,
                            startLoc
                        )
                    }
                Symbols.COLON.rep             -> Token.Symbol(
                    Token.Type.S_COLON,
                    Symbols.COLON,
                    start,
                    startLoc
                )
                Symbols.EXCLAMATION.rep       ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_NEQ,
                                arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.S_BANG, Symbols.EXCLAMATION, start, startLoc)
                    }
                Symbols.PLUS.rep              ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_ADD, arrayOf(
                                    Symbols.PLUS,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.OP_ADD, Symbols.PLUS, start, startLoc)
                    }
                Symbols.HYPHEN.rep            ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_SUB, arrayOf(
                                    Symbols.HYPHEN,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.OP_SUB, Symbols.HYPHEN, start, startLoc)
                    }
                Symbols.ASTERISK.rep          ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_MUL, arrayOf(
                                    Symbols.ASTERISK,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.OP_MUL, Symbols.ASTERISK, start, startLoc)
                    }
                Symbols.SLASH.rep             ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_DIV, arrayOf(
                                    Symbols.SLASH,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.OP_DIV, Symbols.SLASH, start, startLoc)
                    }
                Symbols.PERCENT.rep           ->
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_MOD, arrayOf(
                                    Symbols.PERCENT,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }
                        else               -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERIOD, start, startLoc)
                    }
                Symbols.OPEN_BRACE.rep        -> Token.Symbol(
                    Token.Type.S_OPEN_BRACE,
                    Symbols.OPEN_BRACE,
                    start,
                    startLoc
                )
                Symbols.PERIOD.rep            -> Token.Symbol(Token.Type.S_DOT, Symbols.PERIOD, start, startLoc)
                Symbols.COMMA.rep             -> Token.Symbol(
                    Token.Type.S_COMMA,
                    Symbols.COMMA,
                    start,
                    startLoc
                )
                Symbols.PERCENT.rep           -> Token.Symbol(
                    Token.Type.OP_MOD,
                    Symbols.PERCENT,
                    start,
                    startLoc
                )
                Symbols.CARET.rep             -> Token.Symbol(
                    Token.Type.OP_BIT_XOR,
                    Symbols.CARET,
                    start,
                    startLoc
                )
                Symbols.PIPE.rep              ->
                    when(localPeek(1))
                    {
                        Symbols.PIPE.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_OR,
                                arrayOf(Symbols.PIPE, Symbols.PIPE),
                                start,
                                startLoc
                            )
                        }
                        else             -> Token.Symbol(Token.Type.S_PIPE, Symbols.PIPE, start, startLoc)
                    }
                Symbols.AMPERSAND.rep         ->
                    when(localPeek(1))
                    {
                        Symbols.AMPERSAND.rep ->
                        {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_AND,
                                arrayOf(Symbols.AMPERSAND, Symbols.AMPERSAND),
                                start,
                                startLoc
                            )
                        }
                        else                  -> Token.Symbol(
                            Token.Type.S_AND,
                            Symbols.AMPERSAND,
                            start,
                            startLoc
                        )
                    }
                Symbols.CLOSE_BRACE.rep       -> Token.Symbol(
                    Token.Type.S_CLOSE_BRACE,
                    Symbols.CLOSE_BRACE,
                    start,
                    startLoc
                )
                Symbols.TILDE.rep             -> Token.Symbol(
                    Token.Type.S_TILDE,
                    Symbols.TILDE,
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
                    when(localPeek(1))
                    {
                        Symbols.EQUALS.rep ->
                        {
                            advancePointer()
                            return Token.LinkedSymbols(
                                Token.Type.OP_CMP_EQL,
                                arrayOf(Symbols.EQUALS, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }
                        else               ->
                            return Token.Symbol(Token.Type.OP_ASSIGN, Symbols.EQUALS, start, startLoc)
                    }
                else                          -> Diagnostics.panic(
                    "KiraLexer::nextToken",
                    "Symbol '$char' is not known at Line $lineNumber, Column $column",
                    location = startLoc,
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
