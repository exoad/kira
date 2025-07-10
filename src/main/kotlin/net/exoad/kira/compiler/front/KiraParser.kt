package net.exoad.kira.compiler.front

import net.exoad.kira.Builtin
import net.exoad.kira.Keywords
import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.Intrinsic
import net.exoad.kira.compiler.SourceContext
import net.exoad.kira.compiler.front.elements.*
import net.exoad.kira.compiler.front.exprs.*
import net.exoad.kira.compiler.front.exprs.decl.ClassDecl
import net.exoad.kira.compiler.front.exprs.decl.Decl
import net.exoad.kira.compiler.front.exprs.decl.EnumDecl
import net.exoad.kira.compiler.front.exprs.decl.FirstClassDecl
import net.exoad.kira.compiler.front.exprs.decl.FunctionDecl
import net.exoad.kira.compiler.front.exprs.decl.ModuleDecl
import net.exoad.kira.compiler.front.exprs.decl.ObjectDecl
import net.exoad.kira.compiler.front.exprs.decl.VariableDecl
import net.exoad.kira.compiler.front.statements.*
import kotlin.properties.Delegates

/**
 * stack based prediction for use with [KiraParser] to help with making predicting ambiguous grammar easier, cleaner, and optimized
 */
class PredictionStack(private val parser: KiraParser)
{
    private val stack = mutableListOf<ParserState>() // state snapshots
    private val predictionCache = mutableMapOf<String, PredictionResult<*>>()

    fun push(): PredictionContext
    {
        val currentState = save()
        stack.add(currentState)
        return PredictionContext(this, currentState)
    }

    fun pop(): ParserState?
    {
        return when
        {
            stack.isNotEmpty() -> stack.removeAt(stack.size - 1)
            else               -> null
        }
    }

    fun peek(): ParserState?
    {
        return stack.lastOrNull()
    }

    /**
     * saves the current parser state
     */
    fun save(): ParserState
    {
        return ParserState(
            pointer = parser.pointer,
            underPointer = parser.underPointer,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * restores the parser to a previous state
     */
    fun restore(state: ParserState)
    {
        parser.pointer = state.pointer
        parser.underPointer = state.underPointer
        parser.updateUnderPointer()
    }

    fun getCurrentState(): ParserState
    {
        return save()
    }

    /**
     * evaluates a block with auto state management
     */
    fun <T> evaluate(block: () -> T): T
    {
        val context = push()
        return try
        {
            val result = block()
            context.commit()
            result
        }
        catch(e: Exception)
        {
            context.rollback()
            throw e
        }
        finally
        {
            pop()
        }
    }

    /**
     * returns the first successful candidate out of multiple
     */
    fun <T> tryInOrder(candidates: List<() -> T>, onError: (KiraRuntimeException) -> T): T
    {
        val context = push()
        for(candidate in candidates)
        {
            try
            {
                val result = candidate()
                context.commit()
                return result
            }
            catch(_: Exception)
            {
                context.rollback()
            }
        }
        // everything failed here
        pop()
        return onError(KiraRuntimeException("All prediction candidates failed"))
    }

    /**
     * executes a block with error recovery capabilities
     */
    fun <T> withRecovery(block: () -> T): T
    {
        val startState = save()
        return try
        {
            block()
        }
        catch(e: Exception)
        {
            restore(startState)
            throw KiraRuntimeException("Parse error with recovery", e)
        }
    }

    /**
     * tries a single candidate and returns the result
     */
    fun <T> tryCandidate(candidate: () -> T): PredictionResult<T>
    {
        val startState = save()
        return try
        {
            val result = candidate()
            val endState = save()
            PredictionResult.Success(result, endState)
        }
        catch(e: Exception)
        {
            val errorState = save()
            restore(startState)
            PredictionResult.Failure(e, errorState)
        }
    }

    fun binaryOp(): Array<Token.Type>?
    {
        return tryInOrder(
            listOf(
                {
                    // >>>= (ushr assign)
                    when
                    {
                        parser.peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(2).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(3).type == Token.Type.OP_ASSIGN
                             -> arrayOf(
                            Token.Type.S_CLOSE_ANGLE,
                            Token.Type.S_CLOSE_ANGLE,
                            Token.Type.S_CLOSE_ANGLE,
                            Token.Type.OP_ASSIGN
                        )
                        else -> null
                    }
                },
                {
                    // >>> (ushr)
                    when
                    {
                        parser.peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(2).type == Token.Type.S_CLOSE_ANGLE
                             -> arrayOf(Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE)
                        else -> null
                    }
                },
                {
                    // >>= (shr assign)
                    when
                    {
                        parser.peek(0).type == Token.Type.S_CLOSE_ANGLE && parser.peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                                parser.peek(2).type == Token.Type.OP_ASSIGN
                             -> arrayOf(Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN)
                        else -> null
                    }
                },
                {
                    // >> (shr)
                    when
                    {
                        parser.peek(0).type == Token.Type.S_CLOSE_ANGLE && parser.peek(1).type == Token.Type.S_CLOSE_ANGLE -> arrayOf(
                            Token.Type.S_CLOSE_ANGLE,
                            Token.Type.S_CLOSE_ANGLE
                        )
                        else                                                                                               -> null
                    }
                },
                {
                    // >= (geq)
                    when
                    {
                        parser.peek(0).type == Token.Type.S_CLOSE_ANGLE && parser.peek(1).type == Token.Type.OP_ASSIGN -> arrayOf(
                            Token.Type.S_CLOSE_ANGLE,
                            Token.Type.OP_ASSIGN
                        )
                        else                                                                                           -> null
                    }
                },
                {
                    // > (gte)
                    when(parser.peek(0).type)
                    {
                        Token.Type.S_CLOSE_ANGLE -> arrayOf(Token.Type.S_CLOSE_ANGLE)
                        else                     -> null
                    }
                }
            )) { null }
    }

    fun clearCache()
    {
        predictionCache.clear()
    }

    /**
     * for debugging
     */
    fun getCacheStats(): String
    {
        return "PredictionCache: ${predictionCache.size} entries"
    }
}

class PredictionContext(
    private val stack: PredictionStack,
    private val initialState: ParserState,
)
{
    private var committed = false
    private var rolledBack = false

    /**
     * tries a greedy approach by consuming [candidates] until the first successful one (not null)
     */
    fun <T> findFirstSuccess(candidates: List<() -> T>): T?
    {
        for(candidate in candidates)
        {
            val result = stack.tryCandidate(candidate)
            if(result.isSuccess())
            {
                return result.getMaybe()
            }
        }
        return null
    }

    /**
     * tries multiple [candidates] and uses a scoring function to find the best one
     */
    fun <T> findBestMatch(candidates: List<() -> T>, scorer: (T) -> Int): T?
    {
        var bestResult: T? = null
        var bestScore = Int.MIN_VALUE
        for(candidate in candidates)
        {
            val result = stack.tryCandidate(candidate)
            if(result.isSuccess())
            {
                val value = result.getMaybe()!!
                val score = scorer(value)
                if(score > bestScore)
                {
                    bestScore = score
                    bestResult = value
                }
            }
        }

        return bestResult
    }

    /**
     * tries a greedy approach by checking all [candidates] and returns the one that consumes the most tokens
     */
    fun <T> findLongestMatch(candidates: List<() -> T>): T?
    {
        var bestResult: T? = null
        var maxTokensConsumed = -1
        for(candidate in candidates)
        {
            val startState = stack.save()
            val result = stack.tryCandidate(candidate)
            if(result.isSuccess())
            {
                val tokensConsumed = stack.getCurrentState().pointer - startState.pointer
                if(tokensConsumed > maxTokensConsumed)
                {
                    maxTokensConsumed = tokensConsumed
                    bestResult = result.getMaybe()
                }
            }
        }
        return bestResult
    }

    /**
     * rollback to the initial state
     */
    fun rollback()
    {
        if(committed)
        {
            throw IllegalStateException("Cannot rollback after commit")
        }
        stack.restore(initialState)
        rolledBack = true
    }

    /**
     * commits to the current state, this means you cannot roll back
     */
    fun commit()
    {
        if(rolledBack)
        {
            throw IllegalStateException("Cannot commit after rollback")
        }
        committed = true
    }

    fun isCommitted(): Boolean
    {
        return committed
    }

    fun isRolledBack(): Boolean
    {
        return rolledBack
    }
}

// class for handling if something failed or not
sealed class PredictionResult<T>(open val state: ParserState)
{
    data class Success<T>(val value: T, override val state: ParserState) : PredictionResult<T>(state)
    data class Failure<T>(val error: Exception, override val state: ParserState) : PredictionResult<T>(state)

    fun isSuccess(): Boolean
    {
        return this is Success
    }

    fun isFailure(): Boolean
    {
        return this is Failure
    }

    fun getMaybe(): T?
    {
        return when(this)
        {
            is Success -> value
            is Failure -> null
        }
    }

    fun getOrThrow(): T
    {
        return when(this)
        {
            is Success -> value
            is Failure -> throw error
        }
    }
}

// holds a snapshot of a parser state (literally where the parser was at some point in time
// useful for implementing rollbacks in predictive parsing
data class ParserState(
    val pointer: Int,
    val underPointer: Token,
    val timestamp: Long = System.currentTimeMillis(),
)
{
    fun isValid(): Boolean
    {
        return pointer >= 0 && underPointer.type != Token.Type.S_EOF
    }

    /**
     * check if `this` is a state that is younger than [other]
     */
    fun isAheadOf(other: ParserState): Boolean
    {
        return this.pointer > other.pointer
    }

    /**
     * grabs the lexical token distance away from [other]
     */
    fun distanceFrom(other: ParserState): Int
    {
        return kotlin.math.abs(this.pointer - other.pointer)
    }

    override fun toString(): String
    {
        return "ParserState{ pointer=$pointer, token=${underPointer.type}:'${underPointer.content}' }"
    }
}

/**
 * A semi-naive LL(k) parser. It takes the tokens generated by the [KiraLexer] and uses those to turn them into an AST.
 *
 * Parsing also uses a prediction stack in order to optimize ambiguous grammar by trying multiple
 * approaches for the encountered tokens.
 *
 * It will then pass this AST onto the [KiraSemanticAnalyzer] to make sure the AST is
 * valid grammar.
 */
class KiraParser(private val context: SourceContext)
{
    internal var pointer: Int = 0
    internal var underPointer: Token =
        context.tokens.firstOrNull() ?: Token.Symbol(Token.Type.S_EOF, Symbols.NULL, 0, FileLocation(1, 1))

    private val predictionStack = PredictionStack(this)

    internal fun updateUnderPointer()
    {
        underPointer = when
        {
            pointer < context.tokens.size -> context.tokens[pointer]
            else                          -> Token.Symbol(Token.Type.S_EOF, Symbols.NULL, 0, FileLocation(1, 1))
        }
    }

//    init
//    {
//        underPointer = if(tokens.isEmpty()) Token.Symbol(
//            Token.Type.S_EOF,
//            Symbols.NULL,
//            0,
//            FileLocation(1, 1)
//        )
//        else tokens.first()
//    }

    /**
     * Star method to call on [KiraParser] that will turn the passed in [context] into an AST.
     */
    fun parse(): RootASTNode
    {
        val statements = mutableListOf<Statement>()
        while(underPointer.type != Token.Type.S_EOF)
        {
            statements.add(parseStatement(null))
        }
        if(statements.first().expr !is ModuleDecl)
        {
            Diagnostics.panic(
                "KiraParser::parse",
                "The first declaration of ${context.file} must be a module declaration!\nInstead, I got a ${statements.first()::class.simpleName}",
                context = context
            )
        }
        return RootASTNode(statements)
    }

    /**
     * Grabs the token at the [k]-th position (absolute).
     *
     * If you need relative positioning, take a look at [peek]
     */
    fun look(k: Int): Token
    {
        val index = pointer + k - 1
        return when
        {
            index < context.tokens.size -> context.tokens[index]
            else                        -> Token.Symbol(Token.Type.S_EOF, Symbols.NULL, 0, FileLocation(1, 1))
        }
    }

    /**
     * Grabs the token [k] away from [pointer] (relative).
     *
     * If you need absolute positioning, take a look at [look]
     */
    fun peek(k: Int = 0): Token
    {
        val index = pointer + k
        return when
        {
            index < context.tokens.size -> context.tokens[index]
            else                        -> Token.Symbol(Token.Type.S_EOF, Symbols.NULL, 0, FileLocation(1, 1))
        }
    }

    /**
     * Moves the pointer forward to the next token and thus "consumes" the current token
     */
    fun advancePointer()
    {
        pointer++
        underPointer = when
        {
            pointer < context.tokens.size -> context.tokens[pointer]
            else                          -> Token.Symbol(
                Token.Type.S_EOF, Symbols.NULL, 0,
                FileLocation(1, 1)
            )
        }
    }

    fun expectOptionalThenAdvance(token: Token.Type, ifOk: () -> Unit = { advancePointer() })
    {
        if(underPointer.type == token)
        {
            ifOk()
        }
    }

    private fun expectModifiers(
        modifiers: Map<Modifiers, FileLocation>?,
        scopes: Modifiers.Context,
    )
    {
        val r = if(modifiers == null || modifiers.isEmpty()) null
        else modifiers.keys.toList().find { !it.context.contains(scopes) }
        if(r != null)
        {
            Diagnostics.panic(
                "KiraParser::expectModifiers",
                buildString {
                    append("The modifier ")
                    append(r.tokenType.diagnosticsName())
                    append(" cannot be applied to a ")
                    append(Modifiers.Context.CLASS)
                },
                location = modifiers?.get(r),
                // this is so sketchy lmao, going through the values of a map to find the key which is THE OPPOSITE THING A MAP IS FOR LMAO
                // but since the program is already crashing here, doesnt really matter
                //
                // it just feels really sketchy and could fail at anytime ig
                selectorLength = Keywords.reserved.filterValues { it == r.tokenType }.keys.first().length,
                context = context
            )
        }
    }

    fun expectThenAdvance(token: Token.Type, ifOk: () -> Unit = { advancePointer() })
    {
        when(underPointer.type != token)
        {
            true ->
                Diagnostics.panic(
                    "KiraParser::expect",
                    buildString {
                        append("Expected ")
                        append(token.diagnosticsName())
                        append(" but got ")
                        append(if(underPointer.type.rawDiagnosticsRepresentation == null) "'${underPointer.content}' (${underPointer.type.diagnosticsName()})" else underPointer.type.diagnosticsName())
                    }, // we actually output the content underneath the pointer which is easier to see and depending on if it is like a symbol/keyword we output that else we output variable token contents accordingly
                    location = underPointer.canonicalLocation,
                    selectorLength = underPointer.content.length,
                    context = context
                )
            else -> ifOk()
        }
    }

    fun expectAnyOfThenAdvance(
        tokens: Array<Token.Type>,
        ifOk: () -> Unit =
            { advancePointer() },
    )
    {
        when
        {
            !tokens.contains(underPointer.type) ->
                Diagnostics.panic(
                    "KiraParser::expect",
                    buildString {
                        append("Expected any of ")
                        append(tokens.map { it.diagnosticsName() })
                        append(" but got ")
                        append(underPointer.type.diagnosticsName())
                    },
                    location = underPointer.canonicalLocation,
                    context = context
                )
            else                                -> ifOk()
        }
    }

    fun parseStatement(modifiers: Map<Modifiers, FileLocation>?): Statement
    {
        fun parseWithModifiers(): Statement
        {
            val modifiers = parseModifiers()
            val expr = when(underPointer.type)
            {
                Token.Type.K_CLASS  -> parseClassDecl(modifiers)
                Token.Type.K_OBJECT -> parseObjectDecl(modifiers)
                else                -> parseExpr()
            }
            expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
            return Statement(expr)
        }
        if(modifiers != null && modifiers.isNotEmpty())
        {
            return parseWithModifiers()
        }
        return when(underPointer.type)
        {
            // parse keywords stuffs first if possible (like keyword first statements)
            Token.Type.K_RETURN     -> parseReturnStatement()
            Token.Type.K_IF         -> parseIfSelectionStatement()
            Token.Type.K_WHILE      -> parseWhileIterationStatement()
            Token.Type.K_DO         -> parseDoWhileIterationStatement()
            Token.Type.K_FOR        -> parseForIterationStatement()
            Token.Type.K_USE        -> parseUseStatement()
            Token.Type.K_BREAK      -> parseBreakStatement()
            Token.Type.K_CONTINUE   -> parseContinueStatement()
            in Token.Type.modifiers -> parseWithModifiers()
            Token.Type.K_CLASS      -> // this part covers the case where the class decl has no modifiers on it. THIS CONDITION NEEDS TO BE UNDER THE PREVIOUS CONDITION
            {
                val expr = parseClassDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return Statement(expr)
            }
            Token.Type.K_ENUM       ->
            {
                val expr = parseEnumDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return Statement(expr)
            }
            Token.Type.K_OBJECT     -> // similar to the previous class case, covering when it has modifiers
            {
                val expr = parseObjectDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return Statement(expr)
            }
            else                    ->
            {
                val expr = parseExpr()
                // todo: idk wtf this panic message is for ?
//                if(underPointer.type == Token.Type.L_INTEGER || underPointer.type == Token.Type.IDENTIFIER)
//                {
//                    Diagnostics.panic(
//                        "KiraParser::parseStatement",
//                        "Unexpected token '${underPointer.content}'",
//                        location = underPointer.canonicalLocation,
//                        selectorLength = underPointer.content.length,
//                        context = context
//                    )
//                }
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                Statement(expr)
            }
        }
    }

    /**
     * Used to parse a statement between two `{}` AKA a block.
     */
    private fun parseStatementBlock(): List<Statement>
    {
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val statements = mutableListOf<Statement>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACE && underPointer.type != Token.Type.S_EOF)
        {
            statements.add(parseStatement(null))
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return statements
    }

    fun parseReturnStatement(): Statement // y dont they just call it a return expr? lol beats me tho, just another way to represent an astnode
    {
        expectThenAdvance(Token.Type.K_RETURN)
        val expr = parseExpr()
        expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
        return ReturnStatement(expr)
    }

    fun parseBreakStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_BREAK)
        return BreakStatement()
    }

    fun parseContinueStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_CONTINUE)
        return ContinueStatement()
    }

    fun parseForIterationStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_FOR)
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        // todo: might need a better warning message here, since the initializer needs to be present
        expectThenAdvance(Token.Type.K_MODIFIER_MUTABLE)
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_COLON)
        val target = parseExpr()
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        val body = parseStatementBlock()
        return ForIterationStatement(ForIterationExpr(identifier, target), body)
    }

    fun parseWhileIterationStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_WHILE)
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        val condition = parseExpr()
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        return WhileIterationStatement(condition, parseStatementBlock())
    }

    fun parseDoWhileIterationStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_DO)
        val statements = parseStatementBlock()
        expectThenAdvance(Token.Type.K_WHILE)
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        val condition = parseExpr()
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
        return DoWhileIterationStatement(condition, statements)
    }

    fun parseIfSelectionStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_IF)
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        val condition = parseExpr()
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        val thenStatements = parseStatementBlock()
        val branches = mutableListOf<IfElseBranchStatementNode>()
        while(underPointer.type == Token.Type.K_ELSE)
        {
            advancePointer() // consume "else" part: not useful
            when(underPointer.type) // before i started with always making that "else-if" part was just "elif" which made parsing a lot easier, but i can see why it really isnt that necessary LOL
            {
                Token.Type.K_IF -> // "else-if" part
                {
                    advancePointer()
                    expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
                    val deepCondition = parseExpr()
                    expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
                    branches.add(ElseIfBranchStatement(deepCondition, parseStatementBlock()))
                }

                else            -> branches.add(ElseBranchStatement(parseStatementBlock()))
            }
        }
        return IfSelectionStatement(condition, thenStatements, branches)
    }

    fun parseExpr(minPrecedence: Int = 0): Expr
    {
        var left: Expr = parsePrimaryOrUnaryExpr()
        while(true)
        {
            val opTokens = predictionStack.binaryOp()
            val opToUse =
                opTokens?.let { BinaryOp.byTokenTypeMaybe(*it) } ?: when(opTokens)
                {
                    null -> BinaryOp.byTokenTypeMaybe(underPointer.type)
                    else -> null
                }
            if(opToUse == null || opToUse.precedence < minPrecedence) break
            val tokensToAdvance = opTokens?.size ?: 1
            repeat(tokensToAdvance) { advancePointer() }
            if(opToUse == BinaryOp.TYPE_CHECK)
            {
                val right = parseType()
                return TypeCheckExpr(left, right)
            }
            if(opToUse == BinaryOp.TYPE_CAST)
            {
                val right = parseType()
                return TypeCastExpr(left, right)
            }
            val nextMinPrecedence = opToUse.precedence + 1
            val right = parseExpr(nextMinPrecedence)
            left = when(opToUse)
            {
                BinaryOp.CONJUNCTIVE_DOT -> MemberAccessExpr(left, right)
                BinaryOp.RANGE           -> RangeExpr(left, right)
                else                     -> BinaryExpr(left, right, opToUse)
            }
        }
        return left
    }

//    fun parseExpr(minPrecedence: Int = 0): Expr
//    {
//        var left: Expr = parsePrimaryOrUnaryExpr()
//        while(true)
//        {
//            val binaryOpType = BinaryOp.byTokenTypeMaybe(underPointer.type)
//            if(binaryOpType == null || binaryOpType.precedence < minPrecedence)
//            {
//                break
//            }
//            advancePointer()
//            // the following binary operators require special parsing of the right hand side so they are put before the others
//            if(binaryOpType == BinaryOp.TYPE_CHECK)
//            {
//                val right = parseType()
//                return TypeCheckExpr(left, right)
//            }
//            if(binaryOpType == BinaryOp.TYPE_CAST)
//            {
//                val right = parseType()
//                return TypeCastExpr(left, right)
//            }
//            val nextMinPrecedence = binaryOpType.precedence + 1
//            val right = parseExpr(nextMinPrecedence)
//            left =
//                when(binaryOpType)
//                {
//                    BinaryOp.CONJUNCTIVE_DOT -> MemberAccessExpr(left, right)
//                    BinaryOp.RANGE           -> RangeExpr(left, right)
//                    else                     -> BinaryExpr(left, right, binaryOpType)
//                }
//        }
//        return left
//    }

    private fun parsePrimaryOrUnaryExpr(): Expr
    {
        return when
        {
            UnaryOp.byTokenTypeMaybe(underPointer.type) != null -> parseUnaryExpr()
            else                                                -> parsePrimaryExpr(null)
        }
    }

    fun parsePrimaryExpr(modifiers: Map<Modifiers, FileLocation>?): Expr
    {
        return when(underPointer.type)
        {
            // lowkey this hard coded switch statement seems like the best approach, but i get that
            // itch that it will be like redundancy and edge case hell
            Token.Type.L_FLOAT                                                          -> parseFloatLiteral()
            Token.Type.L_INTEGER                                                        -> parseIntegerLiteral()
            Token.Type.L_STRING                                                         -> parseStringLiteral()
            Token.Type.S_OPEN_BRACE                                                     -> parseMapLiteral(false)
            Token.Type.S_OPEN_BRACKET                                                   -> parseArrayLiteral()
            Token.Type.K_MODIFIER_MUTABLE                                               ->
                when(peek(1).type)
                {
                    Token.Type.S_OPEN_BRACKET ->
                    {
                        advancePointer() // consumes the previous modifier (mutable)
                        parseListLiteral()
                    }
                    Token.Type.S_OPEN_BRACE   ->
                    {
                        advancePointer() // consume the mutable modifier
                        parseMapLiteral(true)
                    }
                    else                      -> Diagnostics.panic(
                        "KiraParser::parsePrimaryExpr",
                        "${Token.Type.K_MODIFIER_MUTABLE} is only allowed on arrays at this position",
                        context = context,
                        location = underPointer.canonicalLocation,
                        selectorLength = underPointer.content.length
                    )
                }
            Token.Type.S_AT                                                             ->
                when(peek(1).type)
                {
                    Token.Type.IDENTIFIER ->
                    {
                        advancePointer()
                        parseIntrinsicCallExpr()
                    }

                    else                  -> Diagnostics.panic(
                        "KiraParser::parsePrimaryExpr",
                        "Intrinsics must be followed by an identifier. I found '${peek(1).type.diagnosticsName()}'",
                        location = underPointer.canonicalLocation,
                        selectorLength = peek(1).content.length,
                        context = context
                    )
                }
            Token.Type.K_MODULE                                                         -> parseModuleDecl()
            Token.Type.IDENTIFIER                                                       ->
                when(peek(1).type)
                {
                    Token.Type.S_OPEN_PARENTHESIS -> parseFunctionCallOrDeclExpr(modifiers)
                    else                          -> parseIdentifierExpr(modifiers)
                }
            Token.Type.L_TRUE_BOOL, Token.Type.L_FALSE_BOOL                             -> parseBoolLiteral()
            Token.Type.OP_SUB, Token.Type.OP_ADD, Token.Type.S_BANG, Token.Type.S_TILDE -> parseUnaryExpr()
            Token.Type.S_OPEN_PARENTHESIS                                               ->
            {
                advancePointer()
                val expr = parseExpr()
                expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
                expr
            }
            else                                                                        ->
                Diagnostics.panic(
                    "KiraParser::parsePrimaryExpr",
                    "${
                        when(underPointer.type.rawDiagnosticsRepresentation)
                        {
                            null -> "'${underPointer.content}'"
                            else -> underPointer.type.diagnosticsName()
                        }
                    } is not allowed here.",
                    location = underPointer.canonicalLocation,
                    selectorLength = underPointer.content.length,
                    context = context
                )
        }
    }

    private fun peekBinaryOpTokens(): Array<Token.Type>?
    {
        return when
        {
            peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(2).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(3).type == Token.Type.OP_ASSIGN     -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN
            )
            peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(2).type == Token.Type.S_CLOSE_ANGLE -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE
            )
            peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(2).type == Token.Type.OP_ASSIGN     -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN
            )
            peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE
            )
            peek(0).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.OP_ASSIGN     -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN
            )
            peek(0).type == Token.Type.S_CLOSE_ANGLE         -> arrayOf(Token.Type.S_CLOSE_ANGLE)
            else                                             -> null
        }
    }

    fun parseModuleDecl(): Decl
    {
        expectThenAdvance(Token.Type.K_MODULE)
        val uri = parseStringLiteral()
        return ModuleDecl(uri)
    }

    fun parseUnaryExpr(): Expr
    {
        val operatorToken = underPointer
        expectAnyOfThenAdvance(UnaryOp.entries.map { it.tokenType }.toTypedArray())
        val operand = parseExpr(UnaryOp.NEG.precedence)
        return UnaryExpr(UnaryOp.byTokenTypeMaybe(operatorToken.type) {
            Diagnostics.panic(
                "UnaryOperator::byTokenTypeMaybe",
                "$operatorToken is not an unary operator!",
                context = context,
                location = operatorToken.canonicalLocation,
                selectorLength = operatorToken.content.length
            )
        }!!, operand)
    }

    fun parseBinaryExpr(): Expr
    {
        var left = parsePrimaryExpr(null)
        while(Token.Type.isBinaryOperator(underPointer.type))
        {
            val operator = underPointer
            advancePointer()
            val right = parsePrimaryExpr(null)
            left = when(operator.type)
            {
                Token.Type.S_DOT -> MemberAccessExpr(left, right)
                else             -> BinaryExpr(
                    left,
                    right,
                    BinaryOp.byTokenTypeMaybe(operator.type) {
                        Diagnostics.panic(
                            "BinaryOperator::byTokenTypeMaybe",
                            "$operator is not a binary operator!",
                            context = context,
                            location = operator.canonicalLocation,
                            selectorLength = operator.content.length
                        )
                    }!!
                )
            }
        }
        return left
    }

    fun parseIntrinsicCallExpr(): Expr
    {
        // the current implementation of intrinsic calls act more like preprocessor directives in other languages
        // it just finds and replaces in whatever process after it uses it
        //
        // for a better way we could just add it as a find and replace method, but that just feels lame, but whatever.
        val startLoc = underPointer.canonicalLocation
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        val parameters = mutableListOf<Expr>()
        while(underPointer.type != Token.Type.S_CLOSE_PARENTHESIS)
        {
            if(parameters.isNotEmpty())
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
            parameters.add(parseExpr())
        }
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        val findVal = Builtin.Intrinsics.entries.find { it.rep == identifier.name }
        return when(findVal != null)
        {
            true -> IntrinsicCallExpr(
                Intrinsic(
                    findVal,
                    AbsoluteFileLocation(
                        underPointer.canonicalLocation.lineNumber,
                        underPointer.canonicalLocation.column,
                        context.file
                    )
                ), parameters
            )
            else -> Diagnostics.panic(
                "KiraParser::parseIntrinsicCallExpr",
                "I could not find an intrinsic named '${identifier.name}'",
                location = startLoc,
                selectorLength = identifier.name.length,
                context = context
            )
        }
    }

    fun parseFunctionParameters(): List<FunctionParameterExpr>
    {
        // could this be also adapted for future implementations of function notations ??
        val parameters = mutableListOf<FunctionParameterExpr>()
        while(underPointer.type != Token.Type.S_CLOSE_PARENTHESIS && underPointer.type != Token.Type.S_EOF)
        {
            if(parameters.isNotEmpty())
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
            val modifiers = parseModifiers()
            val name = parseIdentifier()
            expectThenAdvance(Token.Type.S_COLON)
            val type = parseType()
            parameters.add(FunctionParameterExpr(name, type, modifiers.keys.toList()))
        }
        return parameters
    }

    fun parseFunctionCallOrDeclExpr(modifiers: Map<Modifiers, FileLocation>?): Expr
    {
        var identifier: Identifier? = null
        if(underPointer.type == Token.Type.IDENTIFIER)
        {
            identifier = parseIdentifier()
        }
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        return when(isFunctionDeclSyntax())
        {
            true -> if(identifier == null) parseFunctionLiteral() else parseFunctionDecl(identifier, modifiers)
            else ->
            {
                if(identifier == null)
                {
                    Diagnostics.panic(
                        "KiraParser::parseFunctionCallOrDeclExpr",
                        "Function calls must have a valid prefixed identifier!",
                        location = underPointer.canonicalLocation,
                        selectorLength = underPointer.content.length, // todo: check this validity
                        context = context
                    )
                }
                parseFunctionCallExpr(identifier)
            }
        }
    }

    private fun isFunctionDeclSyntax(): Boolean
    {
        var i = 0
        while(true)
        {
            val token = peek(i)
            val next = peek(i + 1)
            if(token.type == Token.Type.S_CLOSE_PARENTHESIS && next.type != Token.Type.S_COLON)
            {
                return false
            }
            if(token.type == Token.Type.S_COLON)
            {
                return true
            }
            i++
        }
    }

    private fun parseFunctionDecl(
        identifier: Identifier,
        modifiers: Map<Modifiers, FileLocation>?,
    ): FunctionDecl
    {
        expectModifiers(modifiers, Modifiers.Context.FUNCTION)
        val functionLiteral = parseFunctionLiteral()
        return FunctionDecl(identifier, functionLiteral, modifiers?.keys?.toList() ?: emptyList())
    }

    private fun parseFunctionCallExpr(identifier: Identifier): FunctionCallExpr
    {
        val parsedParameters = mutableListOf<Expr>()
        while(underPointer.type != Token.Type.S_CLOSE_PARENTHESIS && underPointer.type != Token.Type.S_EOF)
        {
            if(parsedParameters.isNotEmpty())
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
            parsedParameters.add(parseExpr())
        }
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        return FunctionCallExpr(identifier, parsedParameters)
    }

    private fun parseIdentifierExpr(modifiers: Map<Modifiers, FileLocation>?): Expr
    {
        return when(peek(1).type)
        {
            Token.Type.OP_ASSIGN                      -> parseAssignmentExpr()
            in Token.Type.compoundAssignmentOperators -> parseCompoundAssignmentExpr()
            Token.Type.S_COLON                        -> parseVariableDecl(modifiers)
            else                                      -> parseIdentifier()
        }
    }

    fun parseUseStatement(): Statement
    {
        expectThenAdvance(Token.Type.K_USE)
        val uri = parseStringLiteral()
        return UseStatement(uri)
    }

    fun parseCompoundAssignmentExpr(): CompoundAssignmentExpr
    {
        val left = parseIdentifier() // todo: allow for more than just identifiers for now
        val opTokens: Array<Token.Type>? = when
        {
            underPointer.type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(2).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(3).type == Token.Type.OP_ASSIGN                -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN
            )
            underPointer.type == Token.Type.S_CLOSE_ANGLE &&
                    peek(1).type == Token.Type.S_CLOSE_ANGLE &&
                    peek(2).type == Token.Type.OP_ASSIGN                -> arrayOf(
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.OP_ASSIGN
            )
            underPointer.type in Token.Type.compoundAssignmentOperators -> arrayOf(underPointer.type)
            else                                                        -> null
        }
        if(opTokens == null)
        {
            Diagnostics.panic(
                "KiraParser::parseCompoundAssignmentExpr",
                "Expected a compound assignment operator, but found '${underPointer.type}'",
                location = underPointer.canonicalLocation,
                context = context
            )
        }
        val op = CompoundAssignmentExpr.findBinaryOp(opTokens, context)
        repeat(opTokens.size) { advancePointer() }
        val right = parseExpr()
        return CompoundAssignmentExpr(left, op, right)
    }

    fun parseAssignmentExpr(): AssignmentExpr
    {
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.OP_ASSIGN)
        val value = parseBinaryExpr()
        return AssignmentExpr(identifier, value)
    }

    fun parseEnumMemberExpr(): EnumMemberExpr
    {
        val name = parseIdentifier()
        var value: DataLiteral<*>? = null
        if(underPointer.type == Token.Type.OP_ASSIGN)
        {
            expectThenAdvance(Token.Type.OP_ASSIGN)
            val start = underPointer
            val parseValue = parsePrimaryExpr(null)
            if(parseValue !is SimpleLiteral && parseValue !is DataLiteral<*>)
            {
                Diagnostics.panic(
                    "KiraParser::parseEnumMemberExpr",
                    "Only simple literals are allowed as enum values. That is strings, booleans, floats, and integers.",
                    location = start.canonicalLocation,
                    selectorLength = start.content.length,
                    context = context
                )
            }
            value = parseValue as DataLiteral<*>
        }
        return EnumMemberExpr(name, value)
    }

    fun parseEnumDecl(modifiers: Map<Modifiers, FileLocation>?): EnumDecl
    {
        expectModifiers(modifiers, Modifiers.Context.ENUM)
        advancePointer() // consume 'enum'
        val name = parseIdentifier() // we only allow simple names, not complex names on enums, cuz there is no point
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val members = mutableListOf<EnumMemberExpr>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACE && underPointer.type != Token.Type.S_EOF)
        {
            members.add(parseEnumMemberExpr())
            if(underPointer.type != Token.Type.S_CLOSE_BRACE)
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return EnumDecl(name, members.toTypedArray(), modifiers?.keys?.toList() ?: emptyList())
    }

    fun parseClassDecl(modifiers: Map<Modifiers, FileLocation>?): ClassDecl
    {
        expectModifiers(modifiers, Modifiers.Context.CLASS)
        advancePointer() //consume the class keyword
        val className = parseType()
        var parentType: TypeSpecifier? = null
        if(underPointer.type == Token.Type.S_COLON) // inheritance here baby ;D
        {
            advancePointer()
            parentType = parseType()
        }
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val members = mutableListOf<FirstClassDecl>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACE && underPointer.type != Token.Type.S_EOF)
        {
            val memberModifiers = parseModifiers()
            expectModifiers(memberModifiers, Modifiers.Context.CLASS_MEMBER)
            members.add(
                when(underPointer.type != Token.Type.S_OPEN_PARENTHESIS && peek(1).type != Token.Type.S_OPEN_PARENTHESIS) // the first condition covers the case where there can be anonymous functions here
                {
                    true ->
                    {
                        if(underPointer.type != Token.Type.IDENTIFIER)
                        {
                            Diagnostics.panic(
                                "KiraParser::parseClassDecl",
                                "Anonymous Literals are not allowed by themselves in a class.",
                                location = underPointer.canonicalLocation,
                                selectorLength = underPointer.content.length,
                                context = context
                            )
                        }
                        parseVariableDecl(memberModifiers)
                    }
                    else ->
                    {
                        val start = underPointer.canonicalLocation
                        if(!isFunctionDeclSyntax())
                        {
                            Diagnostics.panic(
                                "KiraParser::parseClassMemberExpr",
                                "Expected a function member here",
                                location = underPointer.canonicalLocation,
                                selectorLength = underPointer.content.length,
                                context = context
                            )
                        }
                        val expr = parseFunctionCallOrDeclExpr(memberModifiers)
                        if(expr is Literal) // at this pt this is most likely an anonymous function or a function literal so this is just for my sanity, in hindsight i don't hink this check will ever fail nor will it cover other "data literals"
                        {
                            Diagnostics.panic(
                                "KiraParser::parseClassDecl",
                                buildString {
                                    append(expr::class.simpleName ?: "Literal")
                                    append("s are not allowed to be placed in classes by themselves")
                                },
                                location = start,
                                selectorLength = context.findCanonicalLine(start.lineNumber).length, // make the error marker cover the whole line!
                                context = context
                            )
                        }
                        expr as FunctionDecl // if this throws, then it is 99.99% a bug
                    }
                }
            )
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return ClassDecl(className, modifiers?.keys?.toList() ?: emptyList(), members, parentType)
    }

    fun parseVariableDecl(modifiers: Map<Modifiers, FileLocation>?): VariableDecl
    {
        expectModifiers(modifiers, Modifiers.Context.VARIABLE)
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_COLON)
        val type = parseType()
        var value: Expr? = null
        if(underPointer.type == Token.Type.OP_ASSIGN)
        {
            advancePointer()
            value = parseExpr()
        }
        return VariableDecl(identifier, type, value, modifiers?.keys?.toList() ?: emptyList())
    }

    fun parseObjectDecl(modifiers: Map<Modifiers, FileLocation>?): ObjectDecl
    {
        expectModifiers(modifiers, Modifiers.Context.OBJECT)
        expectThenAdvance(Token.Type.K_OBJECT)
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val members = mutableListOf<Decl>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACE && underPointer.type != Token.Type.S_EOF)
        {
            val start = underPointer.canonicalLocation
            val modifiers = parseModifiers()
            expectModifiers(modifiers, Modifiers.Context.OBJECT_MEMBER)
            val member = parseStatement(modifiers)
            when(member.expr)
            {
                // i just want something like "!is ... && !is ..." why is kotlin so weird, even dart supports this ?
                !is Decl -> Diagnostics.panic(
                    "KiraParser::parseObjectDecl",
                    "The type ${member::class.simpleName} is not allowed in an object declaration. Only declarations are allowed.",
                    context = context,
                    location = start,
                    selectorLength = context.findCanonicalLine(start.lineNumber).length
                )
                else     -> members.add(member.expr as Decl)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return ObjectDecl(identifier, modifiers?.keys?.toList() ?: emptyList(), members)
    }

    fun parseStringLiteral(): StringLiteral
    {
        val value = underPointer.content
        expectThenAdvance(Token.Type.L_STRING)
        return StringLiteral(value)
    }

    fun parseArrayLiteral(): ArrayLiteral
    {
        expectThenAdvance(Token.Type.S_OPEN_BRACKET)
        val elements = mutableListOf<Expr>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACKET && underPointer.type != Token.Type.S_EOF)
        {
            elements.add(parsePrimaryExpr(null))
            if(underPointer.type != Token.Type.S_CLOSE_BRACKET)
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACKET)
        return ArrayLiteral(elements.toTypedArray())
    }

    fun parseListLiteral(): ListLiteral
    {
        val arrayLiteral = parseArrayLiteral()
        return ListLiteral(arrayLiteral.value.toList()) // more work we are just turning back what the parseLiteral function already did lmao
    }

    fun parseMapLiteral(mutable: Boolean): MapLiteral
    {
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val elements = mutableMapOf<Expr, Expr>()
        while(underPointer.type != Token.Type.S_CLOSE_BRACE && underPointer.type != Token.Type.S_EOF)
        {
            val key = parseExpr()
            expectThenAdvance(Token.Type.S_COLON)
            val value = parseExpr()
            elements[key] = value
            if(underPointer.type != Token.Type.S_CLOSE_BRACE)
            {
                expectThenAdvance(Token.Type.S_COMMA)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return MapLiteral(elements, mutable)
    }

    fun parseIntegerLiteral(): IntegerLiteral
    {
        var value by Delegates.notNull<Long>()
        try
        {
            value = underPointer.content.toLong()
        }
        catch(e: Exception)
        {
            Diagnostics.panic(
                "KiraParser::parseIntegerLiteral",
                "Unable to read '${underPointer.content}' as an integer literal",
                cause = e,
                location = underPointer.canonicalLocation,
                selectorLength = underPointer.content.length,
                context = context
            )
        }
        expectThenAdvance(Token.Type.L_INTEGER)
        return IntegerLiteral(value)
    }

    fun parseFloatLiteral(): FloatLiteral
    {
        var value by Delegates.notNull<Double>()
        try
        {
            value = underPointer.content.toDouble()
        }
        catch(e: Exception)
        {
            Diagnostics.panic(
                "KiraParser::parseIntegerLiteral",
                "Unable to read '${underPointer.content}' as an integer literal",
                cause = e,
                location = underPointer.canonicalLocation,
                selectorLength = underPointer.content.length,
                context = context
            )
        }
        expectThenAdvance(Token.Type.L_FLOAT)
        return FloatLiteral(value)
    }

    fun parseBoolLiteral(): BoolLiteral
    {
        var value by Delegates.notNull<Boolean>()
        try
        {
            value = underPointer.content.toBooleanStrict()
        }
        catch(e: Exception)
        {
            Diagnostics.panic(
                "KiraParser::parseBoolLiteral",
                "Unable to read ${underPointer.content} as a bool literal",
                cause = e,
                location = underPointer.canonicalLocation,
                context = context
            )
        }
        expectAnyOfThenAdvance(arrayOf(Token.Type.L_TRUE_BOOL, Token.Type.L_FALSE_BOOL))
        return BoolLiteral(value)
    }

    fun parseFunctionLiteral(): AnonymousFunction
    {
        val params = parseFunctionParameters()
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        expectThenAdvance(Token.Type.S_COLON)
        val returnType = parseType()
        var body: List<Statement>? = null
        when(underPointer.type)
        {
            Token.Type.S_OPEN_BRACE -> body = parseStatementBlock()
            else                    -> advancePointer()
        }
        return AnonymousFunction(returnType, params, body)
    }

    fun parseIdentifier(): Identifier
    {
        val value = underPointer.content
        expectThenAdvance(Token.Type.IDENTIFIER)
        return Identifier(value)
    }

    fun parseType(trace: Int = 0): TypeSpecifier
    {
        val baseName = underPointer.content
        expectThenAdvance(Token.Type.IDENTIFIER)
        val generics = mutableListOf<TypeSpecifier>()
        if(underPointer.type == Token.Type.S_OPEN_ANGLE)
        {
            advancePointer()
            while(true)
            {
                generics.add(parseType(trace + 1))
                if(underPointer.type == Token.Type.S_COMMA)
                {
                    advancePointer()
                    continue
                }
                break
            }
            expectThenAdvance(Token.Type.S_CLOSE_ANGLE)
        }
        return TypeSpecifier(baseName, generics.toTypedArray())
    }

    fun parseModifiers(): Map<Modifiers, FileLocation>
    {
        val modifiers = mutableMapOf<Modifiers, FileLocation>()
        while(underPointer.type in Token.Type.modifiers)
        {
            val currentModifier = Modifiers.byTokenTypeMaybe(underPointer.type) {
                Diagnostics.panic(
                    "KiraParser::parseModifiers",
                    "$underPointer is not a valid modifier",
                    context = context,
                    location = underPointer.canonicalLocation,
                    selectorLength = underPointer.content.length
                )
            }!!
            if(currentModifier in modifiers)
            {
                Diagnostics.panic(
                    "KiraParser::parseModifiers",
                    buildString {
                        append("The modifier ")
                        append(currentModifier.tokenType.diagnosticsName())
                        append(" was already specified at ")
                        append(underPointer.canonicalLocation)
                        append(". Remove the duplicate modifier.")
                    },
                    location = underPointer.canonicalLocation,
                    selectorLength = underPointer.content.length,
                    context = context
                )
            }
            modifiers[Modifiers.byTokenTypeMaybe(underPointer.type) {
                Diagnostics.panic(
                    "KiraParser::parseModifiers",
                    "$underPointer is not a valid modifier",
                    context = context,
                    location = underPointer.canonicalLocation,
                    selectorLength = underPointer.content.length
                )
            }!!] = underPointer.canonicalLocation
            advancePointer()
        }
        return modifiers
    }
}