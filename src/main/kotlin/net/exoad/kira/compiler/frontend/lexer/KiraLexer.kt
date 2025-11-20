package net.exoad.kira.compiler.frontend.lexer

import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.core.Keywords
import net.exoad.kira.core.Symbols
import net.exoad.kira.core.isHexChar
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import kotlin.properties.Delegates

/**
 * The lexers turns the input string received from [net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor] into [Token]s
 * and assigns them based on the symbol.
 *
 * It passes this list of symbols onto the [net.exoad.kira.compiler.frontend.parser.KiraParser]
 */
class KiraLexer(private val context: SourceContext) {
    private val buffer = CharacterBuffer(context.content)

    // it is ill-advised to modify any of these on their owns
    private var pointer = 0
    private var lineNumber = 1
    private var column = 1

    /**
     * Advances [pointer] to the next character in [context] and updates [lineNumber] and [column]
     */
    fun advancePointer() {
        when (peek()) {
            Symbols.NEWLINE.rep -> {
                lineNumber++
                column = 1
            }

            else -> column++
        }
        pointer++
        buffer.advance()
    }

    fun skipWhitespace() {
        while (peek() != Symbols.NULL.rep && peek().isWhitespace()) {
            advancePointer()
        }
    }

    /**
     * Grabs the [k]th token away from [pointer] as a relative offset.
     */
    fun peek(k: Int = 0): Char {
        return buffer.peek(k)
    }

    private fun lexHexNumberLiteral(): Token {
        val start = pointer
        val startLoc = SourcePosition(lineNumber, column)
        while (peek().isHexChar()) {
            advancePointer()
        }
        var content by Delegates.notNull<String>()
        try {
            content = context.content.substring(start, pointer).toInt(16).toString(10) // check
        } catch (_: Exception) {
            Diagnostics.panic(
                "KiraLexer::lexHexNumberLiteral",
                "'$content' is not a valid hex literal.",
                location = startLoc,
                selectorLength = content.length,
                context = context
            )
        }
        return Token.Raw(Token.Type.L_INTEGER, content, start, startLoc)
    }

    /**
     * Maximal Munch approach to reading floating point and integer types by preferring
     * reading floating point first
     */
    fun lexNumberLiteral(): Token {
        if (peek() == '0' && peek(1) == 'x') // hex parsing!
        {
            advancePointer()
            advancePointer()
            return lexHexNumberLiteral()
        }
        val start = pointer
        val startLoc = SourcePosition(lineNumber, column)
        var isFloat = false
        while (peek().isDigit()) {
            advancePointer()
        }
        if (peek() == Symbols.PERIOD.rep) {
            val afterDot = peek(1)
            if (afterDot.isDigit()) {
                isFloat = true
                advancePointer()
                while (peek().isDigit()) {
                    advancePointer()
                }
            }
        }
        val content = context.content.substring(start, pointer)
        return when {
            isFloat -> Token.Raw(Token.Type.L_FLOAT, content, start, startLoc)
            else -> Token.Raw(Token.Type.L_INTEGER, content, start, startLoc)
        }
    }

    fun lexStringLiteral(): Token {
        val start = pointer
        val startLoc = SourcePosition(lineNumber, column)
        advancePointer() // skip opening "
        val contentStart = pointer
        while (peek() != Symbols.NULL.rep && peek() != Symbols.DOUBLE_QUOTE.rep && peek() != '\n') {
            // escaped sequences are passed "as is" to the parser
            advancePointer()
        }
        if (peek() != Symbols.DOUBLE_QUOTE.rep) {
            Diagnostics.panic(
                "KiraLexer::lexStringLiteral",
                buildString {
                    append("Unterminated string at ")
                    append(pointer)
                    append(". Insert '")
                    append(Symbols.DOUBLE_QUOTE.rep)
                    append("' to terminate it")
                },
                location = startLoc,
                context = context
            )
        }
        val content = String(context.content.toCharArray(), contentStart, pointer - contentStart)
        advancePointer()
        return Token.Raw(Token.Type.L_STRING, content, start, startLoc)
    }

    fun lexIdentifier(): Token {
        val start = pointer
        val startLoc = SourcePosition(lineNumber, column)
        while (peek() != Symbols.NULL.rep) {
            val c = peek()
            if (c.isLetterOrDigit()) {
                advancePointer()
                continue
            }
            if (c == Symbols.UNDERSCORE.rep) {
                if (isInIntrinsic) {
                    advancePointer()
                    continue
                } else {
                    Diagnostics.panic(
                        "KiraLexer::lexIdentifier",
                        "Underscores are not allowed in identifiers. Only intrinsics may contain underscores; use camelCase or PascalCase for identifiers.",
                        location = startLoc,
                        selectorLength = pointer - start,
                        context = context
                    )
                }
            }
            break
        }
        return Token.Raw(Token.Type.IDENTIFIER, context.content.substring(start, pointer), start, startLoc)
    }

    private val pendingClosingAngleBrackets: ArrayDeque<Token> = ArrayDeque()

    private var isInIntrinsic = false

    fun nextToken(): Token {
        if (pendingClosingAngleBrackets.isNotEmpty()) {
            return pendingClosingAngleBrackets.removeFirst()
        }
        while (peek() != Symbols.NULL.rep) {
            skipWhitespace()
            if (peek() == Symbols.NULL.rep) {
                return Token.Symbol(Token.Type.S_EOF, Symbols.NULL, pointer, SourcePosition(lineNumber, column))
            }
            val char = peek()
            val start = pointer

            /**
             * A version of [peek] but for the local context. [k] is a relative offset
             */
            fun localPeek(k: Int): Char {
                val index = k + start
                return when (index < context.content.length) {
                    true -> context.content[index]
                    else -> Diagnostics.panic(
                        "KiraLexer::nextToken::localPeek",
                        "Read pointer went out of content bounds ${context.content.length}",
                        context = context
                    )
                }
            }

            val startLoc = SourcePosition(lineNumber, column)
            if (char.isLetter() || (isInIntrinsic && char == Symbols.UNDERSCORE.rep)) {  // identifiers and keywords usually have the same stuffs
                val identifier = lexIdentifier()
                val keywordTokenType = Keywords.reserved[identifier.content]
                if (keywordTokenType != null) {
                    return Token.Raw(
                        keywordTokenType,
                        identifier.content,
                        identifier.pointerPosition,
                        identifier.canonicalLocation
                    )
                }
                // defensive fallback for keywords that may not be present due to build ordering or map issues
                when (identifier.content) {
                    "try" -> return Token.Raw(
                        Token.Type.K_TRY,
                        identifier.content,
                        identifier.pointerPosition,
                        identifier.canonicalLocation
                    )

                    "throw" -> return Token.Raw(
                        Token.Type.K_THROW,
                        identifier.content,
                        identifier.pointerPosition,
                        identifier.canonicalLocation
                    )

                    "on" -> return Token.Raw(
                        Token.Type.K_ON,
                        identifier.content,
                        identifier.pointerPosition,
                        identifier.canonicalLocation
                    )

                    else -> return identifier
                }
            }
            if (char.isDigit()) {
                return lexNumberLiteral()
            }
            if (char == Symbols.DOUBLE_QUOTE.rep) {
                return lexStringLiteral()
            }
            advancePointer()
            if (char == Symbols.AT.rep) {
                if (!localPeek(1).isLetter() && localPeek(1) != Symbols.UNDERSCORE.rep) {
                    Diagnostics.panic(
                        "KiraLexer::nextToken",
                        "Expected identifier after '@' for intrinsic marking.",
                        location = startLoc,
                        context = context
                    )
                }
                // consume the character already advanced (the '@' was consumed by advancePointer above)
                // now lex the following identifier characters (letters, digits, underscores)
                val identStart = pointer
                val identStartLoc = SourcePosition(lineNumber, column)
                while (localPeek(pointer - start).isLetterOrDigit() || localPeek(pointer - start) == Symbols.UNDERSCORE.rep) {
                    advancePointer()
                }
                val content = context.content.substring(identStart, pointer)
                return Token.Raw(Token.Type.INTRINSIC_IDENTIFIER, content, identStart, identStartLoc)
            }
            // i want to say this when statement looks great, but like man covering conditional, is just pure hell to my eyes to look at.
            //
            // but aye it is straightforward. there is no real need to modularize it further
            //
            // it could be troublesome especially when we need to distinguish multiple occurring characters apart from other ones
            //
            // by this mean i mean like distinguishing the closing angle brackets of something like:
            //
            //          Array<Array<Array<Array<Int32>>>>
            //
            // the last 4 '>' with a naive lexer would be easily mismarked as just either bitwise ushr or just 2 shr or 4 grt. its just a pain
            // sometimes to cover and coalesce these lexical tokens together (I JUST WISH WE HAD MORE KEYS TO WORK WITH, no i dont lol)
            return when (char) {
                // we only allow intrinsics to start with this character, so we parse regularly

                Symbols.OPEN_ANGLE.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_LEQ,
                                arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        Symbols.OPEN_ANGLE.rep -> {
                            advancePointer()
                            when (localPeek(2)) {
                                Symbols.EQUALS.rep -> {
                                    advancePointer()
                                    Token.LinkedSymbols(
                                        Token.Type.OP_ASSIGN_BIT_SHL,
                                        arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE, Symbols.EQUALS),
                                        start,
                                        startLoc
                                    )
                                }

                                else -> Token.LinkedSymbols(
                                    Token.Type.OP_BIT_SHL,
                                    arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE),
                                    start,
                                    startLoc
                                )
                            }
                        }

                        else -> Token.Symbol(
                            Token.Type.S_OPEN_ANGLE,
                            Symbols.OPEN_ANGLE,
                            start,
                            startLoc
                        )
                    }

                Symbols.CLOSE_ANGLE.rep -> Token.Symbol(
                    Token.Type.S_CLOSE_ANGLE,
                    Symbols.CLOSE_ANGLE,
                    start,
                    startLoc
                )

                Symbols.HASH_MARK.rep -> Token.Symbol(
                    Token.Type.OP_HASH_MARK,
                    Symbols.HASH_MARK,
                    start,
                    startLoc
                )

                Symbols.COLON.rep ->
                    when (localPeek(1)) {
                        Symbols.COLON.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_SCOPE,
                                arrayOf(Symbols.COLON, Symbols.COLON),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(
                            Token.Type.S_COLON,
                            Symbols.COLON,
                            start,
                            startLoc
                        )
                    }

                Symbols.QUESTION_MARK.rep -> Token.Symbol(
                    Token.Type.S_QUESTION_MARK,
                    Symbols.QUESTION_MARK,
                    start,
                    startLoc
                )

                Symbols.UNDERSCORE.rep -> Token.Symbol(
                    Token.Type.S_UNDERSCORE,
                    Symbols.UNDERSCORE,
                    start,
                    startLoc
                )

                Symbols.EXCLAMATION.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_NEQ,
                                arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.S_BANG, Symbols.EXCLAMATION, start, startLoc)
                    }

                Symbols.PLUS.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_ADD, arrayOf(
                                    Symbols.PLUS,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.OP_ADD, Symbols.PLUS, start, startLoc)
                    }

                Symbols.HYPHEN.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_SUB, arrayOf(
                                    Symbols.HYPHEN,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.OP_SUB, Symbols.HYPHEN, start, startLoc)
                    }

                Symbols.ASTERISK.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_MUL, arrayOf(
                                    Symbols.ASTERISK,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.OP_MUL, Symbols.ASTERISK, start, startLoc)
                    }

                Symbols.SLASH.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_DIV, arrayOf(
                                    Symbols.SLASH,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.OP_DIV, Symbols.SLASH, start, startLoc)
                    }

                Symbols.PERCENT.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_MOD, arrayOf(
                                    Symbols.PERCENT,
                                    Symbols.EQUALS
                                ), start, startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.OP_MOD, Symbols.PERIOD, start, startLoc)
                    }

                Symbols.OPEN_BRACE.rep -> Token.Symbol(
                    Token.Type.S_OPEN_BRACE,
                    Symbols.OPEN_BRACE,
                    start,
                    startLoc
                )

                Symbols.PERIOD.rep ->
                    when (localPeek(1)) {
                        Symbols.PERIOD.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_RANGE,
                                arrayOf(Symbols.PERIOD, Symbols.PERIOD),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.S_DOT, Symbols.PERIOD, start, startLoc)
                    }

                Symbols.COMMA.rep -> Token.Symbol(
                    Token.Type.S_COMMA,
                    Symbols.COMMA,
                    start,
                    startLoc
                )

                Symbols.PERCENT.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_MOD,
                                arrayOf(Symbols.PERCENT, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(
                            Token.Type.OP_MOD,
                            Symbols.PERCENT,
                            start,
                            startLoc
                        )
                    }

                Symbols.CARET.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_BIT_XOR,
                                arrayOf(Symbols.CARET, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(
                            Token.Type.OP_BIT_XOR,
                            Symbols.CARET,
                            start,
                            startLoc
                        )
                    }

                Symbols.PIPE.rep ->
                    when (localPeek(1)) {
                        Symbols.PIPE.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_OR,
                                arrayOf(Symbols.PIPE, Symbols.PIPE),
                                start,
                                startLoc
                            )
                        }

                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_BIT_OR,
                                arrayOf(Symbols.PIPE, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(Token.Type.S_PIPE, Symbols.PIPE, start, startLoc)
                    }

                Symbols.AMPERSAND.rep ->
                    when (localPeek(1)) {
                        Symbols.AMPERSAND.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_CMP_AND,
                                arrayOf(Symbols.AMPERSAND, Symbols.AMPERSAND),
                                start,
                                startLoc
                            )
                        }

                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            Token.LinkedSymbols(
                                Token.Type.OP_ASSIGN_BIT_AND,
                                arrayOf(Symbols.AMPERSAND, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else -> Token.Symbol(
                            Token.Type.S_AND,
                            Symbols.AMPERSAND,
                            start,
                            startLoc
                        )
                    }

                Symbols.CLOSE_BRACE.rep -> Token.Symbol(
                    Token.Type.S_CLOSE_BRACE,
                    Symbols.CLOSE_BRACE,
                    start,
                    startLoc
                )

                Symbols.OPEN_BRACKET.rep -> Token.Symbol(
                    Token.Type.S_OPEN_BRACKET,
                    Symbols.OPEN_BRACKET,
                    start,
                    startLoc
                )

                Symbols.CLOSE_BRACKET.rep -> Token.Symbol(
                    Token.Type.S_CLOSE_BRACKET,
                    Symbols.CLOSE_BRACKET,
                    start,
                    startLoc
                )

                Symbols.TILDE.rep -> Token.Symbol(
                    Token.Type.S_TILDE,
                    Symbols.TILDE,
                    start,
                    startLoc
                )

                Symbols.OPEN_PARENTHESIS.rep -> Token.Symbol(
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

                Symbols.NEWLINE.rep -> Token.Symbol( // treat new lines as optional semicolons or statement delimiters ;) just like kotlin!
                    Token.Type.S_SEMICOLON,
                    Symbols.NEWLINE, // just an adhoc case
                    start,
                    startLoc
                )

                Symbols.STATEMENT_DELIMITER.rep -> Token.Symbol(
                    Token.Type.S_SEMICOLON,
                    Symbols.STATEMENT_DELIMITER,
                    start,
                    startLoc
                )

                Symbols.EQUALS.rep ->
                    when (localPeek(1)) {
                        Symbols.EQUALS.rep -> {
                            advancePointer()
                            return Token.LinkedSymbols(
                                Token.Type.OP_CMP_EQL,
                                arrayOf(Symbols.EQUALS, Symbols.EQUALS),
                                start,
                                startLoc
                            )
                        }

                        else ->
                            return Token.Symbol(Token.Type.S_EQUAL, Symbols.EQUALS, start, startLoc)
                    }

                else -> Diagnostics.panic(
                    "KiraLexer::nextToken",
                    "Symbol '$char' is not known at Line $lineNumber, Column $column",
                    location = startLoc,
                    context = context
                )
            }
        }
        return Token.Symbol(Token.Type.S_EOF, Symbols.NULL, pointer, SourcePosition(lineNumber, column))
    }

    fun tokenize(): List<Token> {
        val res = mutableListOf<Token>()
        var token by Delegates.notNull<Token>()
        do {
            token = nextToken()
            res.add(token)
        } while (token.type != Token.Type.S_EOF)
        return res
    }
}