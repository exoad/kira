package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TernaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfElseBranchStatementNode
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.source.SourceContext
import java.util.IdentityHashMap

/**
 * Stage 3's return rewrites on one file's stage 2 text, by token edits, each with the tree it means for [ReturnProof];
 * a function the rules do not cover keeps its returns and is listed in [hand].
 */
class ReturnRewrite(private val text: String, private val ctx: SourceContext) {
    /** [start, end) of the text replaced by [text]; its new lines take [originLine]'s indentation shift. */
    class Ed(val start: Int, val end: Int, val text: String, val unit: Int, val originLine: Int)

    /** Lines [from, to] (1-based) one level deeper, for [unit]. */
    class Ind(val from: Int, val to: Int, val unit: Int)

    /** A function body the rules run on: a declared function's or a lambda's. */
    class Unit(val id: Int, val def: FunctionDefExpr, val valued: Boolean, val open: Int, val close: Int, val parent: Unit?, val line: Int) {
        val top: Unit get() = parent?.top ?: this
    }

    private class Refuse(val why: String, val at: ASTNode?) : Exception(why)

    val edits = mutableListOf<Ed>()
    val indents = mutableListOf<Ind>()
    val units = mutableListOf<Unit>()

    /** An old block's statements as the new text must read them, with the unit that wrote it. */
    val expectedLists: IdentityHashMap<List<*>, Pair<List<Statement>, Int>> = IdentityHashMap()

    /** An old node (an if value) as the new text must read it. */
    val expectedNodes: IdentityHashMap<ASTNode, Pair<ASTNode, Int>> = IdentityHashMap()

    /** Whether a node the rewrite made, or an old one it moved, is written in parentheses. */
    val parens: IdentityHashMap<ASTNode, Boolean> = IdentityHashMap()

    /** Each function the rules leave for a hand fix: its line and why. */
    val hand = mutableListOf<Pair<Int, String>>()
    val stats = LinkedHashMap<String, Int>()

    /** Top-level units the proof refused: their rewrites are not applied. */
    val excluded = mutableSetOf<Int>()

    /** A problem with the file as a whole, or null. */
    var fileProblem: String? = null

    private val toks: List<Token> = ctx.tokens
    private val lineStarts = DialectConverter.lineStartsOf(text)
    private val indexAt = HashMap<Pair<Int, Int>, Int>().also { m ->
        toks.forEachIndexed { i, t -> m.putIfAbsent(t.canonicalLocation.lineNumber to t.canonicalLocation.column, i) }
    }
    private val unitIndent: String = if (text.lines().any { it.startsWith("\t") }) "\t" else "    "
    private val taken = mutableSetOf<String>()
    private val parents = IdentityHashMap<Any, Any>()
    private val returnMemo = IdentityHashMap<Any, Boolean>()
    private var current: Unit? = null

    /** The `{` of each test block (stage 3b), `x == T { check }`: a brace in a condition that opens no body. */
    private val testBraces: Set<Int> by lazy {
        AstWalk.all(ctx.ast).filterIsInstance<TypeCheckExpr>().mapNotNull { e ->
            val at = e.checks?.firstOrNull()?.let { ctx.astOrigins[it] } ?: return@mapNotNull null
            indexAt[at.lineNumber to at.column]?.minus(1)?.takeIf { it >= 0 && toks[it].type == Token.Type.S_OPEN_BRACE }
        }.toSet()
    }

    fun collect() {
        // The preprocessor only cuts each line's `//` comment, so the tokens' lines and columns are the text's.
        val cut = ctx.content.lines()
        val raw = text.lines()
        if (cut.size != raw.size || cut.indices.any { !raw[it].startsWith(cut[it]) }) {
            fileProblem = "the preprocessor moves this file's text"
            return
        }
        checkTokens()?.let {
            fileProblem = it
            return
        }
        linkParents(ctx.ast, null)
        findUnits()
        // If values first, inner before outer, so a body's arms know which values became ternaries.
        ifValues()
        for (u in units) {
            val body = u.def.body ?: continue
            current = u
            // A new local's name: none the module or this function uses.
            if (u.parent == null) {
                taken.clear()
                taken += namesIn(u)
            }
            val before = Snapshot()
            try {
                val out = block(body, Ctx(u.close, Mode.VALUE, u))
                if (out !== body) expectedLists[body] = out to u.id
            } catch (r: Refuse) {
                before.restore()
                hand += (r.at?.let { ctx.astOrigins[it]?.lineNumber } ?: u.line) to r.why
            }
        }
        current = null
    }

    /** What is recorded so far, to undo a unit's partial work when it is refused. */
    private inner class Snapshot {
        val e = edits.size
        val i = indents.size
        val lists = IdentityHashMap(expectedLists)
        val nodes = IdentityHashMap(expectedNodes)
        val ps = IdentityHashMap(parens)
        val st = LinkedHashMap(stats)
        val names = HashSet(taken)
        fun restore() {
            while (edits.size > e) edits.removeAt(edits.size - 1)
            while (indents.size > i) indents.removeAt(indents.size - 1)
            expectedLists.clear(); expectedLists.putAll(lists)
            expectedNodes.clear(); expectedNodes.putAll(nodes)
            parens.clear(); parens.putAll(ps)
            stats.clear(); stats.putAll(st)
            taken.clear(); taken.addAll(names)
        }
    }

    private fun count(key: String) {
        stats[key] = (stats[key] ?: 0) + 1
    }

    // ---- tokens and text --------------------------------------------------------------------

    fun off(i: Int): Int {
        val p = toks[i].canonicalLocation
        val at = if (toks[i].type == Token.Type.INTRINSIC_IDENTIFIER) 1 else 0
        return lineStarts[p.lineNumber - 1] + p.column - 1 - at
    }

    /** The offset just past token [i]'s text. */
    fun end(i: Int): Int {
        val t = toks[i]
        val s = off(i)
        return when (t.type) {
            // A string's head starts at its `"`; a part and the tail start just past the hole's `}`.
            Token.Type.L_STRING -> s + 1 + t.content.length + 1
            Token.Type.L_STRING_HEAD -> s + 1 + t.content.length + 2
            Token.Type.L_STRING_PART -> s + t.content.length + 2
            Token.Type.L_STRING_TAIL -> s + t.content.length + 1
            Token.Type.L_CHAR -> s + t.content.length + 2
            Token.Type.L_INTEGER -> {
                var k = s
                while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                k
            }
            Token.Type.INTRINSIC_IDENTIFIER -> s + 1 + t.content.removePrefix("@").length
            else -> s + t.content.length
        }
    }

    /** Every gap between two tokens is whitespace and comments, or the token ends are not known. */
    private fun checkTokens(): String? {
        for (i in 0 until toks.size - 1) {
            if (toks[i + 1].type == Token.Type.S_EOF) break
            val e = end(i)
            val next = toks[i + 1].type
            // The `}` closing a hole is no token: the string's next piece starts past it.
            val s = off(i + 1) - if (next == Token.Type.L_STRING_PART || next == Token.Type.L_STRING_TAIL) 1 else 0
            if (e > s || !isGap(e, s) || (s < off(i + 1) && text[s] != '}')) {
                val p = toks[i].canonicalLocation
                return "token ${toks[i].type} at ${p.lineNumber}:${p.column} ends where it cannot be read"
            }
        }
        return null
    }

    private fun isGap(from: Int, to: Int): Boolean {
        var k = from
        while (k < to) {
            when {
                text[k].isWhitespace() -> k++
                text.startsWith("//", k) -> k = text.indexOf('\n', k).let { if (it < 0 || it > to) to else it }
                text.startsWith("/*", k) -> k = text.indexOf("*/", k).let { if (it < 0) return false else it + 2 }
                else -> return false
            }
        }
        return true
    }

    /** The comments between two offsets that hold no string, in order, each with its offset. */
    private fun commentsIn(from: Int, to: Int): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var k = from
        while (k < to) {
            when {
                text.startsWith("//", k) -> {
                    val e = text.indexOf('\n', k).let { if (it < 0 || it > to) to else it }
                    out += k to text.substring(k, e).trimEnd()
                    k = e
                }
                text.startsWith("/*", k) -> {
                    val e = text.indexOf("*/", k).let { if (it < 0) to else minOf(it + 2, to) }
                    out += k to text.substring(k, e)
                    k = e
                }
                else -> k++
            }
        }
        return out
    }

    /** No comment between two offsets that hold no string: no line or block comment opener. */
    private fun noComment(from: Int, to: Int): Boolean = text.substring(from, to).let { !it.contains("//") && !it.contains("/*") }

    /** The text between two offsets is only whitespace: no comment. */
    private fun blank(from: Int, to: Int): Boolean = (from until to).all { text[it].isWhitespace() }

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    private fun lineStart(line: Int): Int = lineStarts[line - 1]

    private fun lineEnd(line: Int): Int = if (line < lineStarts.size) lineStarts[line] - 1 else text.length

    private fun indentOf(line: Int): String {
        val s = lineStart(line)
        var e = s
        while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
        return text.substring(s, e)
    }

    private fun indentAt(i: Int): String = indentOf(lineOf(off(i)))

    private fun firstOnLine(i: Int): Boolean = blank(lineStart(lineOf(off(i))), off(i))

    /** Token [i] is the last thing on its line, a comment aside. */
    private fun lastOnLine(i: Int): Boolean {
        if (i + 1 >= toks.size || toks[i + 1].type == Token.Type.S_EOF) return true
        return lineOf(off(i + 1)) > lineOf(end(i))
    }

    private fun tok(node: ASTNode): Int = ctx.astOrigins[node]?.let { indexAt[it.lineNumber to it.column] }
        ?: throw Refuse("a ${node.javaClass.simpleName} with no token", node)

    /** The first `{` from token [from] on, outside any parentheses or brackets, a test block's passed over. */
    private fun braceAfter(from: Int): Int {
        var depth = 0
        var j = from
        while (j < toks.size) {
            when (toks[j].type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET -> depth--
                Token.Type.S_OPEN_BRACE -> if (j in testBraces) j = match(j) else if (depth == 0) return j
                Token.Type.S_EOF -> break
                else -> {}
            }
            j++
        }
        throw Refuse("no block after ${toks[from].content}", null)
    }

    /** The token closing the bracket opened at [open]. */
    private fun match(open: Int): Int {
        var depth = 0
        for (j in open until toks.size) {
            when (toks[j].type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET, Token.Type.S_OPEN_BRACE -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET, Token.Type.S_CLOSE_BRACE -> if (--depth == 0) return j
                else -> {}
            }
        }
        throw Refuse("an unclosed bracket", null)
    }

    /** The last token of statement [k] of [stmts], whose block closes at [close]. */
    private fun lastTok(stmts: List<Statement>, k: Int, close: Int): Int =
        if (k + 1 < stmts.size) tok(stmts[k + 1]) - 1 else close - 1

    private fun edit(start: Int, end: Int, new: String, originLine: Int = lineOf(start)) {
        edits += Ed(start, end, new, current?.id ?: -1, originLine)
    }

    // ---- the tree's parents, units, returns ---------------------------------------------------

    private fun linkParents(v: Any?, parent: Any?) {
        when (v) {
            null -> {}
            is ASTNode -> {
                if (parents.containsKey(v)) return
                parents[v] = parent ?: v
                for (f in AstWalk.fieldsOf(v.javaClass)) linkParents(f.get(v), v)
            }
            is Collection<*> -> v.forEach { linkParents(it, parent) }
            is Array<*> -> v.forEach { linkParents(it, parent) }
            is Map<*, *> -> v.values.forEach { linkParents(it, parent) }
            is Pair<*, *> -> { linkParents(v.first, parent); linkParents(v.second, parent) }
            else -> if (AstWalk.isAstPart(v)) for (f in AstWalk.fieldsOf(v.javaClass)) linkParents(f.get(v), parent)
        }
    }

    private fun findUnits() {
        val stack = ArrayDeque<Unit>()
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun open(def: FunctionDefExpr, at: ASTNode, visitRest: () -> kotlin.Unit) {
            val o = runCatching { braceAfter(tok(at)) }.getOrNull()
            val c = o?.let { runCatching { match(it) }.getOrNull() }
            if (o == null || c == null) {
                visitRest()
                return
            }
            val u = Unit(units.size, def, !isVoid(def.returnTypeSpecifier), o, c, stack.lastOrNull(), toks[o].canonicalLocation.lineNumber)
            units += u
            stack.addLast(u)
            visitRest()
            stack.removeLast()
        }
        fun visit(v: Any?) {
            if (v == null) return
            if (v is ASTNode && !seen.add(v)) return
            when (v) {
                is FunctionDecl -> if (v.def.body != null) open(v.def, v) { v.def.body!!.forEach { visit(it) }; v.def.parameters.forEach { visit(it) } }
                is LambdaExpr -> if (v.def.body != null) open(v.def, v) { v.def.body!!.forEach { visit(it) } }
                is ASTNode -> for (f in AstWalk.fieldsOf(v.javaClass)) visit(f.get(v))
                is Collection<*> -> v.forEach { visit(it) }
                is Array<*> -> v.forEach { visit(it) }
                is Map<*, *> -> v.values.forEach { visit(it) }
                else -> if (AstWalk.isAstPart(v)) for (f in AstWalk.fieldsOf(v.javaClass)) visit(f.get(v))
            }
        }
        visit(ctx.ast)
    }

    private fun isVoid(t: Type): Boolean = (t.identifier as? Identifier)?.value == "Void" && t.children.isEmpty()

    /** Whether [v] holds a written `return` of its own function: not one inside a lambda or a nested function. */
    private fun hasReturn(v: Any?): Boolean {
        if (v == null) return false
        val memo = v is ASTNode || v is List<*>
        if (memo) returnMemo[v]?.let { return it }
        val r = when (v) {
            is ReturnStatement -> !v.implicit || hasReturn(v.expr)
            is LambdaExpr, is FunctionDefExpr, is FunctionDecl -> false
            is ASTNode -> AstWalk.fieldsOf(v.javaClass).any { hasReturn(it.get(v)) }
            is Collection<*> -> v.any { hasReturn(it) }
            is Array<*> -> v.any { hasReturn(it) }
            is Map<*, *> -> v.values.any { hasReturn(it) }
            else -> AstWalk.isAstPart(v) && AstWalk.fieldsOf(v.javaClass).any { hasReturn(it.get(v)) }
        }
        if (memo) returnMemo[v] = r
        return r
    }

    /** Every path through [stmts] leaves the function: a return or a throw last. */
    private fun exits(stmts: List<Statement>): Boolean {
        val last = stmts.lastOrNull() ?: return false
        return when {
            last is ReturnStatement -> !last.implicit
            last.javaClass == Statement::class.java && last.expr is ThrowExpr -> true
            last is IfSelectionStatement -> hasFinalElse(last) && exits(last.thenStatements) && last.elseBranches.all { exits(branchStatements(it)) }
            isTry(last) -> (last.expr as TryExpr).let { it.exceptionName != null && exits(it.tryBlock) && exits(it.handlerBlock) }
            else -> false
        }
    }

    private fun hasFinalElse(s: IfSelectionStatement): Boolean = s.elseBranches.lastOrNull() is ElseBranchStatement

    private fun branchStatements(b: IfElseBranchStatementNode): List<Statement> = when (b) {
        is ElseIfBranchStatement -> b.statements
        is ElseBranchStatement -> b.statements
    }

    private fun isTry(s: Statement): Boolean = s.javaClass == Statement::class.java && s.expr is TryExpr && !(s.expr as TryExpr).isValue

    private fun isLoop(s: Statement): Boolean = s is ForIterationStatement || s is WhileIterationStatement

    // ---- synthesized statements -------------------------------------------------------------

    /** [stmt] as the parser reads it alone in a Void body: the tree a synthesized line has. */
    private fun snippet(stmt: String): Statement {
        val (c, err) = ProjectUnits.read(CompilationUnit(bootstrap = false), "snippet", "zzSnippet: () Void {\n$stmt\n}\n", null, literals = false, returns = true)
        // The unit gives the file a module statement first; the function follows it.
        val fn = c?.ast?.statements?.firstNotNullOfOrNull { (it as? Statement)?.expr as? FunctionDecl } ?: throw Refuse("a line the parser does not read ($stmt): $err", null)
        return fn.def.body!!.single()
    }

    private fun assign(name: String, value: Expr): Statement {
        val shape = snippet("$name = 0").expr as? AssignmentExpr ?: throw Refuse("an assignment the parser reads otherwise", null)
        return Statement(AssignmentExpr(shape.target, value))
    }

    // ---- blocks -----------------------------------------------------------------------------

    /** A block's returns: VALUE ends the function (a return is the value), FLAG has code after it (the result and flag set), LOOP a loop's body (them and `break`). */
    private enum class Mode { VALUE, FLAG, LOOP }

    /** The locals a return sets where it cannot be the value: the result (none in a Void function), the flag (none when `break` alone will do). */
    private class Exit(val result: String?, val flag: String?)

    /** A block's place: the token closing it, its mode, its unit, the locals its returns set. */
    private class Ctx(val close: Int, val mode: Mode, val u: Unit, val exit: Exit? = null)

    private fun block(stmts: List<Statement>, c: Ctx): List<Statement> = when (c.mode) {
        Mode.VALUE -> valueBlock(stmts, c)
        Mode.FLAG -> flagBlock(stmts, c)
        Mode.LOOP -> loopBlock(stmts, c)
    }

    /** A block that ends its function: its first statement holding a return decides the shape, else a last if/else or try carries the value. */
    private fun valueBlock(stmts: List<Statement>, c: Ctx): List<Statement> {
        val i = stmts.indexOfFirst { hasReturn(it) }
        if (i < 0) {
            if (stmts.isEmpty()) return stmts
            val last = stmts.last()
            val out = when {
                last is IfSelectionStatement -> tailIf(last, c, stmts.getOrNull(stmts.size - 2))
                isTry(last) -> tailTry(last, c)
                else -> return stmts
            }
            return if (out.size == 1 && out[0] === last) stmts else stmts.dropLast(1) + out
        }
        val s = stmts[i]
        val rest = stmts.subList(i + 1, stmts.size)
        val prev = stmts.getOrNull(i - 1)
        val out = when {
            s is ReturnStatement -> {
                if (rest.isNotEmpty()) throw Refuse("code after a return", rest[0])
                tailReturn(s, c, prev)
            }
            s is IfSelectionStatement -> if (rest.isEmpty()) tailIf(s, c, prev) else guard(s, rest, c, prev) ?: flagged(s, rest, c)
            isLoop(s) -> flagged(s, rest, c)
            isTry(s) -> when {
                rest.isEmpty() -> tailTry(s, c)
                deadAfter(s, rest) -> tailTry(s, c).also { dropDead(rest, c) }
                else -> flagged(s, rest, c)
            }
            else -> throw Refuse("a return inside ${s.expr.javaClass.simpleName}", s)
        }
        return stmts.subList(0, i) + out
    }

    /**
     * Token [first] starts a function's last value after the statement [prev]: a `(` or a sign
     * there would read as more of [prev] (a call, a subtraction), so [prev] ends in `;`.
     */
    private fun separate(first: Int, before: Int, prev: Statement?) {
        if (prev == null) return
        val t = toks[first].type
        if (t != Token.Type.S_OPEN_PARENTHESIS && t != Token.Type.OP_SUB && t != Token.Type.OP_ADD && t != Token.Type.S_OPEN_BRACKET) return
        if (prev is IfSelectionStatement || isLoop(prev) || isTry(prev)) return
        val e = prev.expr
        if (e is FunctionDecl || e is ClassDecl || e is StructDecl || e is TraitDecl) return
        if (toks[before].type == Token.Type.S_SEMICOLON || toks[before].type == Token.Type.S_OPEN_BRACE) return
        edit(end(before), end(before), ";")
        count("a `;` before a last value that opens with ( or a sign")
    }

    /** A function's last `return x`: `x`; a Void one's bare `return` goes. */
    private fun tailReturn(s: ReturnStatement, c: Ctx, prev: Statement?): List<Statement> {
        val r = tok(s)
        if (s.expr === NoExpr) {
            if (c.u.valued) throw Refuse("a bare return in a function with a value", s)
            deleteToken(r)
            count("Void last return dropped")
            return emptyList()
        }
        val e = s.expr
        // `return v` in a Void function is the typer's error; only a call's (a Void one's) is a statement.
        if (!c.u.valued && e !is FunctionCallExpr) throw Refuse("a Void function's return of a value", s)
        // An if or try value at the end reads as the statement it is spelled as.
        val asStatement = when {
            e is IfExpr && e !is TernaryExpr && expectedNodes[e]?.first !is TernaryExpr -> ifAsStatement(e)
            e is TryExpr -> tryAsStatement(e)
            else -> null
        }
        edit(off(r), off(r + 1), "")
        if (asStatement == null) separate(r + 1, r - 1, prev)
        if (!c.u.valued) {
            if (asStatement != null) throw Refuse("a Void function's return of an if or try value", s)
            count("Void last return of a call")
            return listOf(Statement(e))
        }
        if (asStatement != null) {
            count("last return of an if or try value to the statement")
            return listOf(asStatement)
        }
        count("last return to its value")
        return listOf(s)
    }

    /** An if value's chain, spelled at a body's end: the if statement whose arms end in their values. */
    private fun ifAsStatement(e: IfExpr): Statement {
        val branches = mutableListOf<IfElseBranchStatementNode>()
        var cur: IfExpr = e
        val then = armAsBody(e.thenBranch)
        while (true) {
            val nested = cur.elseBranch.singleOrNull()?.takeIf { it.javaClass == Statement::class.java }?.expr as? IfExpr
            val chained = nested != null && nested !is TernaryExpr && runCatching { toks[match(braceAfter(ifTokenOfChained(cur))) + 2].type == Token.Type.K_IF }.getOrDefault(false)
            if (chained) {
                branches += ElseIfBranchStatement(nested!!.condition, armAsBody(nested.thenBranch))
                cur = nested
                continue
            }
            branches += ElseBranchStatement(armAsBody(cur.elseBranch))
            break
        }
        return IfSelectionStatement(e.condition, then, branches)
    }

    private val ifTokens = IdentityHashMap<IfExpr, Int>()

    private fun ifTokenOfChained(e: IfExpr): Int = ifTokens[e] ?: ifTokenOf(e).also { ifTokens[e] = it }

    /** A try value spelled at a body's end: the try statement whose blocks end in their values. */
    private fun tryAsStatement(e: TryExpr): Statement {
        if (e.exceptionName == null) throw Refuse("a return of a try value with no on", e)
        return Statement(TryExpr(armAsBody(e.tryBlock), e.exceptionName, e.exceptionType, armAsBody(e.handlerBlock)))
    }

    /** A value arm's statements as a body's: its last value a return, a last if or try value the statement. */
    private fun armAsBody(arm: List<Statement>): List<Statement> {
        val last = arm.lastOrNull() ?: return arm
        if (last.javaClass != Statement::class.java) return arm
        val v = last.expr
        val tail = when {
            v is TernaryExpr || (v is IfExpr && expectedNodes[v]?.first is TernaryExpr) -> ReturnStatement(v)
            v is IfExpr -> ifAsStatement(v)
            v is TryExpr && v.isValue -> tryAsStatement(v)
            v is Decl || v is AssignmentExpr || v is CompoundAssignmentExpr || v is PlaceAssignmentExpr || v is ThrowExpr || v === NoExpr || (v is TryExpr) -> return arm
            else -> ReturnStatement(v)
        }
        return arm.dropLast(1) + tail
    }

    /** Token [r] removed: its whole line when it stands alone, else it and the space before it. */
    private fun deleteToken(r: Int) {
        val line = lineOf(off(r))
        val next = if (r + 1 < toks.size) off(r + 1) else text.length
        if (firstOnLine(r) && lastOnLine(r) && blank(end(r), minOf(next, lineEnd(line)))) {
            edit(lineStart(line), minOf(lineEnd(line) + 1, text.length), "")
        } else {
            edit(end(r - 1), end(r), "")
        }
    }

    /** A last try/on: each of its blocks ends the function. */
    private fun tailTry(s: Statement, c: Ctx): List<Statement> {
        val e = s.expr as TryExpr
        if (e.exceptionName == null) {
            if (hasReturn(s)) throw Refuse("a return inside a try with no on", s)
            return listOf(s)
        }
        val (tryClose, onClose) = tryCloses(s)
        val tried = block(e.tryBlock, Ctx(tryClose, c.mode, c.u, c.exit))
        val handled = block(e.handlerBlock, Ctx(onClose, c.mode, c.u, c.exit))
        if (tried === e.tryBlock && handled === e.handlerBlock) return listOf(s)
        return listOf(Statement(TryExpr(tried, e.exceptionName, e.exceptionType, handled)))
    }

    private fun tryCloses(s: Statement): Pair<Int, Int> {
        val tryClose = match(braceAfter(tok(s)))
        return tryClose to match(braceAfter(tryClose + 1))
    }

    /** A try whose blocks both return, then a lone return of a literal: unreachable, it goes. */
    private fun deadAfter(s: Statement, rest: List<Statement>): Boolean {
        val e = s.expr as TryExpr
        val r = rest.singleOrNull() as? ReturnStatement ?: return false
        return e.exceptionName != null && exits(e.tryBlock) && exits(e.handlerBlock) && !r.implicit && (r.expr === NoExpr || movable(r.expr))
    }

    private fun dropDead(rest: List<Statement>, c: Ctx) {
        val first = tok(rest[0])
        val last = c.close - 1
        if (!firstOnLine(first) || !lastOnLine(last) || !noComment(off(first), end(last))) throw Refuse("an unreachable return that shares its line", rest[0])
        edit(lineStart(lineOf(off(first))), lineEnd(lineOf(end(last))) + 1, "")
        count("unreachable return after a try dropped")
    }

    /** One arm of an if chain: its condition and its tokens (none for the last else), its statements, its braces. */
    private class Arm(val cond: Expr?, val condFirst: Int, val condLast: Int, val stmts: List<Statement>, val open: Int, val close: Int)

    /** The arms of the if chain whose `if` is token [ifTok]: [branches] each an else-if's condition (null for the else) and its statements. */
    private fun armsOf(ifTok: Int, cond: Expr, then: List<Statement>, branches: List<Pair<Expr?, List<Statement>>>): List<Arm> {
        val out = mutableListOf<Arm>()
        var open = braceAfter(ifTok)
        var close = match(open)
        out += Arm(cond, ifTok + 1, open - 1, then, open, close)
        for ((bc, stmts) in branches) {
            if (toks[close + 1].type != Token.Type.K_ELSE) throw Refuse("an else that does not follow its if's block", null)
            if (bc != null) {
                val ifAt = close + 2
                if (toks[ifAt].type != Token.Type.K_IF) throw Refuse("an else if with no if", null)
                open = braceAfter(ifAt)
                close = match(open)
                out += Arm(bc, ifAt + 1, open - 1, stmts, open, close)
            } else {
                open = close + 2
                if (toks[open].type != Token.Type.S_OPEN_BRACE) throw Refuse("an else with no block", null)
                close = match(open)
                out += Arm(null, -1, -1, stmts, open, close)
            }
        }
        return out
    }

    private fun armsOf(s: IfSelectionStatement): List<Arm> = armsOf(tok(s), s.expr, s.thenStatements, s.elseBranches.map {
        when (it) {
            is ElseIfBranchStatement -> it.condition to it.statements
            is ElseBranchStatement -> null to it.statements
        }
    })

    /** [s] with each arm's statements [outs] (then first), and [extra] after its branches. */
    private fun rebuild(s: IfSelectionStatement, outs: List<List<Statement>>, extra: List<IfElseBranchStatementNode> = emptyList(), cond: Expr = s.expr): IfSelectionStatement {
        val branches = s.elseBranches.mapIndexed { k, b ->
            when (b) {
                is ElseIfBranchStatement -> ElseIfBranchStatement(b.condition, outs[k + 1])
                is ElseBranchStatement -> ElseBranchStatement(outs[k + 1])
            }
        }
        return IfSelectionStatement(cond, outs[0], branches + extra)
    }

    /** An if at its function's end: each arm ends the function; a value function's with one expression in every arm is a ternary. */
    private fun tailIf(s: IfSelectionStatement, c: Ctx, prev: Statement?): List<Statement> {
        if (!hasFinalElse(s) && c.u.valued) {
            if (hasReturn(s)) throw Refuse("an if with no else ends a function with a value", s)
            return listOf(s)
        }
        val arms = armsOf(s)
        val outs = arms.map { block(it.stmts, Ctx(it.close, Mode.VALUE, c.u)) }
        if (c.u.valued && hasFinalElse(s) && outs.all { single(it) }) {
            val t = ternary(arms, outs.map { valueOf(it) }, restFirst = null, restLast = null, owner = s)
            separate(arms[0].condFirst, arms[0].condFirst - 2, prev)
            count("last if/else to a ternary")
            return listOf(ReturnStatement(t))
        }
        if (outs.indices.all { outs[it] === arms[it].stmts }) return listOf(s)
        return listOf(rebuild(s, outs))
    }

    /** One value statement whose expression is a single one: a ternary's arm. */
    private fun single(out: List<Statement>): Boolean {
        val s = out.singleOrNull() ?: return false
        if (s.javaClass != Statement::class.java && s !is ReturnStatement) return false
        return singleExpr(s.expr)
    }

    private fun singleExpr(e: Expr): Boolean = when (e) {
        NoExpr, is Decl, is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr, is ThrowExpr, is TryExpr, is LambdaExpr, is WithExpr -> false
        is TernaryExpr -> true
        is IfExpr -> expectedNodes[e]?.first is TernaryExpr
        else -> true
    }

    private fun valueOf(out: List<Statement>): Expr = out.single().expr

    /** A Void guard `if c { return }` with nothing else in its block: it negates. */
    private fun bareGuard(s: Statement, c: Ctx): Boolean {
        if (c.u.valued || s !is IfSelectionStatement || s.elseBranches.isNotEmpty()) return false
        val only = s.thenStatements.singleOrNull() as? ReturnStatement ?: return false
        if (only.expr !== NoExpr || only.implicit) return false
        val open = braceAfter(tok(s))
        return noComment(end(open), off(match(open)))
    }

    /** A guard: the code after the if goes into the else it lacks, its one path that falls through; null when it has no such one path. */
    private fun guard(s: IfSelectionStatement, rest: List<Statement>, c: Ctx, prev: Statement?): List<Statement>? {
        val arms = armsOf(s)
        val exits = arms.map { exits(it.stmts) }
        if (arms.indices.any { !exits[it] && hasReturn(arms[it].stmts) }) return null
        val falling = arms.indices.filter { !exits[it] } + if (hasFinalElse(s)) emptyList() else listOf(-1)
        if (falling.isEmpty()) throw Refuse("code after an if whose every branch returns", rest[0])
        if (falling != listOf(-1)) return null
        val last = arms.last()
        val restTok = tok(rest[0])
        val joined = blank(end(last.close), off(restTok))
        if (bareGuard(s, c)) return voidGuard(s, arms[0], rest, restTok, joined, c)
        // `if a { return x } if b { return y } ...` with nothing between them: one chain, `} else if b {`.
        val chain = joined && rest[0] is IfSelectionStatement && hasReturn(rest[0]) && !bareGuard(rest[0], c)
        val outs = arms.map { block(it.stmts, Ctx(it.close, Mode.VALUE, c.u)) }
        val restOut = block(rest, Ctx(c.close, Mode.VALUE, c.u))
        if (c.u.valued && outs.all { single(it) } && single(restOut)) {
            val t = ternary(arms, outs.map { valueOf(it) } + valueOf(restOut), restFirst = restTok, restLast = c.close - 1, owner = s)
            separate(arms[0].condFirst, arms[0].condFirst - 2, prev)
            count("guard to a ternary")
            return listOf(ReturnStatement(t))
        }
        val next = restOut.singleOrNull() as? IfSelectionStatement
        if (chain && next != null) {
            edit(end(last.close), off(restTok), " else ")
            count("guard chained as else if")
            return listOf(rebuild(s, outs, listOf(ElseIfBranchStatement(next.expr, next.thenStatements)) + next.elseBranches))
        }
        edit(end(last.close), end(last.close), " else {")
        moveRest(last.close, restTok, c, s)
        count("guard to if/else")
        return listOf(rebuild(s, outs, listOf(ElseBranchStatement(restOut))))
    }

    /** `if c { return }` and the rest: `if !c { rest }`; a run of them `if !a && !b { rest }`. */
    private fun voidGuard(s: IfSelectionStatement, arm: Arm, rest: List<Statement>, restTok: Int, joined: Boolean, c: Ctx): List<Statement> {
        val run = joined && bareGuard(rest[0], c) && rest.size > 1
        val restOut = block(rest, Ctx(c.close, Mode.VALUE, c.u))
        val neg = negate(s.expr, arm.condFirst, arm.condLast)
        val next = restOut.singleOrNull() as? IfSelectionStatement
        if (run && next != null && next.elseBranches.isEmpty()) {
            // The next guard's `if` and this one's `{ return }` give way to `&&`.
            val nextArm = armsOf(rest[0] as IfSelectionStatement)[0]
            edit(end(arm.condLast), off(nextArm.condFirst), " && ")
            val operands = listOf(neg) + (andRuns[next.expr] ?: listOf(next.expr))
            val joinedCond = operands.reduce { l, r -> BinaryExpr(l, r, BinaryOp.AND) }
            andRuns[joinedCond] = operands
            count("Void guard joined by &&")
            return listOf(IfSelectionStatement(joinedCond, next.thenStatements, emptyList()))
        }
        edit(end(arm.open), end(arm.close), "")
        moveRest(arm.close, restTok, c, s)
        count("Void guard negated")
        return listOf(IfSelectionStatement(neg, restOut, emptyList()))
    }

    /** A condition made of a run of negated guards: its operands, so the next run joins left to right. */
    private val andRuns = IdentityHashMap<Expr, List<Expr>>()

    /**
     * The lines from [restTok]'s on, to its block's end, one level deeper, after token [after]
     * (an if's or a loop's last `}`), and a `}` closing the new block before the block's own `}`.
     */
    private fun moveRest(after: Int, restTok: Int, c: Ctx, s: ASTNode) {
        val guardLine = lineOf(off(tok(s)))
        if (!lastOnLine(after)) throw Refuse("more code on the line of a closing brace", s)
        val closeTok = c.close
        val closeLine = lineOf(off(closeTok))
        val closeFirst = firstOnLine(closeTok)
        // From the line after the brace, so a comment above the rest moves with it.
        val from = minOf(lineOf(end(after)) + 1, lineOf(off(restTok)))
        val to = if (closeFirst) closeLine - 1 else closeLine
        if (from <= to) indents += Ind(from, to, current?.id ?: -1)
        if (closeFirst) {
            edit(lineStart(closeLine), lineStart(closeLine), "${indentOf(guardLine)}}\n", originLine = guardLine)
        } else {
            edit(off(closeTok), off(closeTok), "} ", originLine = guardLine)
        }
    }

    /** `!c` for the condition at tokens [first]..[last]: `==` and `!=` swapped, a `!` dropped, else `!c` or `!(c)`. */
    private fun negate(cond: Expr, first: Int, last: Int): Expr {
        val paren = cond in ctx.astParenthesized
        when {
            !paren && cond is BinaryExpr && (cond.operator == BinaryOp.EQUALS || cond.operator == BinaryOp.NOT_EQUAL) -> {
                val op = topLevelToken(first, last) { it == Token.Type.OP_CMP_EQL || it == Token.Type.OP_CMP_NEQ }
                    ?: throw Refuse("an equality whose operator is not found", cond)
                val eq = cond.operator == BinaryOp.EQUALS
                edit(off(op), end(op), if (eq) "!=" else "==")
                return BinaryExpr(cond.leftExpr, cond.rightExpr, if (eq) BinaryOp.NOT_EQUAL else BinaryOp.EQUALS, cond.attachedIntrinsics)
            }
            !paren && cond is TypeCheckExpr -> {
                val op = topLevelToken(first, last) { it == Token.Type.OP_CMP_EQL || it == Token.Type.OP_CMP_NEQ }
                    ?: throw Refuse("a type test whose operator is not found", cond)
                edit(off(op), end(op), if (cond.negated) "==" else "!=")
                return TypeCheckExpr(cond.value, cond.type, !cond.negated, cond.checks)
            }
            !paren && cond is UnaryExpr && cond.operator == UnaryOp.NOT -> {
                edit(off(first), off(first + 1), "")
                val inner = cond.operand
                // `!(a.b())` is `a.b()`; `!(a || b)` keeps its parentheses, so it binds as it did.
                val primary = inner is Identifier || inner is FunctionCallExpr || inner is MemberAccessExpr
                if (primary && inner in ctx.astParenthesized && toks[first + 1].type == Token.Type.S_OPEN_PARENTHESIS && match(first + 1) == last) {
                    edit(off(first + 1), off(first + 2), "")
                    edit(end(last - 1), end(last), "")
                    parens[inner] = false
                }
                return inner
            }
            else -> {
                val bare = paren || cond is Identifier || cond is FunctionCallExpr || cond is MemberAccessExpr
                if (bare) {
                    edit(off(first), off(first), "!")
                } else {
                    edit(off(first), off(first), "!(")
                    edit(end(last), end(last), ")")
                    parens[cond] = true
                }
                return UnaryExpr(UnaryOp.NOT, cond)
            }
        }
    }

    /** The first token in [first]..[last] outside any brackets whose type [p] accepts. */
    private fun topLevelToken(first: Int, last: Int, p: (Token.Type) -> Boolean): Int? {
        var d = 0
        for (j in first..last) {
            val t = toks[j].type
            when (t) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET, Token.Type.S_OPEN_BRACE -> d++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET, Token.Type.S_CLOSE_BRACE -> d--
                else -> if (d == 0 && p(t)) return j
            }
        }
        return null
    }

    // ---- ternaries ----------------------------------------------------------------------------

    /** The if chain [arms] (and a guard's rest, [restFirst]..[restLast]) as `c ? a : d ? b : e`, in place; [vals] the arms' values. */
    private fun ternary(arms: List<Arm>, vals: List<Expr>, restFirst: Int?, restLast: Int?, owner: ASTNode): TernaryExpr {
        val ifTok = arms[0].condFirst - 1
        val lastArm = arms.last()
        // The `if ` and the last `}` take no comment with them; one between the arms moves to its own line.
        if (!noComment(end(ifTok), off(arms[0].condFirst)) || (restFirst == null && !noComment(end(lastArm.close - 1), end(lastArm.close)))) {
            throw Refuse("a comment where an if that becomes a ternary begins or ends", owner)
        }
        val inner = mutableListOf<Pair<Int, Int>>()
        for ((k, a) in arms.withIndex()) {
            if (a.cond != null) inner += end(a.condLast) to off(a.open + 1)
            val next = arms.getOrNull(k + 1)
            if (next != null || restFirst != null) inner += end(a.close - 1) to (if (next == null) off(restFirst!!) else if (next.cond != null) off(next.condFirst) else off(next.open + 1))
        }
        val commented = inner.any { (a, b) -> !noComment(a, b) }
        val oneLine = !commented && spanLength(arms, restFirst, restLast) <= 100
        val deeper = indentOf(lineOf(off(ifTok))) + unitIndent
        // An operator between two arms: a comment that ended the arm's line still ends it, any other goes on a line before the operator.
        fun op(from: Int, to: Int, symbol: String, breaks: Boolean): String {
            val notes = commentsIn(from, to)
            if (notes.isEmpty()) return if (breaks) "\n$deeper$symbol " else " $symbol "
            return notes.joinToString("") { (at, note) -> if (lineOf(at) == lineOf(from)) " $note" else "\n$deeper$note" } + "\n$deeper$symbol "
        }
        edit(off(ifTok), off(arms[0].condFirst), "")
        for ((k, a) in arms.withIndex()) {
            if (a.cond != null) {
                edit(end(a.condLast), off(a.open + 1), op(end(a.condLast), off(a.open + 1), "?", false))
                if (isTernaryValue(a.cond)) wrap(a.condFirst, a.condLast, a.cond)
            }
            val next = arms.getOrNull(k + 1)
            when {
                next != null && next.cond != null -> edit(end(a.close - 1), off(next.condFirst), op(end(a.close - 1), off(next.condFirst), ":", !oneLine))
                next != null -> edit(end(a.close - 1), off(next.open + 1), op(end(a.close - 1), off(next.open + 1), ":", !oneLine))
                restFirst != null -> edit(end(a.close - 1), off(restFirst), op(end(a.close - 1), off(restFirst), ":", !oneLine))
                else -> edit(end(a.close - 1), end(a.close), "")
            }
            // A ternary in a first arm is parenthesized: `a ? (b ? c : d) : e`.
            if (a.cond != null && isTernaryValue(vals[k])) wrap(firstValueTok(a.open + 1), a.close - 1, vals[k])
        }
        var acc: Expr = vals.last()
        for (k in (arms.size - 1).downTo(0)) {
            val a = arms[k]
            if (a.cond == null) continue
            acc = TernaryExpr(a.cond, Statement(vals[k]), Statement(acc))
        }
        return acc as TernaryExpr
    }

    /** An arm's first value token: past a `return` the arm's own rewrite deletes. */
    private fun firstValueTok(i: Int): Int = if (toks[i].type == Token.Type.K_RETURN) i + 1 else i

    /** The ternary's length on one line, roughly: its tokens and a space each, `if`, braces and `return` gone. */
    private fun spanLength(arms: List<Arm>, restFirst: Int?, restLast: Int?): Int {
        var n = indentOf(lineOf(off(arms[0].condFirst))).length + 16
        fun add(a: Int, b: Int) {
            for (j in a..b) {
                if (toks[j].type in setOf(Token.Type.K_RETURN, Token.Type.K_IF, Token.Type.K_ELSE, Token.Type.S_OPEN_BRACE, Token.Type.S_CLOSE_BRACE)) continue
                n += end(j) - off(j) + 1
            }
        }
        for (a in arms) {
            if (a.cond != null) add(a.condFirst, a.condLast)
            add(a.open + 1, a.close - 1)
            n += 3
        }
        if (restFirst != null && restLast != null) add(restFirst, restLast)
        return n
    }

    private fun isTernaryValue(e: Expr): Boolean = e is TernaryExpr || (e is IfExpr && expectedNodes[e]?.first is TernaryExpr)

    /** Parentheses around tokens [first]..[last] (the node [n]) unless it has them. */
    private fun wrap(first: Int, last: Int, n: Expr) {
        if (n in ctx.astParenthesized) return
        edit(off(first), off(first), "(")
        edit(end(last), end(last), ")")
        parens[expectedNodes[n]?.first ?: n] = true
    }

    // ---- if values ----------------------------------------------------------------------------

    /** Each if value whose arms are single expressions, as a ternary: inner ones first. */
    private fun ifValues() {
        val all = AstWalk.all(ctx.ast).filterIsInstance<IfExpr>().filter { it !is TernaryExpr }
        // A chain's links (each `else if`) belong to the chain's head.
        val links = java.util.Collections.newSetFromMap(IdentityHashMap<IfExpr, Boolean>())
        val chains = IdentityHashMap<IfExpr, List<Pair<IfExpr, Int>>>()
        for (e in all) {
            if (e in links) continue
            val chain = mutableListOf<Pair<IfExpr, Int>>()
            var cur: IfExpr = e
            var at = runCatching { ifTokenOf(e) }.getOrNull() ?: continue
            while (true) {
                chain += cur to at
                val close = runCatching { match(braceAfter(at)) }.getOrNull() ?: break
                val nested = cur.elseBranch.singleOrNull()?.takeIf { it.javaClass == Statement::class.java }?.expr as? IfExpr
                if (nested == null || nested is TernaryExpr || toks.getOrNull(close + 2)?.type != Token.Type.K_IF) break
                links += nested
                cur = nested
                at = close + 2
            }
            chains[e] = chain
        }
        for (e in all.filter { it in chains && it !in links }.sortedByDescending { depthOf(it) }) {
            current = unitOf(e)
            val snap = Snapshot()
            try {
                ifValue(e, chains.getValue(e))
            } catch (r: Refuse) {
                snap.restore()
                hand += (ctx.astOrigins[e]?.lineNumber ?: 0) to "an if value that cannot be a ternary: ${r.why}"
            }
        }
        current = null
    }

    private fun depthOf(n: Any): Int {
        var d = 0
        var p: Any? = parents[n]
        while (p != null && p !== parents[p]) {
            d++
            p = parents[p]
        }
        return d
    }

    private fun unitOf(n: ASTNode): Unit? {
        var p: Any? = parents[n]
        while (p != null) {
            if (p is FunctionDefExpr) return units.firstOrNull { it.def === p }
            if (p === parents[p]) return null
            p = parents[p]
        }
        return null
    }

    private fun ifTokenOf(e: IfExpr): Int {
        var t = tok(e)
        // A parenthesized value's origin is its `(`.
        while (toks[t].type == Token.Type.S_OPEN_PARENTHESIS) t++
        return when {
            toks[t].type == Token.Type.K_IF -> t
            toks[t].type == Token.Type.K_ELSE && toks[t + 1].type == Token.Type.K_IF -> t + 1
            else -> throw Refuse("an if value with no if", e)
        }
    }

    private fun ifValue(head: IfExpr, chain: List<Pair<IfExpr, Int>>) {
        if (hasReturn(head)) return
        val branches = chain.drop(1).map { it.first.condition to it.first.thenBranch } + (null to chain.last().first.elseBranch)
        val arms = armsOf(chain[0].second, head.condition, head.thenBranch, branches)
        if (!arms.all { single(it.stmts) }) return
        val t = ternary(arms, arms.map { valueOf(it.stmts) }, restFirst = null, restLast = null, owner = head)
        val parent = parents[head]
        // In an operand, a cast or a receiver the ternary needs parentheses: `?:` binds loosest.
        if (head !in ctx.astParenthesized && !looseContext(parent, head)) {
            val last = arms.last().close
            edit(off(chain[0].second), off(chain[0].second), "(")
            edit(end(last), end(last), ")")
            parens[t] = true
        } else {
            parens[t] = head in ctx.astParenthesized
            // An arm's last value line: one that opens with `(` would read as a call of the line before.
            if (parent is Statement && parent.javaClass == Statement::class.java && head !in ctx.astParenthesized) {
                val list = parents[parent]
                val stmts = when (list) {
                    is IfExpr -> if (list.thenBranch.any { it === parent }) list.thenBranch else list.elseBranch
                    is TryExpr -> if (list.tryBlock.any { it === parent }) list.tryBlock else list.handlerBlock
                    else -> null
                }
                val k = stmts?.indexOfFirst { it === parent } ?: -1
                if (k > 0) separate(arms[0].condFirst, chain[0].second - 1, stmts!![k - 1])
            }
        }
        expectedNodes[head] = t to (current?.id ?: -1)
        count("if value to a ternary")
    }

    /** A place where a value of the lowest precedence stands bare. */
    private fun looseContext(parent: Any?, child: Any): Boolean = when (parent) {
        is IfSelectionStatement, is WhileIterationStatement, is ForIterationStatement -> false
        is Statement -> true
        is VariableDecl -> parent.value === child
        is AssignmentExpr -> parent.value === child
        is CompoundAssignmentExpr, is PlaceAssignmentExpr -> true
        is FunctionCallPositionalParameterExpr, is FunctionCallNamedParameterExpr -> true
        is BuilderExpr, is ObjectInitExpr -> true
        else -> false
    }

    // ---- the flag: returns no shape reaches --------------------------------------------------

    /**
     * [s]'s returns set a result and a done flag declared before it, and the code after runs when the flag is not set;
     * a loop needs no flag when `break` alone gets to the end, and no zero when the value after it is a literal.
     */
    private fun flagged(s: Statement, rest: List<Statement>, c: Ctx): List<Statement> {
        val sTok = tok(s)
        if (!firstOnLine(sTok)) throw Refuse("a statement holding returns that does not start its line", s)
        val line = lineOf(off(sTok))
        val ind = indentOf(line)
        val loop = isLoop(s)
        // Nothing after it in a value function: only a loop that cannot end but by a return, else the
        // function could end with no value, the typer's error, and the result's start would hide it.
        if (c.u.valued && rest.isEmpty() && !endless(s)) throw Refuse("a value function that can end with no value after this", s)
        val restRet = (rest.singleOrNull() as? ReturnStatement)?.takeIf { !it.implicit }
        val deep = loop && returnsBelowLoop(bodyOf(s))
        // A literal after it is the result's start: then the result alone is the value at the end.
        val movable = c.u.valued && restRet != null && movable(restRet.expr)
        val needsFlag = !loop || deep || (rest.isNotEmpty() && !movable)
        val type = c.u.def.returnTypeSpecifier
        var result: String? = null
        var maybe = false
        val decls = mutableListOf<Statement>()
        var head = ""
        if (c.u.valued) {
            result = fresh("result")
            val init = when {
                movable -> sliceOf(restRet!!.expr)
                else -> zeroOf(type) ?: "null".also { maybe = true }
            }
            val declText = if (maybe) "mut $result: Maybe<${typeText(c.u)}> = null" else "mut $result: ${typeText(c.u)} = $init"
            if (maybe && (type.identifier as? Identifier)?.value == "Maybe") throw Refuse("a return no shape reaches in a Maybe function with no literal zero", s)
            decls += snippet(declText)
            head += "$ind$declText\n"
        }
        val flag = if (needsFlag) fresh(if (c.u.valued && rest.isNotEmpty() && !movable) "found" else "done") else null
        if (flag != null) {
            decls += snippet("mut $flag: Bool = false")
            head += "${ind}mut $flag: Bool = false\n"
        }
        if (head.isNotEmpty()) edit(lineStart(line), lineStart(line), head, originLine = line)
        val exit = Exit(result, flag)
        val s2 = statementIn(s, Ctx(c.close, if (loop) Mode.LOOP else Mode.FLAG, c.u, exit))
        val sClose = closeOf(s)
        val value: Expr? = result?.let { (snippet(if (maybe) "$it.value" else it)).expr }
        val valueText = result?.let { if (maybe) "$it.value" else it }
        val after: List<Statement> = when {
            !c.u.valued && rest.isEmpty() -> emptyList()
            !c.u.valued -> {
                val restOut = block(rest, Ctx(c.close, Mode.VALUE, c.u))
                edit(end(sClose), end(sClose), "\n${ind}if !$flag {")
                moveRest(sClose, tok(rest[0]), c, s)
                count("Void return through a done flag")
                listOf(IfSelectionStatement(UnaryExpr(UnaryOp.NOT, Identifier(flag!!)), restOut, emptyList()))
            }
            rest.isEmpty() -> {
                edit(end(sClose), end(sClose), "\n$ind$valueText")
                count("return through a result local, nothing after")
                listOf(ReturnStatement(value!!))
            }
            movable -> {
                val rt = tok(restRet!!)
                edit(off(rt), end(lastTok(rest, 0, c.close)), result!!)
                count("return through a result local that starts as the value after")
                listOf(ReturnStatement(value!!))
            }
            restRet != null && singleExpr(restRet.expr) -> {
                val rt = tok(restRet)
                edit(off(rt), off(rt + 1), "$flag ? $valueText : ")
                count("return through a result local, flag ? result : value")
                listOf(ReturnStatement(TernaryExpr(Identifier(flag!!), Statement(value!!), Statement(restRet.expr))))
            }
            else -> {
                val restOut = block(rest, Ctx(c.close, Mode.VALUE, c.u))
                val restTok = tok(rest[0])
                if (single(restOut) && noComment(end(sClose), off(restTok))) {
                    // The rest became one value: `flag ? result : value`.
                    edit(end(sClose), off(restTok), "\n$ind$flag ? $valueText : ")
                    count("return through a result local, flag ? result : the rest's value")
                    listOf(ReturnStatement(TernaryExpr(Identifier(flag!!), Statement(value!!), Statement(valueOf(restOut)))))
                } else {
                    edit(end(sClose), end(sClose), "\n${ind}if $flag {\n$ind$unitIndent$valueText\n$ind} else {")
                    moveRest(sClose, restTok, c, s)
                    count("return through a result local, if flag else the rest")
                    listOf(IfSelectionStatement(Identifier(flag!!), listOf(ReturnStatement(value!!)), listOf(ElseBranchStatement(restOut))))
                }
            }
        }
        return decls + s2 + after
    }

    /** `while true` with no `break` of its own: it ends only by a return or a throw. */
    private fun endless(s: Statement): Boolean {
        if (s !is WhileIterationStatement || (s.condition as? Identifier)?.value != "true") return false
        fun breaks(stmts: List<Statement>): Boolean = stmts.any { st ->
            when {
                st is BreakStatement -> true
                st is IfSelectionStatement -> breaks(st.thenStatements) || st.elseBranches.any { breaks(branchStatements(it)) }
                isTry(st) -> (st.expr as TryExpr).let { breaks(it.tryBlock) || breaks(it.handlerBlock) }
                else -> false
            }
        }
        return !breaks(s.statements)
    }

    /** Whether [stmts] (a loop's body) hold a return inside a loop of their own. */
    private fun returnsBelowLoop(stmts: List<Statement>): Boolean = stmts.any { st ->
        hasReturn(st) && when {
            isLoop(st) -> true
            st is IfSelectionStatement -> returnsBelowLoop(st.thenStatements) || st.elseBranches.any { returnsBelowLoop(branchStatements(it)) }
            isTry(st) -> (st.expr as TryExpr).let { returnsBelowLoop(it.tryBlock) || returnsBelowLoop(it.handlerBlock) }
            else -> false
        }
    }

    /** The `}` that ends statement [s] (a loop, an if chain, a try). */
    private fun closeOf(s: Statement): Int = when {
        s is IfSelectionStatement -> armsOf(s).last().close
        isTry(s) -> tryCloses(s).second
        else -> match(braceAfter(tok(s)))
    }

    /** [s] (a statement holding returns) with its blocks in [c]'s mode. */
    private fun statementIn(s: Statement, c: Ctx): Statement = when {
        s is IfSelectionStatement -> {
            val arms = armsOf(s)
            rebuild(s, arms.map { block(it.stmts, Ctx(it.close, c.mode, c.u, c.exit)) })
        }
        s is ForIterationStatement -> ForIterationStatement(s.forIterationExpr, block(s.body, Ctx(match(braceAfter(tok(s))), Mode.LOOP, c.u, c.exit)))
        s is WhileIterationStatement -> WhileIterationStatement(s.condition, block(s.statements, Ctx(match(braceAfter(tok(s))), Mode.LOOP, c.u, c.exit)))
        isTry(s) -> {
            val e = s.expr as TryExpr
            if (e.exceptionName == null) throw Refuse("a return inside a try with no on", s)
            val (tryClose, onClose) = tryCloses(s)
            Statement(TryExpr(block(e.tryBlock, Ctx(tryClose, c.mode, c.u, c.exit)), e.exceptionName, e.exceptionType, block(e.handlerBlock, Ctx(onClose, c.mode, c.u, c.exit))))
        }
        else -> throw Refuse("a return inside ${s.expr.javaClass.simpleName}", s)
    }

    /** A return where code may follow: the result and the flag set, then `break` in a loop. */
    private fun exitOf(s: ReturnStatement, stmts: List<Statement>, k: Int, c: Ctx): List<Statement> {
        val exit = c.exit ?: throw Refuse("a return with no flag to set", s)
        val rt = tok(s)
        if (hasReturn(s.expr)) throw Refuse("a return inside a return", s)
        val sep = if (firstOnLine(rt)) "\n${indentAt(rt)}" else "; "
        val out = mutableListOf<Statement>()
        val tail = mutableListOf<String>()
        if (exit.flag != null) tail += "${exit.flag} = true"
        if (c.mode == Mode.LOOP) tail += "break"
        if (exit.result != null) {
            if (s.expr === NoExpr) throw Refuse("a bare return in a function with a value", s)
            edit(off(rt), off(rt + 1), "${exit.result} = ")
            val e = end(lastTok(stmts, k, c.close))
            if (tail.isNotEmpty()) edit(e, e, tail.joinToString("") { "$sep$it" })
            out += assign(exit.result, s.expr)
        } else {
            if (s.expr !== NoExpr) throw Refuse("a return of a value in a Void function", s)
            if (tail.isEmpty()) throw Refuse("a Void return with nothing to set", s)
            edit(off(rt), end(rt), tail.joinToString(sep))
        }
        if (exit.flag != null) out += snippet("${exit.flag} = true")
        if (c.mode == Mode.LOOP) out += BreakStatement()
        return out
    }

    /**
     * A block code follows: the first statement holding a return decides. A guard takes the code
     * after it into its else, as a value's guard does; else the code after runs under `if !done`.
     */
    private fun flagBlock(stmts: List<Statement>, c: Ctx): List<Statement> {
        val i = stmts.indexOfFirst { hasReturn(it) }
        if (i < 0) return stmts
        val s = stmts[i]
        val rest = stmts.subList(i + 1, stmts.size)
        val before = stmts.subList(0, i)
        if (s is ReturnStatement) {
            if (rest.isNotEmpty()) throw Refuse("code after a return", rest[0])
            count("return to set the result and flag")
            return before + exitOf(s, stmts, i, c)
        }
        if (s is IfSelectionStatement && rest.isNotEmpty()) {
            val arms = armsOf(s)
            val exits = arms.map { exits(it.stmts) }
            val falling = arms.indices.filter { !exits[it] } + if (hasFinalElse(s)) emptyList() else listOf(-1)
            if (falling == listOf(-1) && arms.indices.none { !exits[it] && hasReturn(arms[it].stmts) }) {
                val outs = arms.map { block(it.stmts, Ctx(it.close, Mode.FLAG, c.u, c.exit)) }
                val restOut = block(rest, c)
                val last = arms.last()
                edit(end(last.close), end(last.close), " else {")
                moveRest(last.close, tok(rest[0]), c, s)
                count("guard to if/else, the flag set")
                return before + rebuild(s, outs, listOf(ElseBranchStatement(restOut)))
            }
        }
        val s2 = statementIn(s, c)
        if (rest.isEmpty()) return before + s2
        val flag = c.exit?.flag ?: throw Refuse("code after a return with no flag", s)
        val restOut = block(rest, c)
        val sClose = closeOf(s)
        edit(end(sClose), end(sClose), "\n${indentAt(tok(s))}if !$flag {")
        moveRest(sClose, tok(rest[0]), c, s)
        count("code after a return under if !done")
        return before + s2 + IfSelectionStatement(UnaryExpr(UnaryOp.NOT, Identifier(flag)), restOut, emptyList())
    }

    /** A loop's body: a return sets the result and flag and breaks; after an inner loop holding one, `if done { break }`. */
    private fun loopBlock(stmts: List<Statement>, c: Ctx): List<Statement> {
        if (stmts.none { hasReturn(it) }) return stmts
        val out = mutableListOf<Statement>()
        for ((k, st) in stmts.withIndex()) {
            if (!hasReturn(st)) {
                out += st
                continue
            }
            when {
                st is ReturnStatement -> {
                    out += exitOf(st, stmts, k, c)
                    count("loop return to break")
                }
                isLoop(st) -> {
                    val flag = c.exit?.flag ?: throw Refuse("a return two loops deep with no flag", st)
                    out += statementIn(st, c)
                    val close = closeOf(st)
                    val li = indentAt(tok(st))
                    edit(end(close), end(close), "\n${li}if $flag {\n$li${unitIndent}break\n$li}")
                    out += snippet("if $flag {\nbreak\n}")
                    count("inner loop return, then if done break")
                }
                else -> out += statementIn(st, c)
            }
        }
        return out
    }

    private fun bodyOf(s: Statement): List<Statement> = when (s) {
        is ForIterationStatement -> s.body
        is WhileIterationStatement -> s.statements
        else -> emptyList()
    }

    /** A value whose reading has no effect and that no code changes: a literal. */
    private fun movable(e: Expr): Boolean = e !in ctx.astParenthesized && when (e) {
        is IntegerLiteral, is FloatLiteral, is CharLiteral, is NullLiteral, is StringLiteral -> true
        is Identifier -> e.value == "true" || e.value == "false" || e.value == "null"
        is UnaryExpr -> e.operator == UnaryOp.NEG && (e.operand is IntegerLiteral || e.operand is FloatLiteral) && e.operand !in ctx.astParenthesized
        else -> false
    }

    private fun sliceOf(e: Expr): String {
        val t = tok(e)
        return if (e is UnaryExpr) text.substring(off(t), end(t + 1)) else text.substring(off(t), end(t))
    }

    /** The text of a value a result local of type [t] starts as, or null when it has none. */
    private fun zeroOf(t: Type): String? {
        val name = (t.identifier as? Identifier)?.value ?: return null
        val args = t.children
        return when {
            name == "Maybe" -> "null"
            name in setOf("List", "Map", "Set", "Arr") && args.isNotEmpty() -> "${typeSource(t)} { }"
            name.startsWith("Tuple") && args.isNotEmpty() -> args.map { zeroOf(it) ?: return null }.let { "${typeSource(t)} { ${it.joinToString(", ")} }" }
            args.isNotEmpty() -> null
            name == "Bool" -> "false"
            name in setOf("Int8", "Int16", "Int32", "Int64", "UInt8", "UInt16", "UInt32", "UInt64", "Size") -> "0"
            name in setOf("Float32", "Float64") -> "0.0"
            name == "Str" -> "\"\""
            name == "StrBuf" -> "StrBuf { }"
            name == "Json" -> "Json.null()"
            else -> null
        }
    }

    /** A type's text as written. */
    private fun typeSource(t: Type): String {
        val first = tok(t)
        var k = first
        // A name, then its `<...>` when it has them.
        if (toks.getOrNull(k + 1)?.type == Token.Type.S_OPEN_ANGLE) {
            var depth = 0
            k++
            while (k < toks.size) {
                when (toks[k].type) {
                    Token.Type.S_OPEN_ANGLE -> depth++
                    Token.Type.S_CLOSE_ANGLE -> if (--depth == 0) break
                    else -> {}
                }
                k++
            }
        }
        return text.substring(off(first), end(k))
    }

    /** The return type's text: from its first token to the body's `{`. */
    private fun typeText(u: Unit): String {
        val first = runCatching { tok(u.def.returnTypeSpecifier) }.getOrNull() ?: throw Refuse("a return type with no token", u.def)
        return text.substring(off(first), end(u.open - 1))
    }

    private fun fresh(base: String): String {
        var name = base
        var k = 2
        while (name in taken) name = "$base${k++}"
        taken += name
        return name
    }

    /** The identifiers of [u]'s tokens and of the module outside every top-level function. */
    private fun namesIn(u: Unit): Set<String> {
        val tops = units.filter { it.parent == null }
        return toks.indices.filter { j ->
            toks[j].type == Token.Type.IDENTIFIER && (j in u.open..u.close || tops.none { j in it.open..it.close })
        }.map { toks[it].content }.toSet()
    }

    // ---- the new text -------------------------------------------------------------------------

    private fun active(unit: Int): Boolean = unit < 0 || units[unit].top.id !in excluded

    /** Whether the unit [id]'s rewrites are applied. */
    fun isActive(id: Int): Boolean = active(id)

    /** Each line of the last [apply]'s text: the old line it came from. */
    var lineOrigins: IntArray = IntArray(0)

    /** The new text: every active edit, then each moved line one level deeper. */
    fun apply(): String {
        // Sorted by place; edits at one offset keep the order they were made in, an inner block's `}` first.
        val es = edits.filter { active(it.unit) }.sortedWith(compareBy({ it.start }, { it.end }))
        val delta = IntArray(lineStarts.size + 2)
        for (d in indents) if (active(d.unit)) for (l in d.from..d.to) delta[l]++
        val sb = StringBuilder()
        val origin = ArrayList<Int>(text.length + 64)
        var at = 0
        fun copy(from: Int, to: Int) {
            for (k in from until to) {
                sb.append(text[k])
                origin += lineOf(k)
            }
        }
        for (e in es) {
            if (e.start < at) throw IllegalStateException("overlapping edits at line ${lineOf(e.start)}")
            copy(at, e.start)
            for (ch in e.text) {
                sb.append(ch)
                origin += e.originLine
            }
            at = e.end
        }
        copy(at, text.length)
        // Each output line takes the shift of the line its first character came from.
        val out = StringBuilder()
        val origins = mutableListOf<Int>()
        var from = 0
        for (k in 0..sb.length) {
            if (k < sb.length && sb[k] != '\n') continue
            val line = sb.substring(from, k)
            val o = if (from < origin.size) origin[from] else lineStarts.size
            val shift = if (o in delta.indices) delta[o] else 0
            if (shift > 0 && line.isNotBlank()) repeat(shift) { out.append(unitIndent) }
            out.append(line)
            origins += o
            if (k < sb.length) out.append('\n')
            from = k + 1
        }
        lineOrigins = origins.toIntArray()
        return out.toString()
    }

    /** The top-level unit holding line [line], or null. */
    fun topUnitAtLine(line: Int): Unit? = units.firstOrNull { it.parent == null && lineOf(off(it.open)) <= line && line <= lineOf(end(it.close)) }

    /** The top-level unit holding [node], or null. */
    fun topUnitOf(node: ASTNode): Unit? = unitOf(node)?.top ?: (node as? FunctionDefExpr)?.let { d -> units.firstOrNull { it.def === d }?.top }

    /** The top-level units with rewrites. */
    fun touched(): Set<Int> = (edits.map { it.unit } + indents.map { it.unit }).filter { it >= 0 }.map { units[it].top.id }.toSet()
}
