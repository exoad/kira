package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.PhaseC
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/** A run of two or more statements in a row that act on one value `x` (`x.f()`, `x.field = v`): what `x with { ... }` holds. */
internal class FoldRun(
    val module: ModuleSymbol,
    val receiver: String,
    val first: Statement,
    val statements: Int,
    /** The 1-based lines of the run's first token and last token, and of the first token of each statement. */
    val firstLine: Int,
    val lastLine: Int,
    val startLines: List<Int>,
    /** The line the declaration of `x` right above the run ends on with its `}`, when the run can go onto it as `x: T { v } with { ... }`. */
    val declLine: Int? = null,
    /** Whether the receiver is a stdlib value (a List, a Map, a Json...), not a class of the project. */
    val stdlib: Boolean = false,
    /** The receiver's type, by its name alone (List, Json, a class's). */
    val type: String = "",
)

/** A `x with {` block right under the one declaration of `x`: [withLine] can go onto [declLine] as `x: T { v } with {`. */
internal class FoldJoin(val module: ModuleSymbol, val receiver: String, val declLine: Int, val withLine: Int)

/** Statements in a row on one value that stay as they are: [statements] of them from [line] on, and [why]. */
internal class FoldLeft(val module: ModuleSymbol, val receiver: String, val line: Int, val statements: Int, val why: String)

/** What [WithFold.find] found: the runs to fold, the blocks to join to their declaration, and the runs left with why. */
internal class Folds(val runs: List<FoldRun>, val joins: List<FoldJoin>, val left: List<FoldLeft>)

/**
 * The runs `x with { ... }` could hold ([WithFold.runs]), as the `rules.with.fold` hint. Only where the target lowers a
 * `with` (the py and cpp backends set [net.exoad.kira.compiler.analysis.types.TyperOptions.withFold]); only the project's own modules.
 */
internal class WithFoldPass : RulePass {
    override val name: String = "withfold"

    override fun run(program: TypedProgram) {
        if (!program.options.withFold) return
        for (run in WithFold.runs(program, program.workspaceModules.toSet())) {
            program.report(
                "rules.with.fold",
                "These ${run.statements} statements in a row all act on `${run.receiver}`: write them as `${run.receiver} with { ... }`, " +
                    "or run `kira --fix-with FILE...`.",
                run.first,
                Severity.NOTE,
            )
        }
    }
}

/**
 * The runs of [FoldRun]. A statement joins a run when it is `x.f(...)` (a method of x), or `x.field = v` or `x.field += v` (a
 * field of x), where `x` is a name whose type is a Mutable class of the project or a stdlib container or builder ([BUILT_UP]; its
 * `with` edits in place; a value type's would build a copy), and the statement keeps its meaning inside `x with { }`: no bare call in it
 * names a member of x, no bare name a field of x, no local or parameter of its function is named like the member it uses,
 * and nothing it calls could rebind a Mutable field or global that is the receiver. Statements fold only where they sit in a row.
 */
internal object WithFold {
    private class Candidate(
        val stmt: Statement,
        val receiver: Identifier,
        val symbol: Symbol?,
        val type: KType,
        val stdlib: Boolean,
        val start: Int,
        val end: Int,
    )

    /** The stdlib types whose runs are hinted and folded: the containers and builders, values built up call by call. */
    val BUILT_UP = setOf("List", "Map", "Set", "Stack", "Queue", "Deque", "Json", "StrBuf", "ArgSpec")

    /** A statement shaped `x.member...` at the start of its line: a [candidate] to fold, or [why] it stays. */
    private class Shaped(val receiver: String, val start: Int, val end: Int, val candidate: Candidate?, val why: String?)

    fun runs(program: TypedProgram, modules: Set<ModuleSymbol>): List<FoldRun> = find(program, modules).runs

    fun find(program: TypedProgram, modules: Set<ModuleSymbol>): Folds {
        val c = PhaseC(program)
        val out = ArrayList<FoldRun>()
        val joins = ArrayList<FoldJoin>()
        val left = ArrayList<FoldLeft>()
        for (body in Bodies.of(program)) {
            if (body.kind != BodyKind.FUNCTION || body.module !in modules) continue
            val ctx = body.module.source
            val tokens = ctx.tokens
            if (tokens.isEmpty()) continue
            val lines = ctx.content.split("\n")
            val index = HashMap<Long, Int>()
            tokens.forEachIndexed { i, t -> index[key(t.canonicalLocation.lineNumber, t.canonicalLocation.column)] = i }
            val names = HashSet<String>()
            for (s in program.model.declSyms.values) {
                if (s is LocalSymbol && s.fn === body.fn) names += s.name
                if (s is ParamSymbol && (s.fn === body.fn || (s.fn == null && s.module === body.module))) names += s.name
            }

            fun lineOf(i: Int): Int = tokens[i].canonicalLocation.lineNumber

            /** Whether the token at [i] is the last thing on its line, comments too. */
            fun endsLine(i: Int): Boolean {
                val t = tokens[i]
                val line = lines.getOrNull(lineOf(i) - 1) ?: return false
                val after = t.canonicalLocation.column - 1 + t.content.length
                return after <= line.length && line.substring(after).isBlank()
            }

            fun shaped(s: Statement): Shaped? {
                if (s.javaClass != Statement::class.java) return null
                val e = s.expr
                val access = when (e) {
                    is FunctionCallExpr -> e.name as? MemberAccessExpr
                    is PlaceAssignmentExpr -> e.target as? MemberAccessExpr
                    is CompoundAssignmentExpr -> e.left as? MemberAccessExpr
                    else -> null
                } ?: return null
                val member = (access.member as? Identifier)?.takeIf { it.javaClass == Identifier::class.java }?.value ?: return null
                val id = access.origin as? Identifier ?: return null
                if (id.javaClass != Identifier::class.java) return null
                val at = ctx.astOrigins[id] ?: return null
                val start = index[key(at.lineNumber, at.column)] ?: return null
                if (tokens[start].content != id.value || tokens.getOrNull(start + 1)?.content != ".") return null
                val startCol = tokens[start].canonicalLocation.column
                if (lines.getOrNull(at.lineNumber - 1)?.substring(0, startCol - 1)?.isNotBlank() != false) return null
                val end = endOf(tokens, lines, start)
                fun stays(why: String) = Shaped(id.value, start, end, null, why)
                val type = program.model.types[id] as? KType.Nominal ?: return null
                val cls = type.sym as? ClassSymbol ?: return null
                val stdlib = cls.kind == ClassKind.MAGIC
                if (cls.kind == ClassKind.OPAQUE || (!stdlib && cls.foreign != null)) return stays("the receiver's type is made by the host")
                if (!cls.isMutableType) {
                    return stays(
                        if (cls.kind == ClassKind.STRUCT) "the receiver is a value type (a Struct), whose `with` builds a copy" else "the receiver's type is not a Mutable type",
                    )
                }
                // A stdlib value that is not built up call by call (a Suite's checks, a socket's sends) reads best as it is.
                if (stdlib && cls.name !in BUILT_UP) return null
                val call = e is FunctionCallExpr
                val usable = if (call) c.members.method(type, member) != null else !stdlib && c.members.field(type, member) != null
                if (!usable) return null
                if (member in names) return stays("a local or a parameter of the function is named like the member the statement uses")
                if (hasWith(s)) return stays("the statement holds a `with` of its own")
                val symbol = program.model.refs[id]
                if (symbol !is LocalSymbol && symbol !is ParamSymbol && symbol !is FieldSymbol && symbol !is GlobalSymbol) return null
                val args: List<Expr> = when (e) {
                    is FunctionCallExpr -> e.positionalParameters.map { it.value } + e.namedParameters.map { it.value }
                    is PlaceAssignmentExpr -> listOf(e.value)
                    is CompoundAssignmentExpr -> listOf(e.right)
                    else -> emptyList()
                }
                // A call could give a Mutable field or global another value, which the block's receiver would not follow.
                val rebindable = (symbol as? FieldSymbol)?.isMut == true || (symbol as? GlobalSymbol)?.isMut == true
                if (rebindable && args.any { calls(it) }) return stays("the receiver is a Mutable field or global, and a call in the statement could give it another value")
                val methods = c.members.methodNames(type)
                val fields = if (stdlib) emptySet() else c.members.memberNames(type).filterTo(HashSet()) { c.members.field(type, it) != null }
                if (args.any { captured(it, methods + fields, fields) != null }) {
                    return stays("a bare name in the statement would mean the receiver's member inside its block")
                }
                return Shaped(id.value, start, end, Candidate(s, id, symbol, type, stdlib, start, end), null)
            }

            /** The line the declaration [prev] of the receiver of [run] ends on, when the run can go onto it. */
            fun declarationAbove(prev: ASTNode?, run: List<Candidate>): Int? {
                val decl = (prev as? Statement)?.takeIf { it.javaClass == Statement::class.java }?.expr as? VariableDecl ?: return null
                val first = run.first()
                val value = decl.value ?: return null
                if (value is WithExpr || decl.name.value != first.receiver.value || program.model.declSyms[decl] !== first.symbol) return null
                if (program.model.types[value] != first.type || (first.symbol as? LocalSymbol)?.type != first.type) return null
                val close = first.start - 1
                if (tokens.getOrNull(close)?.type != Token.Type.S_CLOSE_BRACE || !endsLine(close) || lineOf(close) != lineOf(first.start) - 1) return null
                // The name is not in reach inside its own declaration.
                if (run.any { r -> (r.start + 1..r.end).any { tokens[it].type == Token.Type.IDENTIFIER && tokens[it].content == first.receiver.value } }) return null
                return lineOf(close)
            }

            /** `x with {` right under the declaration [prev] of `x`: the join of the two. */
            fun join(prev: ASTNode?, n: ASTNode) {
                val w = (n as? Statement)?.takeIf { it.javaClass == Statement::class.java }?.expr as? WithExpr ?: return
                val id = w.receiver as? Identifier ?: return
                val decl = (prev as? Statement)?.takeIf { it.javaClass == Statement::class.java }?.expr as? VariableDecl ?: return
                val value = decl.value ?: return
                val symbol = program.model.refs[id] as? LocalSymbol ?: return
                if (value is WithExpr || decl.name.value != id.value || program.model.declSyms[decl] !== symbol) return
                if (program.model.withs[w]?.copy != false || program.model.types[value] != symbol.type || program.model.types[id] != symbol.type) return
                val at = ctx.astOrigins[id] ?: return
                val start = index[key(at.lineNumber, at.column)] ?: return
                if (tokens[start].content != id.value || tokens.getOrNull(start + 1)?.type != Token.Type.K_WITH) return
                if (tokens.getOrNull(start + 2)?.type != Token.Type.S_OPEN_BRACE || !endsLine(start + 2)) return
                val startCol = tokens[start].canonicalLocation.column
                if (lines.getOrNull(at.lineNumber - 1)?.substring(0, startCol - 1)?.isNotBlank() != false) return
                val close = start - 1
                if (tokens.getOrNull(close)?.type != Token.Type.S_CLOSE_BRACE || !endsLine(close) || lineOf(close) != lineOf(start) - 1) return
                val end = endOf(tokens, lines, start)
                if ((start + 1..end).any { tokens[it].type == Token.Type.IDENTIFIER && tokens[it].content == id.value }) return
                joins += FoldJoin(body.module, id.value, lineOf(close), lineOf(start))
            }

            fun group(statements: List<ASTNode>) {
                var run = ArrayList<Candidate>()
                var before: ASTNode? = null
                var shapes = ArrayList<Shaped>()
                fun flush() {
                    if (run.size >= 2) {
                        out += FoldRun(
                            body.module, run.first().receiver.value, run.first().stmt, run.size,
                            lineOf(run.first().start), lineOf(run.last().end), run.map { lineOf(it.start) },
                            declarationAbove(before, run), run.first().stdlib,
                            ((run.first().type as? KType.Nominal)?.sym as? ClassSymbol)?.name ?: "",
                        )
                    }
                    run = ArrayList()
                }
                fun flushShapes() {
                    val stay = shapes.filter { it.candidate == null }
                    if (shapes.size >= 2 && stay.isNotEmpty()) {
                        left += FoldLeft(body.module, shapes.first().receiver, lineOf(stay.first().start), stay.size, stay.first().why!!)
                    }
                    shapes = ArrayList()
                }
                var last: ASTNode? = null
                for (n in statements) {
                    join(last, n)
                    val shape = (n as? Statement)?.let { shaped(it) }
                    val prevShape = shapes.lastOrNull()
                    if (shape == null || (prevShape != null && (shape.start != prevShape.end + 1 || shape.receiver != prevShape.receiver))) flushShapes()
                    shape?.let { shapes += it }
                    val cand = shape?.candidate
                    if (cand == null) {
                        flush()
                        last = n
                        continue
                    }
                    val prev = run.lastOrNull()
                    if (prev != null && (cand.start != prev.end + 1 || cand.receiver.value != prev.receiver.value || cand.symbol !== prev.symbol)) flush()
                    if (run.isEmpty()) before = last
                    run += cand
                    last = n
                }
                flush()
                flushShapes()
            }

            fun visit(n: ASTNode, inWith: Boolean) {
                val kids = AstTree.children(n)
                if (!inWith) group(kids)
                for (k in kids) visit(k, inWith || n is WithExpr)
            }
            group(body.roots)
            body.roots.forEach { visit(it, false) }
        }
        return Folds(
            out.sortedWith(compareBy({ it.module.source.file }, { it.firstLine })),
            joins.sortedWith(compareBy({ it.module.source.file }, { it.withLine })),
            left.sortedWith(compareBy({ it.module.source.file }, { it.line })),
        )
    }

    private fun key(line: Int, col: Int): Long = line.toLong() * 100_000L + col

    private fun hasWith(n: ASTNode): Boolean {
        var found = false
        AstTree.walk(n) { if (it is WithExpr) found = true }
        return found
    }

    /** Whether [e] runs code of the project's: a call or a construction anywhere in it. */
    private fun calls(e: Expr): Boolean {
        var found = false
        AstTree.walk(e) { if (it is FunctionCallExpr || it is ObjectInitExpr) found = true }
        return found
    }

    /**
     * The first bare name in [e] the receiver's block would read as its member: a call of one of [callable], or a name that
     * is one of [fields] (a declared one too: a lambda's parameter cannot be named like a field there).
     */
    private fun captured(e: Expr, callable: Set<String>, fields: Set<String>): String? {
        var found: String? = null
        fun walk(n: ASTNode) {
            if (found != null) return
            when (n) {
                is MemberAccessExpr -> {
                    walk(n.origin)
                    (n.member as? FunctionCallExpr)?.let { m ->
                        m.positionalParameters.forEach { walk(it.value) }
                        m.namedParameters.forEach { walk(it.value) }
                    }
                    return
                }
                is FunctionCallNamedParameterExpr -> {
                    walk(n.value)
                    return
                }
                is FunctionCallExpr -> {
                    val callee = n.name
                    if (callee is Identifier) {
                        if (callee.value in callable) found = callee.value
                    } else {
                        walk(callee)
                    }
                    n.positionalParameters.forEach { walk(it.value) }
                    n.namedParameters.forEach { walk(it.value) }
                    return
                }
                is Type -> return
                is Identifier -> if (n.value in fields) found = n.value
                else -> {}
            }
            AstTree.children(n).forEach { walk(it) }
        }
        walk(e)
        return found
    }

    /**
     * The last token of the statement that starts at token [start]: before the next token that starts a line at or left of the
     * statement's column, outside brackets. A line's column is read from its text: an intrinsic's token (`@listOf`) sits past its `@`.
     */
    private fun endOf(tokens: List<Token>, lines: List<String>, start: Int): Int {
        val startCol = tokens[start].canonicalLocation.column
        var depth = 0
        var i = start
        while (i + 1 < tokens.size) {
            val t = tokens[i]
            when (t.type) {
                Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET, Token.Type.S_OPEN_BRACE -> depth++
                Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET, Token.Type.S_CLOSE_BRACE -> depth--
                else -> {}
            }
            val next = tokens[i + 1]
            if (next.type == Token.Type.S_EOF) return i
            if (depth <= 0 && next.canonicalLocation.lineNumber > t.canonicalLocation.lineNumber) {
                val line = lines.getOrNull(next.canonicalLocation.lineNumber - 1)
                val column = line?.indexOfFirst { !it.isWhitespace() }?.takeIf { it >= 0 }?.plus(1) ?: next.canonicalLocation.column
                if (minOf(column, next.canonicalLocation.column) <= startCol) return i
            }
            i++
        }
        return i
    }
}
