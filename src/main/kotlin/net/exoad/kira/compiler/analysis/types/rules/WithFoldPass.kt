package net.exoad.kira.compiler.analysis.types.rules

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
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
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
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
)

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
 * field of x), where `x` is a name whose type is a Mutable class of the project (its `with` edits in place; a value type's
 * would build a copy), and the statement keeps its meaning inside `x with { }`: no bare name in it is a member of x, and
 * no local or parameter of its function is named like the member it uses. Statements fold only where they sit in a row.
 */
internal object WithFold {
    private class Candidate(
        val stmt: Statement,
        val receiver: Identifier,
        val member: String,
        val symbol: Symbol?,
        val start: Int,
        val end: Int,
    )

    fun runs(program: TypedProgram, modules: Set<ModuleSymbol>): List<FoldRun> {
        val c = PhaseC(program)
        val out = ArrayList<FoldRun>()
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

            fun candidate(s: Statement): Candidate? {
                if (s.javaClass != Statement::class.java) return null
                val e = s.expr
                val (origin, member) = when (e) {
                    is FunctionCallExpr -> {
                        val m = e.name as? MemberAccessExpr ?: return null
                        m.origin to ((m.member as? Identifier)?.takeIf { it.javaClass == Identifier::class.java }?.value ?: return null)
                    }
                    is PlaceAssignmentExpr -> {
                        val m = e.target as? MemberAccessExpr ?: return null
                        m.origin to ((m.member as? Identifier)?.takeIf { it.javaClass == Identifier::class.java }?.value ?: return null)
                    }
                    is CompoundAssignmentExpr -> {
                        val m = e.left as? MemberAccessExpr ?: return null
                        m.origin to ((m.member as? Identifier)?.takeIf { it.javaClass == Identifier::class.java }?.value ?: return null)
                    }
                    else -> return null
                }
                val id = origin as? Identifier ?: return null
                if (id.javaClass != Identifier::class.java) return null
                val type = program.model.types[id] as? KType.Nominal ?: return null
                val cls = type.sym as? ClassSymbol ?: return null
                if (cls.kind == ClassKind.MAGIC || cls.kind == ClassKind.OPAQUE || cls.foreign != null || !cls.isMutableType) return null
                val usable = when (e) {
                    is FunctionCallExpr -> c.members.method(type, member) != null
                    else -> c.members.field(type, member) != null
                }
                if (!usable || member in names) return null
                if (hasWith(s)) return null
                val at = ctx.astOrigins[id] ?: return null
                val start = index[key(at.lineNumber, at.column)] ?: return null
                if (tokens[start].content != id.value || tokens.getOrNull(start + 1)?.content != ".") return null
                val startCol = tokens[start].canonicalLocation.column
                if (lines.getOrNull(at.lineNumber - 1)?.substring(0, startCol - 1)?.isNotBlank() != false) return null
                val end = endOf(tokens, start)
                // A bare name that is a member of x means something else inside its block.
                val members = c.members.memberNames(type)
                for (i in start + 2..end) {
                    val t = tokens[i]
                    if (t.type == Token.Type.IDENTIFIER && t.content in members && tokens[i - 1].content != ".") return null
                }
                return Candidate(s, id, member, program.model.refs[id], start, end)
            }

            fun group(statements: List<ASTNode>) {
                var run = ArrayList<Candidate>()
                fun flush() {
                    if (run.size >= 2) {
                        val firstTok = ctx.tokens[run.first().start]
                        val lastTok = ctx.tokens[run.last().end]
                        out += FoldRun(
                            body.module, run.first().receiver.value, run.first().stmt, run.size,
                            firstTok.canonicalLocation.lineNumber, lastTok.canonicalLocation.lineNumber,
                            run.map { ctx.tokens[it.start].canonicalLocation.lineNumber },
                        )
                    }
                    run = ArrayList()
                }
                for (n in statements) {
                    val cand = (n as? Statement)?.let { candidate(it) }
                    if (cand == null) {
                        flush()
                        continue
                    }
                    val prev = run.lastOrNull()
                    if (prev != null && (cand.start != prev.end + 1 || cand.receiver.value != prev.receiver.value || cand.symbol !== prev.symbol)) flush()
                    run += cand
                }
                flush()
            }

            fun visit(n: ASTNode, inWith: Boolean) {
                val kids = AstTree.children(n)
                if (!inWith) group(kids)
                for (k in kids) visit(k, inWith || n is WithExpr)
            }
            group(body.roots)
            body.roots.forEach { visit(it, false) }
        }
        return out.sortedWith(compareBy({ it.module.source.file }, { it.firstLine }))
    }

    private fun key(line: Int, col: Int): Long = line.toLong() * 100_000L + col

    private fun hasWith(n: ASTNode): Boolean {
        var found = false
        AstTree.walk(n) { if (it is WithExpr) found = true }
        return found
    }

    /** The last token of the statement that starts at token [start]: before the next token that starts a line at or left of the statement's column, outside brackets. */
    private fun endOf(tokens: List<Token>, start: Int): Int {
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
            if (depth <= 0 && next.canonicalLocation.lineNumber > t.canonicalLocation.lineNumber && next.canonicalLocation.column <= startCol) return i
            i++
        }
        return i
    }
}
