package net.exoad.kira.dialect

import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Builder
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.source.SourceContext
import java.io.File
import java.util.IdentityHashMap

/**
 * `kira-dialect stage2 [--check] PATH...`: the clean dialect's stage 2 on .kira files in stage 1's.
 * Each `[...]` literal becomes the builder of the type stage 1's typer gave it (`@listOf(...)`
 * for a List, `@arrOf(...)` for an Arr), and `p.copy(f = v)`, which never compiled, becomes
 * `p with {\n    f = v\n}`. Each file's group (its project, the stdlib, or its folder) is typed
 * as the compiler reads it, so a `copy` that is a method of the receiver stays. Each converted
 * file is proved: its tree equals the old one but for those shapes, and its group typed again
 * gives each builder the type its literal had.
 */
object Stage2 {
    /** Read as text from before stage 5 is: an import's pub names bare, as `use` had them. */
    fun convertAll(paths: List<String>, check: Boolean): Int = DialectGates.aliasOnlyImports(false) { convertAllAsWritten(paths, check) }

    private fun convertAllAsWritten(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        var changed = 0
        var proved = 0
        var bad = 0
        for ((group, members) in files.groupBy { DialectMain.newUriOf(it).second }) {
            val extra = members.map { it.path }
            val loaded = ProjectUnits.load(File(group), literals = true, extra = extra)
            val program = KiraTyper.run(loaded.unit, TyperMode.STRICT)
            val outs = LinkedHashMap<File, Rewrite>()
            for (f in members) {
                val path = ProjectUnits.canonical(f.path)
                val old = loaded.sources[path]
                if (old == null) {
                    bad++
                    println("NOT-READ $f: ${loaded.failures[path] ?: "not in its group's unit"}")
                    continue
                }
                val run = Rewrite(f.readText(), old, program)
                run.collect()
                if (run.problems.isNotEmpty()) {
                    bad++
                    run.problems.forEach { println("PROBLEM $f:$it") }
                    continue
                }
                if (run.edits.isEmpty()) {
                    proved++
                    continue
                }
                outs[f] = run
            }
            if (outs.isEmpty()) {
                continue
            }
            // The group again with the new texts, linked and typed as the old one was: its tree, and each builder's type.
            val texts = outs.entries.associate { (f, run) -> ProjectUnits.canonical(f.path) to run.apply() }
            val again = ProjectUnits.load(File(group), literals = true, texts, extra)
            val program2 = KiraTyper.run(again.unit, TyperMode.STRICT)
            for ((f, run) in outs) {
                val path = ProjectUnits.canonical(f.path)
                val nc = again.sources[path]
                val diff = when {
                    nc == null -> "the new text does not parse: ${again.failures[path]}"
                    texts.getValue(path).contains('[') && ProjectUnits.read(CompilationUnit(bootstrap = false), path, texts.getValue(path), null, literals = false, returns = true).first == null ->
                        "the new text still holds a `[...]` literal"
                    else -> Proof(loaded.sources.getValue(path), nc, run.rewritten).compare()?.let { "tree: $it" }
                        ?: typed(loaded.sources.getValue(path), program, nc, program2)?.let { "type: $it" }
                }
                if (diff != null) {
                    bad++
                    println("NOT-PROVED $f: $diff")
                    continue
                }
                proved++
                changed++
                if (!check) f.writeText(texts.getValue(path))
            }
        }
        println("kira-dialect stage2: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $bad not converted")
        return if (bad > 0 || (check && changed > 0)) 1 else 0
    }

    /** The first builder of [nc] whose type under [p2] is not its old literal's in [oc] under [p1], or null. */
    fun typed(oc: SourceContext, p1: TypedProgram, nc: SourceContext, p2: TypedProgram): String? {
        val olds = AstWalk.all(oc.ast).filterIsInstance<BuilderExpr>()
        val news = AstWalk.all(nc.ast).filterIsInstance<BuilderExpr>()
        if (olds.size != news.size) return "${olds.size} literals became ${news.size} builders"
        for ((o, n) in olds.zip(news)) {
            val a = p1.model.types[o]?.display()
            val b = p2.model.types[n]?.display()
            if (a != b) {
                val at = nc.astOrigins[n]?.let { "${it.lineNumber}:${it.column}" } ?: "?"
                return "the builder at $at is $b, its literal was $a"
            }
        }
        return null
    }

    /** The edits stage 2 makes to one file, on its original [text] by its tokens. */
    class Rewrite(private val text: String, private val ctx: SourceContext, private val program: TypedProgram) {
        val problems = mutableListOf<String>()
        val edits = mutableListOf<DialectConverter.Edit>()

        /** The old nodes rewritten, each by the shape it became: the proof's allowances. */
        val rewritten: MutableMap<ASTNode, String> = IdentityHashMap()

        private val toks: List<Token> = ctx.tokens
        private val lineStarts = DialectConverter.lineStartsOf(text)
        private val indexAt = HashMap<Pair<Int, Int>, Int>().also { m ->
            toks.forEachIndexed { i, t -> m.putIfAbsent(t.canonicalLocation.lineNumber to t.canonicalLocation.column, i) }
        }

        /** The arguments of calls and constructions the typer could not resolve: no context reached them. */
        private val unbound: MutableSet<ASTNode> = java.util.Collections.newSetFromMap(IdentityHashMap())

        fun collect() {
            val nodes = AstWalk.all(ctx.ast)
            for (n in nodes) {
                when (n) {
                    is FunctionCallExpr -> if (program.model.calls[n] == null) {
                        n.positionalParameters.forEach { unbound.add(it.value) }
                        n.namedParameters.forEach { unbound.add(it.value) }
                    }
                    is ObjectInitExpr -> if (program.model.inits[n] == null) {
                        unbound.addAll(n.positionalArgs)
                        n.namedArgs.forEach { unbound.add(it.value) }
                    }
                    else -> {}
                }
            }
            for (n in nodes) {
                when (n) {
                    is FunctionCallExpr -> copy(n)
                    is BuilderExpr -> if (n.kind == Builder.INFER) literal(n)
                    else -> {}
                }
            }
        }

        /** `[a, b]` as the builder of the type the typer gave it: `@listOf(a, b)` or `@arrOf(a, b)`. */
        private fun literal(n: BuilderExpr) {
            val kind = program.model.inferred[n] ?: run {
                problems += "${where(n)}: a [...] the typer did not reach"
                return
            }
            // A literal typed in error, or given to a callee the typer could not find, says nothing of what it meant.
            if (program.model.types[n]?.containsError() != false || n in unbound) {
                problems += "${where(n)}: a [...] whose context the typer could not tell, so its builder is a hand fix"
                return
            }
            val open = indexOf(n)?.takeIf { toks[it].type == Token.Type.S_OPEN_BRACKET } ?: run {
                problems += "${where(n)}: a [...] whose '[' has no token"
                return
            }
            val close = closing(open) ?: return
            edits += DialectConverter.Edit(offsetOf(open), offsetOf(open) + 1, "@${kind.intrinsic}(")
            edits += DialectConverter.Edit(offsetOf(close), offsetOf(close) + 1, ")")
            rewritten[n] = kind.name
        }

        private fun where(n: ASTNode): String = ctx.astOrigins[n]?.let { "${it.lineNumber}:${it.column}" } ?: "?"

        fun apply(): String {
            val sb = StringBuilder()
            var at = 0
            for (e in edits.sortedWith(compareBy({ it.start }, { it.end }))) {
                if (e.start < at) {
                    problems += "overlapping edits at offset ${e.start}"
                    continue
                }
                sb.append(text, at, e.start).append(e.text)
                at = e.end
            }
            sb.append(text, at, text.length)
            return sb.toString()
        }

        private fun offsetOf(i: Int): Int {
            val p = toks[i].canonicalLocation
            val at = if (toks[i].type == Token.Type.INTRINSIC_IDENTIFIER) 1 else 0
            return lineStarts[p.lineNumber - 1] + p.column - 1 - at
        }

        private fun indexOf(node: ASTNode): Int? = ctx.astOrigins[node]?.let { indexAt[it.lineNumber to it.column] }

        private fun lineIndent(offset: Int): String {
            var k = offset
            while (k > 0 && text[k - 1] != '\n') k--
            var e = k
            while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
            return text.substring(k, e)
        }

        /** The token closing the bracket opened at [open]. */
        private fun closing(open: Int): Int? {
            var depth = 0
            for (j in open until toks.size) {
                when (toks[j].type) {
                    Token.Type.S_OPEN_PARENTHESIS, Token.Type.S_OPEN_BRACKET, Token.Type.S_OPEN_BRACE -> depth++
                    Token.Type.S_CLOSE_PARENTHESIS, Token.Type.S_CLOSE_BRACKET, Token.Type.S_CLOSE_BRACE -> if (--depth == 0) return j
                    else -> {}
                }
            }
            return null
        }

        /** The text of each top-level comma-separated item between the brackets at [open] and [close]. */
        private fun items(open: Int, close: Int): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var start = open + 1
            for (j in open + 1..close) {
                val t = toks[j].type
                when {
                    j == close || (depth == 0 && t == Token.Type.S_COMMA) -> {
                        if (start < j) out += text.substring(offsetOf(start), offsetOf(j)).trim()
                        start = j + 1
                    }
                    t == Token.Type.S_OPEN_PARENTHESIS || t == Token.Type.S_OPEN_BRACKET || t == Token.Type.S_OPEN_BRACE -> depth++
                    t == Token.Type.S_CLOSE_PARENTHESIS || t == Token.Type.S_CLOSE_BRACKET || t == Token.Type.S_CLOSE_BRACE -> depth--
                }
            }
            return out
        }

        /** `r.copy(f = v, ...)` that resolved to no method: `r with {\n    f = v\n}`. */
        private fun copy(call: FunctionCallExpr) {
            val callee = call.name as? MemberAccessExpr ?: return
            val member = callee.member as? Identifier ?: return
            if (member.value != "copy" || call.positionalParameters.isNotEmpty() || call.namedParameters.isEmpty()) return
            if (program.model.calls[call]?.fn != null) return
            val at = indexOf(member) ?: run {
                problems += "a copy(...) whose name has no token"
                return
            }
            if (at < 1 || toks[at - 1].type != Token.Type.S_DOT || toks[at + 1].type != Token.Type.S_OPEN_PARENTHESIS) return
            val close = closing(at + 1) ?: return
            val start = offsetOf(at - 1)
            val indent = lineIndent(start)
            val lines = items(at + 1, close).joinToString("\n") { "$indent    $it" }
            edits += DialectConverter.Edit(start, offsetOf(close) + 1, " with {\n$lines\n$indent}")
            rewritten[call] = "with"
        }
    }

    /**
     * The old tree (stage 1's text) against the new one: equal but where [rewritten] says a
     * node became another shape, `r.copy(f = v)` the `with` assigning `f = v`.
     */
    class Proof(private val oc: SourceContext, private val nc: SourceContext, private val rewritten: Map<ASTNode, String>) {
        fun compare(): String? = list("root", oc.ast.statements, nc.ast.statements)

        private fun list(path: String, o: List<*>, n: List<*>): String? {
            if (o.size != n.size) return "$path: ${o.size} entries became ${n.size}"
            for (i in o.indices) value("$path[$i]", o[i], n[i])?.let { return it }
            return null
        }

        private fun value(path: String, o: Any?, n: Any?): String? {
            if (o is ASTNode && n is ASTNode) return node(path, o, n)
            if (o is List<*> && n is List<*>) return list(path, o, n)
            if (o is Array<*> && n is Array<*>) return list(path, o.toList(), n.toList())
            if (o is Map<*, *> && n is Map<*, *>) {
                if (o.keys != n.keys) return "$path: keys ${o.keys} became ${n.keys}"
                for (k in o.keys) value("$path[$k]", o[k], n[k])?.let { return it }
                return null
            }
            if (o != null && n != null && o.javaClass == n.javaClass && AstWalk.isAstPart(o)) {
                for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
                return null
            }
            if (o is net.exoad.kira.source.SourceLocation && n is net.exoad.kira.source.SourceLocation) return null
            if (o == n) return null
            return "$path: '$o' became '$n'"
        }

        private fun node(path: String, o: ASTNode, n: ASTNode): String? {
            when (val shape = rewritten[o]) {
                null -> {}
                "with" -> return with(path, o as FunctionCallExpr, n)
                else -> return builder(path, o as BuilderExpr, n, Builder.valueOf(shape))
            }
            if (o.javaClass != n.javaClass) return "$path: ${o.javaClass.simpleName} became ${n.javaClass.simpleName}"
            marks(path, o, n)?.let { return it }
            for (f in AstWalk.fieldsOf(o.javaClass)) value("$path.${f.name}", f.get(o), f.get(n))?.let { return it }
            return null
        }

        /** `[a, b]` became the builder [kind] of the same elements, with no type arguments. */
        private fun builder(path: String, o: BuilderExpr, n: ASTNode, kind: Builder): String? {
            if (n !is BuilderExpr || n.kind != kind || n.typeArgs.isNotEmpty()) return "$path: a [...] typed ${kind.intrinsic} became $n"
            return list("$path.elements", o.elements, n.elements)
        }

        /** `r.copy(f = v, ...)` became `r with { f = v ... }`: the receiver and each assignment, in order. */
        private fun with(path: String, o: FunctionCallExpr, n: ASTNode): String? {
            if (n !is WithExpr) return "$path: a copy(...) became ${n.javaClass.simpleName}"
            value("$path.receiver", (o.name as MemberAccessExpr).origin, n.receiver)?.let { return it }
            if (o.namedParameters.size != n.body.size) return "$path: ${o.namedParameters.size} fields became ${n.body.size} statements"
            o.namedParameters.zip(n.body).forEachIndexed { i, (p, s) ->
                val a = (s.takeIf { it.javaClass == Statement::class.java }?.expr as? AssignmentExpr) ?: return "$path.body[$i]: not an assignment"
                if (a.target.value != p.name.value) return "$path.body[$i]: '${p.name.value}' became '${a.target.value}'"
                value("$path.body[$i]", p.value, a.value)?.let { return it }
            }
            return null
        }

        private fun marks(path: String, o: ASTNode, n: ASTNode): String? {
            val om = oc.intrinsicInvocationsOf(o)
            val nm = nc.intrinsicInvocationsOf(n)
            if (om.map { it.intrinsicKey.name } != nm.map { it.intrinsicKey.name }) return "$path: marks ${om.map { it.intrinsicKey.name }} became ${nm.map { it.intrinsicKey.name }}"
            if ((o in oc.astParenthesized) != (n in nc.astParenthesized)) return "$path: parentheses changed"
            return null
        }
    }
}
