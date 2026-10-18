package net.exoad.kira.dialect.legacy

import net.exoad.kira.compiler.analysis.diagnostics.Diagnostics
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.TokenBuffer
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.*
import net.exoad.kira.compiler.frontend.parser.ast.elements.*
import net.exoad.kira.compiler.frontend.parser.ast.expressions.*
import net.exoad.kira.compiler.frontend.parser.ast.literals.*
import net.exoad.kira.compiler.frontend.parser.ast.statements.*
import net.exoad.kira.core.IntrinsicRegistry
import net.exoad.kira.core.Keywords
import net.exoad.kira.core.OperatorIntrinsics
import net.exoad.kira.core.Symbols
import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import net.exoad.kira.utils.EnglishUtils
import java.util.*
import kotlin.math.max
import kotlin.properties.Delegates

/**
 * The parser of the old dialect (py-backend 45d5255), frozen: the converter reads old text with it
 * and proves each conversion by comparing its tree with the new parser's.
 */
class LegacyKiraParser(private val context: SourceContext) {
    private val buffer = TokenBuffer(context.tokens)

    init {
        context.astOrigins = IdentityHashMap()
        context.astIntrinsicMarked = IdentityHashMap()
        context.astIntrinsicInvocations = IdentityHashMap()
    }

    /**
     * Condition-head rule D36. While parsing the bare (unparenthesized)
     * condition of `if`, `else if`, `while` and `do ... while`, and the
     * iterable of `for ... in`, an uppercase identifier followed by `{` is the
     * body's brace, not an object construction. Parentheses, brackets and a
     * block reset the rule (see [withNoObjectInit]).
     */
    private var noObjectInit = false

    private inline fun <T> withNoObjectInit(value: Boolean, block: () -> T): T {
        val saved = noObjectInit
        noObjectInit = value
        try {
            return block()
        } finally {
            noObjectInit = saved
        }
    }

    /**
     * Whether [ident] may name a type in `Ident {` / `Ident<...> {`: it is
     * PascalCase, and it holds no underscore (UPPER_SNAKE is the only place
     * the spec allows `_`, so `LIMIT_M {` is never a construction anywhere).
     */
    private fun looksLikeTypeName(ident: String): Boolean {
        return ident.isNotEmpty() && ident[0].isUpperCase() && !ident.contains(Symbols.UNDERSCORE.rep)
    }

    fun <T : ASTNode> putOrigin(
        node: T,
        location: SourcePosition = here(),
    ): T // returns the original value to facilitate with easier refactoring
    {
        context.astOrigins[node] = location
        return node
    }

    fun here(): SourcePosition {
        return peek().canonicalLocation
    }

    /**
     * Star method to call on [KiraParser] that will turn the passed in [context] into an AST.
     */
    fun parse() {
        val statements = mutableListOf<Statement>()
        try {
            while (!at(Token.Type.S_EOF)) {
                statements.add(parseStatement(null))
            }
        } catch (_: StackOverflowError) {
            Diagnostics.panic(
                "LegacyKiraParser::parse",
                "This nests too deeply to parse; split it into smaller expressions or declarations.",
                context = context,
                location = here(),
                selectorLength = max(1, peek().content.length)
            )
        }
        if (statements.firstOrNull()?.expr !is ModuleDecl) {
            Diagnostics.panic(
                "LegacyKiraParser::parse",
                "The first declaration of ${context.file} must be a module declaration!",
                context = context,
                location = SourcePosition(1, 1),
                selectorLength = max(1, context.findCanonicalLine(1).length)
            )
        }
        context.ast = RootASTNode(statements)
    }

    /**
     * Grabs the token at the [k]-th position (absolute).
     *
     * If you need relative positioning, take a look at [peek]
     */
    fun look(k: Int): Token {
        return buffer.peek(k - 1)
    }

    /**
     * Grabs the token [k] away from where the pointer is right now (relative).
     *
     * If you need absolute positioning, take a look at [look]
     *
     * - also performs caching on the result for the same relative
     */
    private fun peek(k: Int = 0): Token {
        return buffer.peek(k)
    }

    /**
     * Moves the pointer forward to the next token and thus "consumes" the current token
     */
    private fun advancePointer(count: Int = 1) {
        buffer.advance(count)
    }

    fun tryInOrder(candidates: Array<Array<Token.Type>>, peekOffset: Int = 0): Array<Token.Type>? {
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            val candidate = candidates[0]
            for (i in candidate.indices) {
                if (candidate[i] != peek(i + peekOffset).type) return null
            }
            return candidate
        }
        for (candidate in candidates) {
            var match = true
            for (i in candidate.indices) {
                if (candidate[i] != peek(i + peekOffset).type) {
                    match = false
                    break
                }
            }
            if (match) {
                return candidate
            }
        }
        return null
    }

    private inline fun expectOptionalThenAdvance(token: Token.Type, ifOk: () -> Unit = { advancePointer() }) {
        if (at(token)) {
            ifOk()
        }
    }

    private fun at(type: Token.Type): Boolean {
        return type == peek().type
    }

    private fun expectModifiers(modifier: Map<Modifier, SourcePosition>?, scopes: WrappingContext) {
        val r = if (modifier == null || modifier.isEmpty()) {
            null
        } else {
            modifier.keys.toList().find { !it.wrappingContext.contains(scopes) }
        }
        if (r != null) {
            // `final` (W2.9, 1.8) is a class modifier only; everywhere else gets its own
            // diagnostic (`parse.final`) instead of the generic wrapping-context message,
            // so `types.class.final` (SignatureResolver.classParents) has a parser-side twin.
            if (r == Modifier.FINAL) {
                Diagnostics.panic(
                    "parse.final",
                    "'final' can only modify a class; it cannot be applied to a ${scopes.name.lowercase().replace('_', ' ')}.",
                    location = modifier?.get(r),
                    selectorLength = "final".length,
                    context = context
                )
            }
            Diagnostics.panic(
                "LegacyKiraParser::expectModifiers",
                buildString {
                    append("The modifier ")
                    append(r.tokenType.diagnosticsName())
                    append(" cannot be applied to a ")
                    append(scopes.name.lowercase().replace('_', ' '))
                },
                location = modifier?.get(r),
                // this is so sketchy lmao, going through the values of a map to find the key which is THE OPPOSITE THING A MAP IS FOR LMAO
                // but since the program is already crashing here, doesnt really matter
                //
                // it just feels really sketchy and could fail at anytime ig
                selectorLength = Keywords.legacy.filterValues { it == r.tokenType }.keys.first().length,
                context = context
            )
        }
    }

    private inline fun expectThenAdvance(
        token: Token.Type,
        noinline onBad: (() -> Nothing)? = null,
        ifOk: () -> Unit = { advancePointer() }
    ) {
        if (!at(token)) {
            if (onBad != null) {
                onBad()
            } else {
                val helpMessage = buildString {
                    when (token) {
                        Token.Type.S_OPEN_PARENTHESIS -> {
                            when (peek().type) {
                                Token.Type.S_OPEN_BRACE -> {
                                    append("Did you mean to use '(' instead of '{'? Function declarations require parentheses for parameters.")
                                }

                                Token.Type.S_COLON -> {
                                    append("Missing '(' before parameters. Function syntax is: fx name: (params) ReturnType { ... }")
                                }

                                else -> {
                                    append("Insert '(' here to begin the parameter list or condition.")
                                }
                            }
                        }

                        Token.Type.S_CLOSE_PARENTHESIS -> {
                            append("Missing closing ')'. Check that all opening parentheses are properly closed.")
                        }

                        Token.Type.S_OPEN_BRACE -> {
                            append("Missing '{' to begin a block. Functions, classes, and control flow statements require braces around their body.")
                        }

                        Token.Type.S_CLOSE_BRACE -> {
                            append("Missing closing '}'. Check that all opening braces are properly closed.")
                        }

                        Token.Type.S_COLON -> {
                            when (peek().type) {
                                Token.Type.S_EQUAL -> {
                                    append("Use ':' for type annotations, not '='. Syntax: name: Type = value")
                                }
                                // `fx name(params): Ret` is the pre-migration form; point at
                                // the colon-binds-the-name shape instead of the generic hint.
                                Token.Type.S_OPEN_PARENTHESIS -> {
                                    append("Missing ':' after the function name. Kira function syntax is: fx name: (params) ReturnType { ... } -- the colon binds the name to its signature, the same way 'value: Int32' binds a variable to its type.")
                                }
                                else -> {
                                    append("Missing ':' for type annotation. Variables and functions require type specifications.")
                                }
                            }
                        }

                        Token.Type.S_COMMA -> {
                            append("Missing ',' to separate elements. Use commas between list/map items, function parameters, or generic type arguments.")
                        }

                        Token.Type.S_SEMICOLON -> {
                            append("Missing ';' or newline to terminate the statement.")
                        }

                        Token.Type.S_EQUAL -> {
                            append("Missing '=' for assignment or initialization.")
                        }

                        Token.Type.IDENTIFIER -> {
                            if (peek().type == Token.Type.S_OPEN_PARENTHESIS) {
                                append("Expected an identifier (variable or function name) here, not '('. Did you forget the name?")
                            } else {
                                append("Expected an identifier (a name) here. Identifiers must start with a letter and use camelCase or PascalCase.")
                            }
                        }

                        Token.Type.S_OPEN_ANGLE -> {
                            append("Missing '<' to begin generic type parameters. Syntax: Type<T, U> or fx name<T>: (params) ReturnType")
                        }

                        Token.Type.S_CLOSE_ANGLE -> {
                            append("Missing '>' to close generic type parameters. Check that all '<' are properly closed.")
                        }

                        else -> {
                            append("Insert '${token.diagnosticsName()}' here to fix the syntax error.")
                        }
                    }
                }

                Diagnostics.panic(
                    "LegacyKiraParser::expect",
                    buildString {
                        append("Expected ")
                        append(EnglishUtils.prependIndefiniteArticle(token.diagnosticsName().lowercase()))
                        append(" but got ")
                        append(EnglishUtils.prependIndefiniteArticle(peek().type.diagnosticsName()))
                        if (peek().type == Token.Type.IDENTIFIER || peek().type == Token.Type.L_STRING) {
                            append(" '${peek().content}'")
                        }
                        append("\n\nHelp: ")
                        append(helpMessage)
                    },
                    location = peek().canonicalLocation,
                    selectorLength = peek().content.length,
                    context = context
                )
            }
        } else {
            ifOk()
        }
    }

    private inline fun expectAnyOfThenAdvance(tokens: Array<Token.Type>, ifOk: () -> Unit = { advancePointer() }) {
        when {
            !tokens.contains(peek().type) ->
                Diagnostics.panic(
                    "LegacyKiraParser::expect",
                    buildString {
                        append("Expected any of ")
                        append(tokens.map { it.diagnosticsName() })
                        append(" but got ")
                        append(EnglishUtils.prependIndefiniteArticle(peek().type.diagnosticsName()))
                    },
                    location = peek().canonicalLocation,
                    context = context
                )

            else -> ifOk()
        }
    }

    /** An enum, a variant, a do-while or a finally: forms the clean dialect removed, refused so the converter names the file. */
    private fun removedForm(what: String): Nothing = Diagnostics.panic(
        "legacy.removed",
        "cannot convert $what, which the clean dialect removes",
        location = here(),
        selectorLength = max(1, peek().content.length),
        context = context
    )

    fun parseStatement(modifier: Map<Modifier, SourcePosition>?): Statement {
        fun parseWithModifiers(): Statement {
            val baseLocation = here()
            val modifiers = parseModifiers()
            expectMarkedDeclarationFollows()
            var expr = when (peek().type) {
                Token.Type.K_CLASS -> parseClassDecl(modifiers)
                Token.Type.K_STRUCT -> parseStructDecl(modifiers)
                Token.Type.K_ENUM -> removedForm("an enum")
                Token.Type.K_ALIAS -> parseTypeAliasExpr(modifiers)
                Token.Type.K_VARIANT -> removedForm("a variant")
                Token.Type.K_TRAIT -> parseTraitDecl(modifiers)
                else -> parsePrimaryExpr(modifiers)
            }
            expr = attachIntrinsics(expr)
            expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
            return putOrigin(Statement(expr), baseLocation)
        }
        if (modifier != null && modifier.isNotEmpty()) {
            return parseWithModifiers()
        }
        // If keywords were lexed as simple identifiers (e.g., 'try' or 'throw'), handle them here too
        if (peek().type == Token.Type.IDENTIFIER) {
            val txt = peek().content
            if (txt == "throw") {
                return parseThrowStatement()
            }
            if (txt == "try") {
                return parseTryStatement()
            }
        }

        return when (peek().type) {
            // parse keywords stuffs first if possible (like keyword first statements)
            Token.Type.K_RETURN -> parseReturnStatement()
            Token.Type.K_IF -> parseIfSelectionStatement()
            Token.Type.K_WHILE -> parseWhileIterationStatement()
            Token.Type.K_DO -> removedForm("a do-while loop")
            Token.Type.K_FOR -> parseForIterationStatement()
            Token.Type.K_THROW -> parseThrowStatement()
            Token.Type.K_TRY -> parseTryStatement()
            Token.Type.K_USE -> parseUseStatement()
            Token.Type.K_BREAK -> parseBreakStatement()
            Token.Type.K_CONTINUE -> parseContinueStatement()
            // A declaration intrinsic with no keyword modifier before it (`@_extern fx f`).
            // Callable intrinsics (@op_add, @_trace_) parse as expression statements.
            Token.Type.INTRINSIC_IDENTIFIER -> {
                val raw = peek().content.removePrefix("@")
                if (IntrinsicRegistry.isDeclMarker(raw)) {
                    parseWithModifiers()
                } else {
                    val baseLocation = here()
                    val expr = parseExpr()
                    expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                    putOrigin(Statement(expr), baseLocation)
                }
            }
            in Token.Type.modifiers -> parseWithModifiers()
            Token.Type.K_CLASS -> // this part covers the case where the class decl has no modifiers on it. THIS CONDITION NEEDS TO BE UNDER THE PREVIOUS CONDITION
            {
                val baseLocation = here()
                val expr = parseClassDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return putOrigin(Statement(expr), baseLocation)
            }

            Token.Type.K_STRUCT -> {
                val baseLocation = here()
                val expr = parseStructDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return putOrigin(Statement(expr), baseLocation)
            }

            Token.Type.K_TRAIT -> {
                val baseLocation = here()
                val expr = parseTraitDecl(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return putOrigin(Statement(expr), baseLocation)
            }

            Token.Type.K_ALIAS -> {
                val baseLocation = here()
                val expr = parseTypeAliasExpr(null)
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return putOrigin(Statement(expr), baseLocation)
            }

            Token.Type.K_ENUM -> removedForm("an enum")

            Token.Type.K_VARIANT -> removedForm("a variant")

            else -> {
                val baseLocation = here()
                val expr = parseExpr()
                // todo: idk wtf this panic message is for ?
//                if(hereIs( Token.Type.L_INTEGER || hereIs( Token.Type.IDENTIFIER)
//                {
//                    Diagnostics.panic(
//                        "LegacyKiraParser::parseStatement",
//                        "Unexpected token '${peek().content}'",
//                        location = peek().canonicalLocation,
//                        selectorLength = peek().content.length,
//                        context = context
//                    )
//                }
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                return putOrigin(Statement(expr), baseLocation)
            }
        }
    }

    /**
     * Used to parse a statement between two `{}` AKA a block.
     */
    private fun parseStatementBlock(): List<Statement> {
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val statements = mutableListOf<Statement>()
        withNoObjectInit(false) {
            while (!at(Token.Type.S_CLOSE_BRACE) && !at(Token.Type.S_EOF)) {
                statements.add(parseStatement(null))
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return statements
    }

    private val declarationStarters = setOf(
        Token.Type.K_CLASS,
        Token.Type.K_STRUCT,
        Token.Type.K_ENUM,
        Token.Type.K_ALIAS,
        Token.Type.K_VARIANT,
        Token.Type.K_TRAIT,
        Token.Type.K_FX,
        Token.Type.IDENTIFIER,
    )

    /**
     * A declaration-marker intrinsic (`@_extern("sym")`, `@_const`) marks the
     * declaration that follows it (design 2.5). Measured before this rule:
     * a bare `@_extern("cname")` line compiled to a stray `"cname";`
     * statement, because its parentheses were read as an expression.
     */
    private fun expectMarkedDeclarationFollows() {
        val marks = pendingIntrinsicExprs ?: return
        if (peek().type in declarationStarters) {
            return
        }
        Diagnostics.panic(
            "LegacyKiraParser::parseStatement",
            "'@${marks.last().intrinsicKey.name}' marks the declaration that follows it, but " +
                "${EnglishUtils.prependIndefiniteArticle(peek().type.diagnosticsName())} follows instead.\n\n" +
                "Help: put a declaration after the marker, like '@${marks.last().intrinsicKey.name} fx name: (...) Ret;'.",
            location = here(),
            selectorLength = max(1, peek().content.length),
            context = context
        )
    }

    fun parseReturnStatement(): Statement // y dont they just call it a return expr? lol beats me tho, just another way to represent an astnode
    {
        val origin = here()
        expectThenAdvance(Token.Type.K_RETURN)
        // A bare `return` (Void functions) carries NoExpr. Newlines are not
        // tokens, so "bare" means the next token closes the statement: `}`,
        // `;` or end of input. `return` followed by an expression on the next
        // line still returns that expression.
        val expr = if (at(Token.Type.S_CLOSE_BRACE) || at(Token.Type.S_SEMICOLON) || at(Token.Type.S_EOF)) {
            NoExpr
        } else {
            parseExpr()
        }
        expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
        return putOrigin(ReturnStatement(expr), origin)
    }

    fun parseBreakStatement(): Statement {
        val origin = here()
        expectThenAdvance(Token.Type.K_BREAK)
        return putOrigin(BreakStatement(), origin)
    }

    fun parseContinueStatement(): Statement {
        val origin = here()
        expectThenAdvance(Token.Type.K_CONTINUE)
        return putOrigin(ContinueStatement(), origin)
    }

    fun parseForIterationStatement(): Statement {
        val origin = here()
        expectThenAdvance(Token.Type.K_FOR)
        // support both: `for (mut x: expr) {}` and `for mut x: expr {}`
        val hasParens = at(Token.Type.S_OPEN_PARENTHESIS)
        if (hasParens) expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        val forExpr = if (at(Token.Type.K_MODIFIER_MUTABLE)) {
            // Legacy `for mut i: a..b { }`: an untyped variable over an
            // inclusive range (design 2.2); it keeps its meaning.
            advancePointer()
            val identifier = parseIdentifier()
            expectThenAdvance(Token.Type.S_COLON)
            val target = withNoObjectInit(!hasParens) { parseExpr() }
            ForIterationExpr(identifier, target, emptyList())
        } else {
            // Spec `for i: T in e { }`: a typed variable; a range is exclusive
            // (design D37). The iterable is a condition head (D36).
            val identifier = parseIdentifier()
            expectThenAdvance(Token.Type.S_COLON)
            val declaredType = parseType()
            expectThenAdvance(Token.Type.K_IN)
            val target = withNoObjectInit(!hasParens) { parseExpr() }
            ForIterationExpr(identifier, target, emptyList(), declaredType, isLegacy = false)
        }
        if (hasParens) expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        val body = parseStatementBlock()
        return putOrigin(ForIterationStatement(putOrigin(forExpr, origin), body), origin)
    }

    /**
     * The condition of `if`, `else if`, `while` and `do ... while`; the pointer
     * sits just after the keyword. It is one full expression under rule D36:
     * `if x < LIMIT {` reads `LIMIT` as a value and `{` as the body, while
     * inside parentheses a construction is a construction again. A leading
     * `(` is an ordinary parenthesized operand, so `if (crc & 1) != 0 {` and
     * `if (x) {` both parse. Measured before the rule: `if x < LIMIT {` failed
     * and `if x < (LIMIT) {` parsed.
     */
    private fun parseConditionHead(): Expr {
        return withNoObjectInit(true) { parseExpr() }
    }

    private fun parseParentheticalConditionExpr(leading: Token.Type): Expr {
        expectThenAdvance(leading)
        return parseConditionHead()
    }

    fun parseWhileIterationStatement(): Statement {
        val origin = here()
        val condition = parseParentheticalConditionExpr(Token.Type.K_WHILE)
        return putOrigin(WhileIterationStatement(condition, parseStatementBlock()), origin)
    }

    fun parseIfSelectionStatement(): Statement {
        val origin = here()
        val condition = parseParentheticalConditionExpr(Token.Type.K_IF)
        val thenStatements = parseStatementBlock()
        val branches = mutableListOf<IfElseBranchStatementNode>()
        while (at(Token.Type.K_ELSE)) {
            advancePointer() // consume "else" part: not useful
            when (peek().type) // before i started with always making that "else-if" part was just "elif" which made parsing a lot easier, but i can see why it really isnt that necessary LOL
            {
                Token.Type.K_IF -> // "else-if" part
                {
                    val subOrigin = peek().canonicalLocation
                    advancePointer()
                    val deepCondition = parseConditionHead()
                    branches.add(putOrigin(ElseIfBranchStatement(deepCondition, parseStatementBlock()), subOrigin))
                }

                else -> branches.add(putOrigin(ElseBranchStatement(parseStatementBlock())))
            }
        }
        return putOrigin(IfSelectionStatement(condition, thenStatements, branches), origin)
    }

    // handles special cases with closing angle which are not tokenized together during the lexer (we use angle brackets for generics smh)
    private fun tryBinaryOps(peekOffset: Int = 0): Array<Token.Type>? {
        return tryInOrder(
            arrayOf(
                arrayOf(Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE),
                arrayOf(Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE),
                arrayOf(Token.Type.S_CLOSE_ANGLE, Token.Type.S_EQUAL)
            ),
            peekOffset
        )
    }

    fun parseExpr(minPrecedence: Int = 0): Expr {
        val origin = here()
        var left: Expr = parsePrimaryOrUnaryExpr()
        left = parsePostfix(left)
        if (minPrecedence == 0) {
            val place = tryParsePlaceAssignment(left, origin)
            if (place != null) {
                return place
            }
        }
        while (true) {
            val binOpTokens = tryBinaryOps() ?: arrayOf(peek().type)
            val binaryOpType = BinaryOp.byTokenTypeMaybe(binOpTokens)
            if (binaryOpType == null || binaryOpType.precedence < minPrecedence) {
                break
            }
            val opAt = here()
            repeat(binOpTokens.size) {
                advancePointer() // consume the operators
            }
            // the following binary operators require special parsing of the right hand side so they are put before the others
            if (binaryOpType == BinaryOp.TYPE_CHECK) {
                left = putOrigin(TypeCheckExpr(left, parseType()), origin)
                continue
            }
            if (binaryOpType == BinaryOp.TYPE_CAST) {
                left = castOf(left, origin, opAt)
                continue
            }
            val nextMinPrecedence = binaryOpType.precedence + 1
            val rightOrigin = here()
            var right = parseExpr(nextMinPrecedence)
            // `..` binds looser than `as`: 0..n as Int64 is 0..(n as Int64).
            while (binaryOpType == BinaryOp.RANGE && at(Token.Type.K_AS)) {
                val asAt = here()
                advancePointer()
                right = castOf(right, rightOrigin, asAt)
            }
            left = when (binaryOpType) {
                BinaryOp.CONJUNCTIVE_DOT -> MemberAccessExpr(left, right)
                BinaryOp.RANGE -> RangeExpr(left, right)
                else -> BinaryExpr(left, right, binaryOpType)
            }
        }
        return putOrigin(left, origin)
    }

    /** `operand as Type`, with the pointer just past the `as` at [asAt]. */
    private fun castOf(operand: Expr, origin: SourcePosition, asAt: SourcePosition): Expr {
        if (at(Token.Type.IDENTIFIER) && peek(1).type == Token.Type.S_OPEN_ANGLE && typeArgumentsEnd(1) == null) {
            val type = peek().content
            Diagnostics.panic(
                "LegacyKiraParser::parseExpr",
                "The '<' after 'as $type' opens $type's type arguments, so it is not a comparison here.\n\n" +
                    "Help: put the cast in parentheses: (... as $type) < ...",
                location = asAt,
                selectorLength = 3 + type.length,
                context = context
            )
        }
        return putOrigin(TypeCastExpr(operand, parseType()), origin)
    }

    /**
     * Place assignment (design 2.5). After a postfix expression shaped as a
     * member access or an index -- `a.b`, `a[i]`, `this.x` -- an `=` or a
     * compound operator assigns to that place. Identifier targets keep
     * [AssignmentExpr] and [CompoundAssignmentExpr]. Only the outermost
     * expression (precedence 0) can be an assignment.
     */
    private fun tryParsePlaceAssignment(left: Expr, origin: SourcePosition): Expr? {
        if (left !is MemberAccessExpr && left !is ArrayIndexExpr) {
            return null
        }
        if (at(Token.Type.S_EQUAL)) {
            advancePointer()
            val value = parseExpr()
            return putOrigin(PlaceAssignmentExpr(left, null, value), origin)
        }
        val opTokens = tryCompoundAssignmentOperators()
            ?: if (peek().type in compoundAssignmentTokenTypes) arrayOf(peek().type) else return null
        val op = CompoundAssignmentExpr.findBinaryOp(opTokens) ?: return null
        repeat(opTokens.size) { advancePointer() }
        val value = parseExpr()
        return putOrigin(PlaceAssignmentExpr(left, op, value), origin)
    }

    private fun parsePostfix(baseExpr: Expr): Expr {
        var expr = baseExpr
        while (true) {
            when {
                at(Token.Type.S_OPEN_BRACKET) -> {
                    // array index
                    expectThenAdvance(Token.Type.S_OPEN_BRACKET)
                    val idx = withNoObjectInit(false) { parseExpr() }
                    expectThenAdvance(Token.Type.S_CLOSE_BRACKET)
                    expr = putOrigin(
                        ArrayIndexExpr(expr, idx),
                        here()
                    )
                }

                at(Token.Type.S_DOT) -> {
                    advancePointer()
                    // `a.@_op_get_(i)` / `a.@op_add(b)` (1.3.2, [M5]): an operator intrinsic
                    // (member or free) is a member name here, so it parses as a
                    // MemberAccessExpr; the S_OPEN_PARENTHESIS branch below then wraps it as
                    // a call, the same as `p.dist()`. Any other intrinsic after '.' falls
                    // through to parseIdentifier's ordinary "expected an identifier" error.
                    val member: Expr = if (at(Token.Type.INTRINSIC_IDENTIFIER) && OperatorIntrinsics.isOperatorName(peek().content)) {
                        parseIntrinsicExpr()
                    } else {
                        parseIdentifier()
                    }
                    expr = putOrigin(MemberAccessExpr(expr, member), here())
                }

                // Explicit generic call: name<T, U>(...), not a comparison.
                // Disambiguate by requiring '(' immediately after the matching '>'.
                at(Token.Type.S_OPEN_ANGLE) && looksLikeGenericCall() -> {
                    val typeArgs = parseTypeArgumentList()
                    val params = parseFunctionCallParameter()
                    expr = putOrigin(
                        FunctionCallExpr(expr, params.second, params.first, typeArgs),
                        here()
                    )
                }

                at(Token.Type.S_OPEN_PARENTHESIS) -> {
                    // function call on arbitrary expression (e.g., p.dist())
                    val params = parseFunctionCallParameter()
                    expr = putOrigin(FunctionCallExpr(expr, params.second, params.first), here())
                }

                else -> break
            }
        }
        return expr
    }

    /** `foo<T>(...)`, not a comparison: type arguments at the pointer, then `(`. */
    private fun looksLikeGenericCall(): Boolean =
        typeArgumentsEnd(0)?.let { peek(it + 1).type == Token.Type.S_OPEN_PARENTHESIS } ?: false

    /** The offset of the `>` closing the `<` at [start], when every token between can be part of a type argument. */
    private fun typeArgumentsEnd(start: Int): Int? {
        if (peek(start).type != Token.Type.S_OPEN_ANGLE) {
            return null
        }
        var depth = 0
        var i = start
        while (true) {
            when (peek(i).type) {
                Token.Type.S_OPEN_ANGLE -> depth++
                Token.Type.S_CLOSE_ANGLE -> if (--depth == 0) return i
                in typeArgumentTokens -> {}
                else -> return null
            }
            i++
        }
    }

    private val typeArgumentTokens = setOf(
        Token.Type.IDENTIFIER,
        Token.Type.S_COMMA,
        Token.Type.L_INTEGER,
        Token.Type.K_MODIFIER_MUTABLE,
        Token.Type.S_COLON,
    )

    /** Parse `<T, U>` type-argument list; pointer must be on `<`. */
    private fun parseTypeArgumentList(): List<Type> {
        expectThenAdvance(Token.Type.S_OPEN_ANGLE)
        val args = mutableListOf<Type>()
        while (!at(Token.Type.S_CLOSE_ANGLE) && !at(Token.Type.S_EOF)) {
            args.add(parseType())
            if (!at(Token.Type.S_CLOSE_ANGLE)) {
                expectThenAdvance(Token.Type.S_COMMA)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_ANGLE)
        return args
    }

    private fun parsePrimaryOrUnaryExpr(): Expr {
        return when {
            UnaryOp.byTokenTypeMaybe(peek().type) != null -> parseUnaryExpr()
            else -> parsePrimaryExpr(null)
        }
    }

    fun parsePrimaryExpr(modifier: Map<Modifier, SourcePosition>?): Expr {
        return when (peek().type) {
            // lowkey this hard coded switch statement seems like the best approach, but i get that
            // itch that it will be like redundancy and edge case hell
            Token.Type.L_FLOAT -> parseFloatLiteral()
            Token.Type.L_INTEGER -> parseIntegerLiteral()
            Token.Type.L_STRING -> parseStringLiteral()
            Token.Type.L_STRING_HEAD -> parseInterpolatedStringLiteral()
            Token.Type.L_CHAR -> parseCharLiteral()
            Token.Type.S_OPEN_BRACKET -> parseArrayLiteral()
            Token.Type.INTRINSIC_IDENTIFIER -> {
                // intrinsic tokens are lexed as a single token by the lexer
                val startLoc = peek().canonicalLocation
                val identifier = peek().content
                advancePointer()
                // if followed by '(' parse call-like parameters for intrinsics
                var parameters: List<Expr>? = null
                if (at(Token.Type.S_OPEN_PARENTHESIS)) {
                    parameters = mutableListOf()
                    expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
                    while (!at(Token.Type.S_CLOSE_PARENTHESIS) && !at(Token.Type.S_EOF)) {
                        if (parameters.isNotEmpty()) {
                            expectThenAdvance(Token.Type.S_COMMA)
                        }
                        // A full expression (design 2.5). Measured before:
                        // `@_trace_(LIMIT == 10)` failed with "Expected ','",
                        // because parsePrimaryWithIndexing stops at the operator.
                        parameters.add(withNoObjectInit(false) { parseExpr() })
                    }
                    expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
                }
                val findVal = IntrinsicRegistry.find(identifier)
                return when (findVal != null) {
                    true -> putOrigin(
                        IntrinsicExpr(
                            findVal,
                            startLoc.toLocationFromContext(context),
                            parameters
                        ), startLoc
                    )

                    else -> Diagnostics.panic(
                        "LegacyKiraParser::parsePrimaryExpr",
                        "An intrinsic named '@$identifier' does not exist.",
                        location = startLoc,
                        selectorLength = identifier.length,
                        context = context
                    )
                }
            }

            Token.Type.K_WITH -> parseWithExpr()
            Token.Type.K_MODULE -> parseModuleDecl()
            Token.Type.K_IF -> parseIfExpr()
            Token.Type.K_THIS -> {
                // A fresh node per use: the typed model keys nodes by identity.
                val origin = here()
                advancePointer()
                putOrigin(ThisExpr(), origin)
            }

            Token.Type.K_FX -> {
                // `fx (params) Ret { }` with neither a name nor modifiers is a
                // lambda in expression position; `fx name: ...` stays a
                // FunctionDecl (design 2.4).
                if (peek(1).type == Token.Type.S_OPEN_PARENTHESIS && modifier.isNullOrEmpty()) {
                    parseLambdaExpr()
                } else {
                    parseFunctionDecl(modifier)
                }
            }

            Token.Type.IDENTIFIER ->
                when (peek(1).type) {
                    Token.Type.S_OPEN_PARENTHESIS -> {
                        if (modifier?.isNotEmpty() ?: false) {
                            Diagnostics.panic(
                                "LegacyKiraParser::parseFunctionCallExpr",
                                "Modifiers are not expected on function calls.",
                                location = here(),
                                selectorLength = 1,
                                context = context
                            )
                        }
                        parseFunctionCallExpr()
                    }

                    Token.Type.S_OPEN_BRACE -> {
                        // Type initialization: only treat as object init if the
                        // identifier looks like a type (PascalCase, no `_`) and
                        // we are not in a bare condition head (rule D36).
                        val ident = peek().content
                        return if (looksLikeTypeName(ident) && !noObjectInit) {
                            parseObjectInit()
                        } else {
                            parseIdentifierExpr(modifier)
                        }
                    }

                    Token.Type.S_OPEN_ANGLE -> {
                        val ident = peek().content
                        if (looksLikeTypeName(ident) && !noObjectInit) {
                            var depth = 0
                            var i = 1
                            var foundBrace = false
                            while (i < 100) { // reasonable lookahead limit
                                val t = peek(i).type
                                if (t == Token.Type.S_OPEN_ANGLE) depth++
                                else if (t == Token.Type.S_CLOSE_ANGLE) {
                                    depth--
                                    if (depth == 0) {
                                        if (peek(i + 1).type == Token.Type.S_OPEN_BRACE) {
                                            foundBrace = true
                                        }
                                        break
                                    }
                                } else if (t == Token.Type.S_EOF) break
                                i++
                            }

                            return if (foundBrace) {
                                parseObjectInit()
                            } else {
                                parseIdentifierExpr(modifier)
                            }
                        } else {
                            parseIdentifierExpr(modifier)
                        }
                    }

                    else -> parseIdentifierExpr(modifier)
                }

            Token.Type.OP_SUB, Token.Type.OP_ADD, Token.Type.S_BANG, Token.Type.S_TILDE -> parseUnaryExpr()
            Token.Type.S_OPEN_PARENTHESIS -> {
                advancePointer()
                val expr = withNoObjectInit(false) { parseExpr() }
                expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
                context.astParenthesized.add(expr)
                expr
            }

            else ->
                Diagnostics.panic(
                    "LegacyKiraParser::parsePrimaryExpr",
                    "${EnglishUtils.prependIndefiniteArticle(peek().type.diagnosticsName())} is not allowed here.",
                    location = peek().canonicalLocation,
                    selectorLength = peek().content.length,
                    context = context
                )
        }
    }

    fun parseModuleDecl(): Decl {
        val origin = here()
        expectThenAdvance(Token.Type.K_MODULE)
        val uri = parseStringLiteral()
        return putOrigin(ModuleDecl(uri), origin)
    }

    /**
     * `if c { a } else { b }` in expression position (design 2.4). The else is
     * required, since the expression must have a value for both outcomes; an
     * `else if` nests another [IfExpr] as the single else statement.
     */
    private fun parseIfExpr(): Expr {
        val origin = here()
        expectThenAdvance(Token.Type.K_IF)
        val condition = parseConditionHead()
        val thenBranch = parseStatementBlock()
        if (!at(Token.Type.K_ELSE)) {
            Diagnostics.panic(
                "LegacyKiraParser::parseIfExpr",
                "An if-expression needs an 'else' branch: it must have a value for both outcomes.\n\n" +
                    "Help: write 'if c { a } else { b }', or use an if statement when no value is needed.",
                location = here(),
                selectorLength = max(1, peek().content.length),
                context = context
            )
        }
        advancePointer()
        val elseBranch = if (at(Token.Type.K_IF)) {
            val nestedOrigin = here()
            listOf(putOrigin(Statement(parseIfExpr()), nestedOrigin))
        } else {
            parseStatementBlock()
        }
        return putOrigin(IfExpr(condition, thenBranch, elseBranch), origin)
    }

    /** `fx (params) Ret { body }` in expression position (design 2.4). */
    private fun parseLambdaExpr(): Expr {
        val origin = here()
        expectThenAdvance(Token.Type.K_FX)
        val params = parseFunctionDeclParameters()
        val returnType = parseType()
        val body = parseStatementBlock()
        return putOrigin(LambdaExpr(putOrigin(FunctionDefExpr(returnType, params, body), origin)), origin)
    }

    fun parseUnaryExpr(): Expr {
        val origin = here()
        val operatorToken = peek()
        expectAnyOfThenAdvance(UnaryOp.entries.map { it.tokenType }.toTypedArray())
        // `as` shares this precedence but binds looser: -x as T is (-x) as T.
        val operand = parseExpr(UnaryOp.NEG.precedence + 1)
        return putOrigin(UnaryExpr(UnaryOp.byTokenTypeMaybe(operatorToken.type) {
            Diagnostics.panic(
                "UnaryOperator::byTokenTypeMaybe",
                "$operatorToken is not an unary operator!",
                context = context,
                location = operatorToken.canonicalLocation,
                selectorLength = operatorToken.content.length
            )
        }!!, operand), origin)
    }


    /** The old `with { a = b }` never had a typed meaning: `value with { ... }` replaces it. */
    fun parseWithExpr(): Expr = removedForm("a `with { }`, which never had a typed meaning")

    private fun parseFunctionCallExpr(): FunctionCallExpr {
        val origin = here()
        var identifier by Delegates.notNull<Expr>()
        if (at(Token.Type.INTRINSIC_IDENTIFIER)) {
            identifier = parseIntrinsicExpr()
        } else {
            identifier = parseIdentifier()
        }
        val parameters = parseFunctionCallParameter()
        return putOrigin(FunctionCallExpr(identifier, parameters.second, parameters.first), location = origin)
    }

    fun parseIntrinsicExpr(isFunctionContext: Boolean = false): Expr {
        val startLoc = peek().canonicalLocation
        val identifier = peek().content
        // intrinsic identifiers are lexed as INTRINSIC_IDENTIFIER by the lexer
        expectThenAdvance(Token.Type.INTRINSIC_IDENTIFIER)
        val findVal = IntrinsicRegistry.find(identifier)
        return when (findVal != null) {
            true -> putOrigin(
                IntrinsicExpr(
                    findVal,
                    startLoc.toLocationFromContext(context),
                    null
                ), startLoc
            )

            else -> Diagnostics.panic(
                "LegacyKiraParser::parseIntrinsicExpr",
                "An intrinsic named '@$identifier' does not exist.",
                location = startLoc,
                selectorLength = identifier.length,
                context = context
            )
        }
    }

    fun parseFunctionDeclParameters(): List<FunctionDeclParameterExpr> {
        // could this be also adapted for future implementations of function notations ??
        val parameters = mutableListOf<FunctionDeclParameterExpr>()
        var firstDefaulted: Identifier? = null
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        while (!at(Token.Type.S_CLOSE_PARENTHESIS) && !at(Token.Type.S_EOF)) {
            if (parameters.isNotEmpty()) {
                expectThenAdvance(Token.Type.S_COMMA)
            }
            val modifiers = parseModifiers()
            val origin = here()
            val name = parseIdentifier()
            expectConventionalName(name.value, origin, "Parameter", "camelCase")
            expectThenAdvance(Token.Type.S_COLON)
            val type = parseType()
            // Default parameter (spec Default Parameters): `name: Type = expr`.
            // Whether the expression is constant is the typer's check (D48).
            var defaultValue: Expr? = null
            if (at(Token.Type.S_EQUAL)) {
                advancePointer()
                defaultValue = withNoObjectInit(false) { parseExpr() }
                if (firstDefaulted == null) {
                    firstDefaulted = name
                }
            } else if (firstDefaulted != null) {
                // Spec Default Parameters: "Default parameters must appear
                // after required parameters"; `(port: Int32 = 8080, host: Str)`
                // is its own Error example. A caller could never skip the
                // default without naming everything after it.
                Diagnostics.panic(
                    "LegacyKiraParser::parseFunctionDeclParameters",
                    "A required parameter cannot follow a parameter with a default value: " +
                        "'${name.value}' comes after '${firstDefaulted.value} = ...'. " +
                        "Give '${name.value}' a default, or declare it before the defaulted parameters.",
                    location = origin,
                    selectorLength = name.value.length,
                    context = context
                )
            }
            parameters.add(putOrigin(FunctionDeclParameterExpr(name, type, modifiers.keys.toList(), defaultValue), origin))
        }
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        return parameters
    }

    private fun parseFunctionDecl(modifier: Map<Modifier, SourcePosition>?): FunctionDecl {
        val origin = here()
        // Capture @_extern before expectModifiers / nested parseModifiers clears it.
        val functionIntrinsicsEarly = pendingIntrinsicExprs
        expectModifiers(modifier, WrappingContext.FUNCTION)
        if (modifier?.containsKey(Modifier.OVERRIDE) == true && typeBodyDepth == 0) {
            Diagnostics.panic(
                "LegacyKiraParser::parseFunctionDecl",
                "'override' marks a method of a class or a trait; a function outside a type body overrides nothing.",
                location = modifier[Modifier.OVERRIDE],
                selectorLength = "override".length,
                context = context
            )
        }
        expectThenAdvance(Token.Type.K_FX)
        var functionName: Expr = AnonymousIdentifier
        if (at(Token.Type.INTRINSIC_IDENTIFIER)) {
            functionName = parseIntrinsicExpr(isFunctionContext = true)
        } else if (at(Token.Type.IDENTIFIER)) {
            val nameLocation = here()
            functionName = parseIdentifier()
            expectConventionalName((functionName as Identifier).value, nameLocation, "Function", "camelCase")
        }
        // optional generics: fx name<T, U: Bound>: (...) Ret
        val generics = mutableListOf<Type>()
        if (at(Token.Type.S_OPEN_ANGLE)) {
            expectThenAdvance(Token.Type.S_OPEN_ANGLE)
            while (!at(Token.Type.S_CLOSE_ANGLE) && !at(Token.Type.S_EOF)) {
                val param = parseTypeParameter(declaring = true)
                generics.add(param)
                if (!at(Token.Type.S_CLOSE_ANGLE)) expectThenAdvance(Token.Type.S_COMMA)
            }
            expectThenAdvance(Token.Type.S_CLOSE_ANGLE)
        }
        // `fx name: (params) Ret` -- the colon binds the name to its signature the
        // same way `value: Int32` binds a variable to its type. Anonymous function
        // literals have no name to bind, so they go straight to `fx (params) Ret`.
        if (functionName !== AnonymousIdentifier) {
            expectThenAdvance(Token.Type.S_COLON)
        }
        val params = parseFunctionDeclParameters()
        val returnType = parseType()
        val functionIntrinsics = functionIntrinsicsEarly ?: pendingIntrinsicExprs
        pendingIntrinsicExprs = null
        var body: List<Statement>? = null
        if (at(Token.Type.S_OPEN_BRACE)) {
            body = parseStatementBlock()
        }
        val decl = FunctionDecl(
            functionName,
            FunctionDefExpr(returnType, params, body),
            modifier?.keys?.toList() ?: emptyList(),
            generics,
        )
        markIntrinsics(decl, functionIntrinsics)
        attachIntrinsics(decl)
        return putOrigin(decl, origin)
    }

    private fun parseFunctionCallParameter(): Pair<List<FunctionCallNamedParameterExpr>, List<FunctionCallPositionalParameterExpr>> {
        val named = mutableListOf<FunctionCallNamedParameterExpr>()
        val positional = mutableListOf<FunctionCallPositionalParameterExpr>()
        var positionalIndex = 0
        var seenNamed = false
        expectThenAdvance(Token.Type.S_OPEN_PARENTHESIS)
        withNoObjectInit(false) {
            while (!at(Token.Type.S_CLOSE_PARENTHESIS) && !at(Token.Type.S_EOF)) {
                if (positional.isNotEmpty() || named.isNotEmpty()) {
                    expectThenAdvance(Token.Type.S_COMMA)
                }
                val startToken = peek()
                // Call-site `mut` (design D4): `f(x, mut y)` passes y for
                // mutation. A named argument takes it after the `=`
                // (`f(out = mut y)`); `f(mut out = y)` is accepted as well.
                var isMut = false
                if (at(Token.Type.K_MODIFIER_MUTABLE)) {
                    advancePointer()
                    isMut = true
                }
                if (at(Token.Type.IDENTIFIER) && peek(1).type == Token.Type.S_EQUAL) {
                    seenNamed = true
                    val origin = here()
                    val identifier = parseIdentifier()
                    expectThenAdvance(Token.Type.S_EQUAL)
                    if (at(Token.Type.K_MODIFIER_MUTABLE)) {
                        advancePointer()
                        isMut = true
                    }
                    val expr = parseExpr()
                    if (expr is CompoundAssignmentExpr || expr is AssignmentExpr || expr is PlaceAssignmentExpr) {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseFunctionCallParameter",
                            "Cannot use assignment expressions in function call parameters",
                            context = context,
                            location = startToken.canonicalLocation,
                            selectorLength = startToken.content.length
                        )
                    }
                    named.add(putOrigin(FunctionCallNamedParameterExpr(identifier, expr, isMut), origin))
                } else {
                    if (seenNamed) {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseFunctionCallParameter",
                            "Positional arguments must come before named arguments",
                            context = context,
                            location = startToken.canonicalLocation,
                            selectorLength = startToken.content.length
                        )
                    }
                    val origin = here()
                    // Full expression, matching the named-argument branch above.
                    // parsePrimaryWithIndexing() stops at the first operator, which
                    // made `abs(a - 1)` a parse error while `abs(x = a - 1)` worked.
                    val expr = parseExpr()
                    if (expr is CompoundAssignmentExpr || expr is AssignmentExpr || expr is PlaceAssignmentExpr) {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseFunctionCallParameter",
                            "Cannot use assignment expressions in function call parameters",
                            context = context,
                            location = startToken.canonicalLocation,
                            selectorLength = startToken.content.length
                        )
                    }
                    positional.add(putOrigin(FunctionCallPositionalParameterExpr(positionalIndex++, expr, isMut), origin))
                }
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
        return named to positional
    }


    private fun parseIdentifierExpr(modifier: Map<Modifier, SourcePosition>?): Expr {
        // Compound assignment: >>= / >>>= are multi-token (angle brackets are
        // shared with generics), the rest are single OP_ASSIGN_* tokens. All of
        // them must route here; parseExpr would otherwise read `a += b` as a
        // plain BinaryExpr and silently drop the assignment.
        if (tryCompoundAssignmentOperators(1) != null ||
            peek(1).type in compoundAssignmentTokenTypes
        ) {
            return parseCompoundAssignmentExpr()
        }
        return when (peek(1).type) {
            Token.Type.S_EQUAL -> parseAssignmentExpr()
            Token.Type.S_COLON -> parseVariableDecl(modifier)
            else -> parseIdentifier()
        }
    }

    private val compoundAssignmentTokenTypes = setOf(
        Token.Type.OP_ASSIGN_MUL,
        Token.Type.OP_ASSIGN_DIV,
        Token.Type.OP_ASSIGN_ADD,
        Token.Type.OP_ASSIGN_SUB,
        Token.Type.OP_ASSIGN_MOD,
        Token.Type.OP_ASSIGN_BIT_XOR,
        Token.Type.OP_ASSIGN_BIT_SHL,
        Token.Type.OP_ASSIGN_BIT_OR,
        Token.Type.OP_ASSIGN_BIT_AND,
    )

    fun parseUseStatement(): Statement {
        val origin = here()
        expectThenAdvance(Token.Type.K_USE)
        val uri = parseStringLiteral()
        return putOrigin(UseStatement(uri), origin)
    }

    // helper function for dealing with compound assignment operators that utilize the closing angle bracket (which is a pain to disambiguate) <-- this is a fucking word!?!?!
    fun tryCompoundAssignmentOperators(peekOffset: Int = 0): Array<Token.Type>? {
        return tryInOrder(
            arrayOf(
                arrayOf(
                    Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_EQUAL
                ), arrayOf(
                    Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_EQUAL
                )
            ),
            peekOffset,
        )
    }

    fun parseCompoundAssignmentExpr(): CompoundAssignmentExpr {
        val origin = here()
        val left = parseIdentifier() // todo: allow for more than just identifiers for now
        val opTokens: Array<Token.Type> = tryCompoundAssignmentOperators() ?: arrayOf(peek().type)
        val op = CompoundAssignmentExpr.findBinaryOp(opTokens)
        repeat(opTokens.size) { advancePointer() }
        val right = parseExpr()
        return putOrigin(CompoundAssignmentExpr(left, op!!, right), origin)
    }


    fun parseAssignmentExpr(): AssignmentExpr {
        val origin = here()
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_EQUAL)
        // Full precedence-climbing parse, matching declaration initializers and
        // compound assignment. The old flat parseBinaryExpr() had no precedence
        // and treated '.' as a binary operator, so `n = n + p.get()` built
        // MemberAccess(BinaryExpr(n, p), get) -- i.e. `(n + p).get()`.
        val value = parseExpr()
//        if (identifier !is Identifier) {
//            Diagnostics.panic(
//                "LegacyKiraParser::parseAssignmentExpr",
//                "Assignment expressions can only use identifiers for l-value.",
//                context = context,
//                location = context.astOrigins[identifier] ?: origin,
//            )
//        }
        return putOrigin(AssignmentExpr(identifier, value), origin)
    }

    /**
     * The spec's naming conventions, the part the lexer cannot check: an
     * underscore is only ever part of an UPPER_SNAKE_CASE constant (a
     * variable, field or enum member), never of a type, function, parameter
     * or type-parameter name. The lexer already rejected every other shape.
     */
    private fun expectConventionalName(name: String, location: SourcePosition, what: String, convention: String) {
        if (!name.contains(Symbols.UNDERSCORE.rep)) {
            return
        }
        Diagnostics.panic(
            "LegacyKiraParser::expectConventionalName",
            "$what '$name' does not conform to $convention naming convention: " +
                "underscores are reserved for UPPER_SNAKE_CASE constants and @intrinsics.",
            location = location,
            selectorLength = name.length,
            context = context
        )
    }

    /** The spec's constant shape, as the lexer knows it: `^[A-Z][A-Z0-9_]*$`. */
    private val upperSnakeCase = Regex("[A-Z][A-Z0-9_]*")

    private fun expectConventionalTypeName(type: Type, location: SourcePosition, what: String) {
        val name = (type.identifier as? Identifier)?.value ?: return
        expectConventionalName(name, location, what, "PascalCase")
    }

    fun parseTypeAliasExpr(modifiers: Map<Modifier, SourcePosition>?): TypeAliasDecl {
        expectModifiers(modifiers, WrappingContext.TYPE_ALIAS)
        val origin = here()
        expectThenAdvance(Token.Type.K_ALIAS)
        val aliasType = parseType(declaresGenerics = true)
        expectConventionalTypeName(aliasType, origin, "Type alias")
        expectThenAdvance(Token.Type.K_AS)
        val targetType = parseType()
        val decl = TypeAliasDecl(
            modifiers?.let { modifiers.keys.toList() } ?: emptyList(),
            aliasType,
            targetType
        )
        attachIntrinsics(decl)
        return putOrigin(decl, origin)
    }


    /** The `: A, B` list after a class, struct, variant or trait name; empty without a colon. */
    private fun parseParentList(): List<Type> {
        val parents = mutableListOf<Type>()
        if (!at(Token.Type.S_COLON)) {
            return parents
        }
        advancePointer()
        while (!at(Token.Type.S_OPEN_BRACE) && !at(Token.Type.S_EOF)) {
            parents.add(parseType())
            if (at(Token.Type.S_COMMA)) {
                advancePointer()
            } else {
                break
            }
        }
        return parents
    }

    private class TypeBody(
        val members: List<FirstClassDecl>,
        val initially: List<Statement>?,
    )

    /** How many class, struct or trait bodies enclose the pointer; `override` needs one. */
    private var typeBodyDepth = 0

    /**
     * The `{ ... }` of a class or a struct: fields, methods and at most one `initially { }`
     * (spec Initializers); a `finally { }` is a form the clean dialect removed.
     */
    private fun parseTypeBody(kind: String): TypeBody {
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val members = mutableListOf<FirstClassDecl>()
        var initially: List<Statement>? = null
        typeBodyDepth++
        try {
            parseTypeBodyMembers(kind, members, { initially }, { initially = it })
        } finally {
            typeBodyDepth--
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return TypeBody(members, initially)
    }

    private fun parseTypeBodyMembers(
        kind: String,
        members: MutableList<FirstClassDecl>,
        initially: () -> List<Statement>?,
        setInitially: (List<Statement>) -> Unit,
    ) {
        while (!at(Token.Type.S_CLOSE_BRACE) && !at(Token.Type.S_EOF)) {
            if (at(Token.Type.K_FINALLY)) {
                removedForm("a 'finally' block")
            }
            if (at(Token.Type.K_INITIALLY)) {
                val keyword = peek()
                advancePointer()
                if (initially() != null) {
                    Diagnostics.panic(
                        "LegacyKiraParser::parseTypeBody",
                        "A $kind may have only one '${keyword.content}' block.",
                        location = keyword.canonicalLocation,
                        selectorLength = keyword.content.length,
                        context = context
                    )
                }
                val block = parseStatementBlock()
                expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                setInitially(block)
                continue
            }
            val memberModifiers = parseModifiers()
            expectModifiers(memberModifiers, WrappingContext.CLASS_MEMBER)
            members.add(
                if (at(Token.Type.S_OPEN_PARENTHESIS)) // check for simple/anonymous function literals
                {
                    Diagnostics.panic(
                        "LegacyKiraParser::parseClassDecl",
                        "Anonymous Function Literals are not allowed by themselves in a $kind.",
                        location = peek().canonicalLocation,
                        selectorLength = peek().content.length,
                        context = context
                    )
                } else if (peek(1).type == Token.Type.S_COLON) {
                    val valDecl = parseVariableDecl(memberModifiers)
                    expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                    valDecl
                } else {
                    val funcDecl = parseFunctionDecl(memberModifiers)
                    expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
                    funcDecl
                }
            )
        }
    }

    fun parseClassDecl(modifier: Map<Modifier, SourcePosition>?): ClassDecl {
        // Capture @_opaque / @_magic before expectModifiers parses `pub` and
        // clears pendingIntrinsics via a fresh parseModifiers path.
        val classIntrinsicsEarly = pendingIntrinsicExprs
        expectModifiers(modifier, WrappingContext.CLASS)
        advancePointer() //consume the class keyword
        val origin = here()
        val className = parseType(declaresGenerics = true)
        expectConventionalTypeName(className, origin, "Class")
        val parenTypes = parseParentList()
        if (!at(Token.Type.S_OPEN_BRACE)) {
            val classDecl = ClassDecl(className, modifier?.keys?.toList() ?: emptyList(), emptyList(), parenTypes)
            val marks = classIntrinsicsEarly ?: pendingIntrinsicExprs
            if (marks != null) {
                markIntrinsics(classDecl, marks)
                pendingIntrinsicExprs = null
            }
            attachIntrinsics(classDecl)
            return putOrigin(classDecl, origin)
        }
        val classIntrinsics = classIntrinsicsEarly ?: pendingIntrinsicExprs
        pendingIntrinsicExprs = null
        val body = parseTypeBody("class")
        val classDecl = ClassDecl(
            className,
            modifier?.keys?.toList() ?: emptyList(),
            body.members,
            parenTypes,
            body.initially,
        )
        markIntrinsics(classDecl, classIntrinsics)
        attachIntrinsics(classDecl)
        return putOrigin(classDecl, origin)
    }

    /**
     * `struct Name: Trait, ... { members; initially { } }` (design D1): the
     * class body grammar, an optional trait list, no finalizer. A body-less
     * `struct Name` declares an empty struct, as a body-less class does.
     */
    fun parseStructDecl(modifier: Map<Modifier, SourcePosition>?): StructDecl {
        val structIntrinsicsEarly = pendingIntrinsicExprs
        expectModifiers(modifier, WrappingContext.CLASS)
        expectThenAdvance(Token.Type.K_STRUCT)
        val origin = here()
        val structName = parseType(declaresGenerics = true)
        expectConventionalTypeName(structName, origin, "Struct")
        val traits = parseParentList()
        val structIntrinsics = structIntrinsicsEarly ?: pendingIntrinsicExprs
        pendingIntrinsicExprs = null
        val body = if (at(Token.Type.S_OPEN_BRACE)) {
            parseTypeBody("struct")
        } else {
            TypeBody(emptyList(), null)
        }
        val decl = StructDecl(structName, modifier?.keys?.toList() ?: emptyList(), body.members, traits, body.initially)
        markIntrinsics(decl, structIntrinsics)
        attachIntrinsics(decl)
        return putOrigin(decl, origin)
    }

    fun parseTraitDecl(modifier: Map<Modifier, SourcePosition>?): TraitDecl {
        expectModifiers(modifier, WrappingContext.TRAIT)
        val baseLocation = here()
        var seenAnonymous = false
        expectThenAdvance(Token.Type.K_TRAIT)
        val name = parseType(declaresGenerics = true)
        expectConventionalTypeName(name, baseLocation, "Trait")
        val parenTypes = parseParentList()
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val traitIntrinsics = pendingIntrinsicExprs
        pendingIntrinsicExprs = null
        val members = mutableListOf<FunctionDecl>()
        typeBodyDepth++
        try {
        while (!at(Token.Type.S_CLOSE_BRACE) && !at(Token.Type.S_EOF)) {
            val memberModifiers = parseModifiers()
            expectModifiers(memberModifiers, WrappingContext.TRAIT_MEMBER)
            val memberExpr = parseFunctionDecl(memberModifiers)
            // check if we have seen an anonymous function against this member (we only permit one anonymous function)
            if (memberExpr.isAnonymous()) {
                if (seenAnonymous) {
                    Diagnostics.panic(
                        "LegacyKiraParser::parseTraitDecl",
                        "Traits only allow 1 anonymous function. Either give this function name or remove others.",
                        context = context,
                        location = context.astOrigins[memberExpr],
                        selectorLength = context.findCanonicalLine(context.astOrigins[memberExpr]!!.lineNumber).length
                    )
                }
                seenAnonymous = true
            }
            members.add(memberExpr)
            expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
        }
        } finally {
            typeBodyDepth--
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        if (members.isEmpty()) {
            Diagnostics.Logging.warn(
                "LegacyKiraParser::parseTraitDecl",
                "Found trait $name with no members."
            )
        }
        val traitDecl = TraitDecl(name, modifier?.keys?.toTypedArray() ?: emptyArray(), members, parenTypes)
        markIntrinsics(traitDecl, traitIntrinsics)
        return putOrigin(traitDecl, baseLocation)
    }

    fun parseVariableDecl(modifier: Map<Modifier, SourcePosition>?): VariableDecl {
        expectModifiers(modifier, WrappingContext.VARIABLE)
        val origin = here()
        val identifier = parseIdentifier()
        expectThenAdvance(Token.Type.S_COLON)
        val type = parseType()
        var value: Expr? = null
        if (at(Token.Type.S_EQUAL)) {
            advancePointer()
            value = parseExpr()
        }
//        Diagnostics.Logging.info("LOG", "${context.file} @ $origin -> ${identifier.value}")
        val decl = VariableDecl(identifier, type, value, modifier?.keys?.toList() ?: emptyList())
        attachIntrinsics(decl)
        return putOrigin(decl, origin)
    }


    fun parseStringLiteral(): StringLiteral {
        val token = peek()
        val origin = here()
        expectThenAdvance(Token.Type.L_STRING)
        return putOrigin(StringLiteral(decodeStringEscapes(token)), origin)
    }

    /**
     * `"text ${expr} text"` (spec String Interpolation). The lexer hands over
     * HEAD expr (PART expr)* TAIL; each text piece is decoded like a plain
     * string (so `\$` is a literal dollar) and empty pieces are dropped.
     */
    private fun parseInterpolatedStringLiteral(): InterpolatedStringLiteral {
        val origin = here()
        val parts = mutableListOf<InterpolationPart>()
        fun addText(token: Token) {
            val text = decodeStringEscapes(token)
            if (text.isNotEmpty()) {
                parts.add(InterpolationPart.Text(text))
            }
        }
        val head = peek()
        expectThenAdvance(Token.Type.L_STRING_HEAD)
        addText(head)
        while (true) {
            parts.add(InterpolationPart.Hole(withNoObjectInit(false) { parseExpr() }))
            val piece = peek()
            when (piece.type) {
                Token.Type.L_STRING_PART -> {
                    advancePointer()
                    addText(piece)
                }

                Token.Type.L_STRING_TAIL -> {
                    advancePointer()
                    addText(piece)
                    break
                }

                else -> Diagnostics.panic(
                    "LegacyKiraParser::parseInterpolatedStringLiteral",
                    "Expected the string to continue after the '\${' hole, but got " +
                        "${EnglishUtils.prependIndefiniteArticle(piece.type.diagnosticsName())}.\n\n" +
                        "Help: a hole holds one expression; close it with '}'.",
                    location = piece.canonicalLocation,
                    selectorLength = max(1, piece.content.length),
                    context = context
                )
            }
        }
        return putOrigin(InterpolatedStringLiteral(parts), origin)
    }

    /** `'c'` (design D3): one byte, with the escapes `\n \t \r \\ \' \0`. */
    fun parseCharLiteral(): CharLiteral {
        val token = peek()
        val origin = here()
        expectThenAdvance(Token.Type.L_CHAR)
        val raw = token.content
        val value = if (raw.startsWith("\\")) {
            when (raw.getOrNull(1)) {
                'n' -> '\n'.code
                't' -> '\t'.code
                'r' -> '\r'.code
                '\\' -> '\\'.code
                '\'' -> '\''.code
                '0' -> 0
                else -> Diagnostics.panic(
                    "LegacyKiraParser::parseCharLiteral",
                    "Unknown escape sequence '$raw' in a Char literal. Kira knows \\n, \\t, \\r, \\\\, \\' and \\0.",
                    location = token.canonicalLocation,
                    selectorLength = raw.length + 2,
                    context = context
                )
            }
        } else {
            raw.single().code
        }
        if (value > 255) {
            Diagnostics.panic(
                "LegacyKiraParser::parseCharLiteral",
                "A Char is one byte (0..255); '$raw' is code point $value.",
                location = token.canonicalLocation,
                selectorLength = raw.length + 2,
                context = context
            )
        }
        return putOrigin(CharLiteral(value), origin)
    }

    /**
     * Turn the lexer's raw string body into the characters it denotes. The
     * literal holds real characters from here on; each backend re-escapes
     * them for its own language. The escapes are the spec's: `\n \t \r \\ \"`
     * and `\$` (a literal dollar).
     */
    private fun decodeStringEscapes(token: Token): String {
        val raw = token.content
        if (!raw.contains('\\')) {
            return raw
        }
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            val next = raw.getOrNull(i + 1)
            val decoded = when (next) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                '\\' -> '\\'
                '"' -> '"'
                '$' -> '$'
                else -> Diagnostics.panic(
                    "LegacyKiraParser::parseStringLiteral",
                    "Unknown escape sequence '\\${next ?: ""}' in string literal. " +
                        "Kira knows \\n, \\t, \\r, \\\\, \\\" and \\$.",
                    location = token.canonicalLocation,
                    selectorLength = raw.length + 2,
                    context = context
                )
            }
            sb.append(decoded)
            i += 2
        }
        return sb.toString()
    }

    fun parseArrayLiteral(): ArrayLiteral {
        val origin = here()
        expectThenAdvance(Token.Type.S_OPEN_BRACKET)
        val elements = mutableListOf<Expr>()
        withNoObjectInit(false) {
            while (!at(Token.Type.S_CLOSE_BRACKET) && !at(Token.Type.S_EOF)) {
                elements.add(parsePrimaryExpr(null))
                if (!at(Token.Type.S_CLOSE_BRACKET)) {
                    expectThenAdvance(Token.Type.S_COMMA)
                }
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACKET)
        return putOrigin(ArrayLiteral(elements.toTypedArray()), origin)
    }

    fun parseIntegerLiteral(): IntegerLiteral {
        var value by Delegates.notNull<Long>()
        val origin = here()
        try {
            value = peek().content.toLong()
        } catch (e: Exception) {
            Diagnostics.panic(
                "LegacyKiraParser::parseIntegerLiteral",
                "Unable to read '${peek().content}' as an integer literal",
                cause = e,
                location = origin,
                selectorLength = peek().content.length,
                context = context
            )
        }
        expectThenAdvance(Token.Type.L_INTEGER)
        return putOrigin(IntegerLiteral(value), origin)
    }

    fun parseFloatLiteral(): FloatLiteral {
        var value by Delegates.notNull<Double>()
        val origin = here()
        try {
            value = peek().content.toDouble()
        } catch (e: Exception) {
            Diagnostics.panic(
                "LegacyKiraParser::parseIntegerLiteral",
                "Unable to read '${peek().content}' as an integer literal",
                cause = e,
                location = peek().canonicalLocation,
                selectorLength = peek().content.length,
                context = context
            )
        }
        expectThenAdvance(Token.Type.L_FLOAT)
        return putOrigin(FloatLiteral(value), origin)
    }

    fun parseIdentifier(): Identifier {
        val loc = peek().canonicalLocation
        val value = peek().content
        expectThenAdvance(Token.Type.IDENTIFIER)
        return putOrigin(Identifier(value), loc)
    }

    /**
     * One entry of a `<...>` list: a type argument (`Arr<UInt8, N>`), or with
     * [declaring] a type parameter that a declaration introduces (`class
     * Box<T>`, `fx f<T: Bound>`). [enclosing] is the name of the type whose
     * argument list this is (null for a function's own generics).
     *
     * A declared parameter is a PascalCase name, as it always was: the const
     * argument, the `mut` argument and the UPPER_SNAKE constant name are
     * argument-list forms. Measured before: `class Box<T_X>` parsed.
     */
    private fun parseTypeParameter(enclosing: String? = null, declaring: Boolean = false): Type {
        val baseLocation = here()
        // An integer literal in a type-argument list is a const argument
        // (design D6): `Arr<UInt8, 32>`. A constant name stays a Type.
        if (!declaring && at(Token.Type.L_INTEGER)) {
            return putOrigin(ConstTypeArg(parseIntegerLiteral()), baseLocation)
        }
        // `mut T` inside Tuple*<...> marks an Fx parameter passed for
        // mutation (design D24); anywhere else it means nothing.
        var isMutParam = false
        if (!declaring && at(Token.Type.K_MODIFIER_MUTABLE)) {
            if (enclosing == null || !enclosing.startsWith("Tuple")) {
                Diagnostics.panic(
                    "LegacyKiraParser::parseTypeParameter",
                    "'mut' in a type argument is only meaningful inside a Tuple's arguments, " +
                        "as an Fx parameter type: Fx<Tuple1<mut T>, Void>.",
                    location = baseLocation,
                    selectorLength = peek().content.length,
                    context = context
                )
            }
            advancePointer()
            isMutParam = true
        }
        var bound: Type? = null
        fun acquireTypeBound() {
            if (at(Token.Type.S_COLON)) {
                advancePointer()
                bound = parseType()
            }
        }

        val baseIdentifier = parseIdentifier()
        // In a named type's argument list a constant name (`Arr<UInt8,
        // USER_CMD_BYTES>`, design D6) is an UPPER_SNAKE value, not a type
        // parameter; it stays a Type and the typer resolves it. A declared
        // parameter (a declaration's generics) keeps the PascalCase rule.
        if (declaring || enclosing == null || !upperSnakeCase.matches(baseIdentifier.value)) {
            expectConventionalName(baseIdentifier.value, baseLocation, "Type parameter", "PascalCase")
        }
        val children = mutableListOf<Type>()
        if (at(Token.Type.S_OPEN_ANGLE)) {
            expectThenAdvance(Token.Type.S_OPEN_ANGLE)
            while (!at(Token.Type.S_CLOSE_ANGLE) && !at(Token.Type.S_EOF)) {
                val param = parseTypeParameter(baseIdentifier.value, declaring)
                children.add(param)
                if (!at(Token.Type.S_CLOSE_ANGLE)) {
                    expectThenAdvance(Token.Type.S_COMMA)
                }
            }
            expectThenAdvance(Token.Type.S_CLOSE_ANGLE)
        } else {
            acquireTypeBound()
        }

        return putOrigin(Type(baseIdentifier, bound, children, isMutParam), baseLocation)
    }


    /**
     * A type reference, or with [declaresGenerics] the name of a class, struct,
     * trait, variant or alias declaration, whose `<...>` entries are the type
     * parameters it introduces rather than arguments.
     */
    fun parseType(declaresGenerics: Boolean = false): Type {
        val baseLocation = here()
        val baseIdentifier = parseIdentifier()
        if (!at(Token.Type.S_OPEN_ANGLE)) {
            return putOrigin(Type(baseIdentifier), baseLocation)
        }
        expectThenAdvance(Token.Type.S_OPEN_ANGLE)
        val children = mutableListOf<Type>()
        while (!at(Token.Type.S_CLOSE_ANGLE) && !at(Token.Type.S_EOF)) {
            val param = parseTypeParameter(baseIdentifier.value, declaring = declaresGenerics)
            children.add(param)
            if (!at(Token.Type.S_CLOSE_ANGLE)) {
                expectThenAdvance(Token.Type.S_COMMA)
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_ANGLE)
        return putOrigin(Type(baseIdentifier, null, children), baseLocation)

    }

    /** A keyword modifier or an intrinsic of a declaration head, as written. */
    private class HeadWord(val text: String, val at: SourcePosition, val isIntrinsic: Boolean)

    fun parseModifiers(): Map<Modifier, SourcePosition> {
        val modifier = mutableMapOf<Modifier, SourcePosition>()
        val intrinsics = mutableListOf<IntrinsicExpr>()
        val head = mutableListOf<HeadWord>()
        while (true) {
            when {
                at(Token.Type.INTRINSIC_IDENTIFIER) -> {
                    val startLoc = here()
                    val startToken = peek()
                    var endOffset = startToken.pointerPosition + startToken.content.length
                    val intrinsicName = peek().content
                    val intrinsic = IntrinsicRegistry.find(intrinsicName)
                        ?: Diagnostics.panic(
                            "LegacyKiraParser::parseModifiers",
                            "Unknown intrinsic: @$intrinsicName",
                            context = context,
                            location = peek().canonicalLocation,
                            selectorLength = peek().content.length,
                        )
                    advancePointer()
                    // Marker arguments (design 2.5): `( args )` where each
                    // argument is `name = literal` or a literal. Measured
                    // before: the parentheses became an expression statement.
                    var parameters: List<Expr>? = null
                    val named = linkedMapOf<String, Expr>()
                    if (at(Token.Type.S_OPEN_PARENTHESIS)) {
                        advancePointer()
                        val positional = mutableListOf<Expr>()
                        while (!at(Token.Type.S_CLOSE_PARENTHESIS) && !at(Token.Type.S_EOF)) {
                            if (positional.isNotEmpty() || named.isNotEmpty()) {
                                expectThenAdvance(Token.Type.S_COMMA)
                            }
                            if (at(Token.Type.IDENTIFIER) && peek(1).type == Token.Type.S_EQUAL) {
                                val name = parseIdentifier()
                                advancePointer()
                                named[name.value] = parseMarkerArgument(intrinsicName)
                            } else {
                                if (named.isNotEmpty()) {
                                    Diagnostics.panic(
                                        "LegacyKiraParser::parseModifiers",
                                        "Positional arguments must come before named arguments in '@$intrinsicName(...)'.",
                                        context = context,
                                        location = peek().canonicalLocation,
                                        selectorLength = peek().content.length,
                                    )
                                }
                                positional.add(parseMarkerArgument(intrinsicName))
                            }
                        }
                        endOffset = peek().pointerPosition + 1
                        expectThenAdvance(Token.Type.S_CLOSE_PARENTHESIS)
                        parameters = positional
                    }
                    // As written, for the Help line; arguments over several lines read as one.
                    val written = context.content.substring(startToken.pointerPosition - 1, endOffset)
                        .replace(Regex("(?<=\\()\\s*\\n\\s*|\\s*\\n\\s*(?=\\))"), "").replace(Regex("\\s*\\n\\s*"), " ")
                    head.add(HeadWord(written, startLoc.offsetBy(0, -1), isIntrinsic = true))
                    intrinsics.add(
                        putOrigin(
                            IntrinsicExpr(intrinsic, startLoc.toLocationFromContext(context), parameters, named),
                            startLoc
                        )
                    )
                }

                peek().type in Token.Type.modifiers -> {
                    val currentModifier = Modifier.byTokenTypeMaybe(peek().type) {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseModifiers",
                            "${peek()} is not a valid modifier",
                            context = context,
                            location = peek().canonicalLocation,
                            selectorLength = peek().content.length,
                        )
                    }
                    modifier[currentModifier]?.let {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseModifiers", "Duplicate modifier at ${peek().canonicalLocation}",
                            context = context,
                            location = peek().canonicalLocation,
                            selectorLength = peek().content.length,
                        )
                    }
                    modifier[currentModifier!!] = peek().canonicalLocation
                    head.add(HeadWord(peek().content, peek().canonicalLocation, isIntrinsic = false))
                    advancePointer()
                }

                else -> break
            }
        }
        if (intrinsics.isNotEmpty()) {
            if (peek().type in declarationStarters) {
                expectIntrinsicSlot(head)
            }
            pendingIntrinsicExprs = intrinsics
        }
        return modifier
    }

    /**
     * A declaration intrinsic is keyword-level: written after every keyword modifier, on the
     * line of the declaration keyword or name it marks (`pub @_opaque class Tag {`).
     */
    private fun expectIntrinsicSlot(head: List<HeadWord>) {
        val target = peek().canonicalLocation
        val marks = head.filter { it.isIntrinsic }
        val apart = marks.firstOrNull { it.at.lineNumber != target.lineNumber }
        val first = head.indexOfFirst { it.isIntrinsic }
        val modifierAfter = head.drop(first).firstOrNull { !it.isIntrinsic }
        if (apart == null && modifierAfter == null) {
            return
        }
        val rest = context.findCanonicalLine(target.lineNumber).substring(target.column - 1).trimEnd()
        val rewritten = (head.filter { !it.isIntrinsic } + marks).joinToString(" ") { it.text } + " " + rest
        val (code, offending, message) = when {
            apart == null -> Triple(
                "parse.intrinsic.order", head[first],
                "'${head[first].text}' is written before the modifier '${modifierAfter!!.text}'; an intrinsic follows every keyword modifier."
            )
            head.none { !it.isIntrinsic && it.at.lineNumber == apart.at.lineNumber && it.at < apart.at } -> Triple(
                "parse.intrinsic.line", apart,
                "'${apart.text}' stands on a line of its own; an intrinsic is keyword-level, written on the line of the declaration it marks."
            )
            else -> Triple(
                "parse.intrinsic.newline", apart,
                "A newline separates '${apart.text}' from the declaration it marks; an intrinsic is keyword-level, written on the declaration's line."
            )
        }
        Diagnostics.panic(
            code,
            "$message\n\nHelp: write it as '$rewritten'.",
            location = offending.at,
            selectorLength = max(1, offending.text.length),
            context = context
        )
    }

    /**
     * An argument of a marker intrinsic is a literal: a string, an integer or
     * a float, optionally signed. `@_extern(cpp = "f")`, `@_align(16)`.
     */
    private fun parseMarkerArgument(intrinsicName: String): Expr {
        val start = peek()
        var value = parsePrimaryOrUnaryExpr()
        if (value is UnaryExpr && (value.operator == UnaryOp.NEG || value.operator == UnaryOp.POS)) {
            val negate = value.operator == UnaryOp.NEG
            value = when (val operand = value.operand) {
                is IntegerLiteral -> putOrigin(IntegerLiteral(if (negate) -operand.value else operand.value), start.canonicalLocation)
                is FloatLiteral -> putOrigin(FloatLiteral(if (negate) -operand.value else operand.value), start.canonicalLocation)
                else -> value
            }
        }
        if (value !is SimpleLiteral) {
            Diagnostics.panic(
                "LegacyKiraParser::parseModifiers",
                "'@$intrinsicName' takes literal arguments (a string, an integer or a float), " +
                    "as 'name = literal' or a bare literal.",
                location = start.canonicalLocation,
                selectorLength = max(1, start.content.length),
                context = context
            )
        }
        return value
    }

    /**
     * The marker intrinsics parsed by the last [parseModifiers] and not yet
     * attached to a declaration. They land on the next declaration through
     * [markIntrinsics] / [attachIntrinsics].
     */
    private var pendingIntrinsicExprs: List<IntrinsicExpr>? = null

    /**
     * Records [invocations] as [node]'s marks: the registry entries in
     * `astIntrinsicMarked` (what every existing reader consults) and the
     * full invocations, arguments included, in `astIntrinsicInvocations`.
     */
    private fun <T : ASTNode> markIntrinsics(node: T, invocations: List<IntrinsicExpr>?) {
        if (invocations.isNullOrEmpty()) {
            return
        }
        context.astIntrinsicMarked[node] = invocations.map { it.intrinsicKey }.toTypedArray()
        context.astIntrinsicInvocations[node] = invocations
    }

    private fun <T : ASTNode> attachIntrinsics(node: T): T {
        val pending = pendingIntrinsicExprs ?: return node
        markIntrinsics(node, pending)
        pendingIntrinsicExprs = null
        return node
    }

    /**
     * `T { a, b }` and the spec's named construction `T { y = 2, x = 1 }`:
     * positional arguments first, then named. Measured before: `Esc { pulse = 0 }`
     * parsed as a positional assignment expression.
     */
    private fun parseObjectInit(): ObjectInitExpr {
        val origin = here()
        val typeName = parseType()
        expectThenAdvance(Token.Type.S_OPEN_BRACE)
        val args = mutableListOf<Expr>()
        val named = mutableListOf<FunctionCallNamedParameterExpr>()
        withNoObjectInit(false) {
            while (!at(Token.Type.S_CLOSE_BRACE) && !at(Token.Type.S_EOF)) {
                if (args.isNotEmpty() || named.isNotEmpty()) {
                    expectThenAdvance(Token.Type.S_COMMA)
                }
                val startToken = peek()
                if (at(Token.Type.IDENTIFIER) && peek(1).type == Token.Type.S_EQUAL) {
                    val argOrigin = here()
                    val name = parseIdentifier()
                    advancePointer()
                    named.add(putOrigin(FunctionCallNamedParameterExpr(name, parseExpr()), argOrigin))
                } else {
                    if (named.isNotEmpty()) {
                        Diagnostics.panic(
                            "LegacyKiraParser::parseObjectInit",
                            "Positional arguments must come before named arguments in a construction",
                            context = context,
                            location = startToken.canonicalLocation,
                            selectorLength = max(1, startToken.content.length)
                        )
                    }
                    args.add(parseExpr())
                }
            }
        }
        expectThenAdvance(Token.Type.S_CLOSE_BRACE)
        return putOrigin(ObjectInitExpr(typeName, args, named), origin)
    }

    private fun parsePrimaryWithIndexing(): Expr {
        val base = parsePrimaryOrUnaryExpr()
        return parsePostfix(base)
    }

    fun parseThrowStatement(): Statement {
        val origin = here()
        if (at(Token.Type.K_THROW)) {
            expectThenAdvance(Token.Type.K_THROW)
        } else if (at(Token.Type.IDENTIFIER) && peek().content == "throw") {
            advancePointer()
        } else {
            Diagnostics.panic(
                "LegacyKiraParser::parseThrowStatement",
                "Expected 'throw' here.",
                location = here(),
                context = context
            )
        }
        val expr = parseExpr()
        expectOptionalThenAdvance(Token.Type.S_SEMICOLON)
        val throwNode = putOrigin(ThrowExpr(expr), origin)
        return putOrigin(Statement(throwNode), origin)
    }

    fun parseTryStatement(): Statement {
        val origin = here()
        if (at(Token.Type.K_TRY)) {
            expectThenAdvance(Token.Type.K_TRY)
        } else if (at(Token.Type.IDENTIFIER) && peek().content == "try") {
            advancePointer()
        } else {
            Diagnostics.panic(
                "LegacyKiraParser::parseTryStatement",
                "Expected 'try' here.",
                location = here(),
                context = context
            )
        }
        val tryBlock = parseStatementBlock()
        if (at(Token.Type.K_ON) || (at(Token.Type.IDENTIFIER) && peek().content == "on")) {
            if (at(Token.Type.K_ON)) expectThenAdvance(Token.Type.K_ON) else advancePointer()
            val name = parseIdentifier()
            expectThenAdvance(Token.Type.S_COLON)
            val type = parseType()
            val handlerBlock = parseStatementBlock()
            val tryNode = putOrigin(
                TryExpr(
                    tryBlock,
                    name,
                    type,
                    handlerBlock
                ), origin
            )
            return putOrigin(Statement(tryNode), origin)
        }
        val tryNode = putOrigin(
            TryExpr(
                tryBlock,
                null,
                null,
                emptyList()
            ), origin
        )
        return putOrigin(Statement(tryNode), origin)
    }
}
