package net.exoad.kira.dialect

import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDefExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.source.SourceContext
import java.io.File
import java.util.IdentityHashMap

/**
 * `kira-dialect stage4 [--check] PATH...`: stage 3's text to stage 4's, Mutability. `mut x: T` is `x: Mutable<T>`; a
 * type with a `mut` field, a `mut fx`, a field some code writes (a value type's), a field whose value changes or a
 * Mutable parent is `T: Mutable<P>` (a value type's `Struct` parent becomes `Mutable<Any>`); `mut fx` goes (a stdlib
 * magic one is `@_mutates`); a `mut` parameter, argument or Fx parameter of a Mutable type goes, since its value is
 * shared. Every group is read first and the types decided together; each file is proved by its tree (the same but for
 * those shapes) and by its group typed with stage 4's rules (the errors it had, no more). Left for a hand fix (HAND):
 * a `mut` parameter of a type that does not change (an out-parameter), one the function assigns, the legacy `for mut`
 * loop, and every copy-then-change ([Stage4Copies]): the meaning stage 4 changes, listed to be checked.
 */
object Stage4 {
    val totals = LinkedHashMap<String, Int>()

    /** Read as text from before stage 5 is: an import's pub names bare, as `use` had them. */
    fun convertAll(paths: List<String>, check: Boolean): Int = DialectGates.aliasOnlyImports(false) { convertAllAsWritten(paths, check) }

    private fun convertAllAsWritten(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        totals.clear()
        val groups = files.groupBy { DialectMain.newUriOf(it).second }
        // The stdlib first: every other group reads its types, so it is decided with them, from its old text.
        val order = groups.keys.sortedBy { if (File(it, "core.kira").isFile && !File(it, "kira.yaml").isFile) 0 else 1 }
        val facts = Stage4Facts()
        val loaded = LinkedHashMap<String, Pair<ProjectUnits.Loaded, TypedProgram>>()
        for (g in order) {
            val (l, p) = old(File(g), groups.getValue(g).map { it.path })
            facts.read(p)
            loaded[g] = l to p
        }
        facts.decide()
        var proved = 0
        var bad = 0
        var hands = 0
        var handFiles = 0
        val outs = LinkedHashMap<File, Rewrite>()
        val groupOf = HashMap<File, String>()
        val rewrites = HashMap<String, Rewrite>()
        for (g in order) {
            val (l, p) = loaded.getValue(g)
            for (f in groups.getValue(g)) {
                val path = ProjectUnits.canonical(f.path)
                val ctx = l.sources[path] ?: continue
                rewrites[path] = Rewrite(f.readText(), ctx, p, facts).also { it.collect() }
            }
        }
        val edited = rewrites.filterValues { it.edits.isNotEmpty() }.keys
        for (g in order) {
            val (l, p) = loaded.getValue(g)
            // A group reads as old text when the run edits a file of its unit (its own, or its stdlib); then its copies
            // are checked, each body of the unit read, so a callee or a field's changes in another file count.
            val copyHand = IdentityHashMap<net.exoad.kira.source.SourceContext, MutableList<Pair<Int, String>>>()
            if (l.sources.keys.any { it in edited }) {
                val mine = groups.getValue(g).mapNotNull { l.sources[ProjectUnits.canonical(it.path)] }.toSet()
                val copies = Stage4Copies(p, facts, mine)
                copies.run()
                for ((n, why) in copies.hand) {
                    val (ctx, at) = p.locate(n) ?: continue
                    copyHand.getOrPut(ctx) { mutableListOf() } += (ctx.astOrigins[n]?.lineNumber ?: at.lineNumber) to why
                }
            }
            for (f in groups.getValue(g)) {
                val path = ProjectUnits.canonical(f.path)
                val ctx = l.sources[path]
                if (ctx == null) {
                    bad++
                    println("NOT-READ $f: ${l.failures[path] ?: "not in its group's unit"}")
                    continue
                }
                val rw = rewrites.getValue(path)
                val copies = copyHand[ctx].orEmpty()
                val hand = (rw.hand + copies).sortedBy { it.first }
                if (hand.isNotEmpty()) {
                    handFiles++
                    hands += hand.size
                    hand.forEach { (ln, why) -> println("HAND $f:$ln: $why") }
                }
                rw.stats.forEach { (k, v) -> totals[k] = (totals[k] ?: 0) + v }
                if (copies.isNotEmpty()) totals["copy-then-change to check"] = (totals["copy-then-change to check"] ?: 0) + copies.size
                if (rw.edits.isEmpty()) {
                    proved++
                    continue
                }
                outs[f] = rw
                groupOf[f] = g
            }
        }
        val texts = HashMap<String, String>()
        for ((f, rw) in outs) {
            try {
                texts[ProjectUnits.canonical(f.path)] = rw.apply()
            } catch (e: IllegalStateException) {
                bad++
                println("NOT-PROVED $f: ${e.message}")
            }
        }
        var changed = 0
        for (g in order) {
            val mine = outs.keys.filter { groupOf[it] == g && ProjectUnits.canonical(it.path) in texts }
            if (mine.isEmpty()) continue
            val (l, p) = loaded.getValue(g)
            // The group read again with every new text of the run (its stdlib's too), typed by stage 4's rules.
            val (again, p2) = DialectGates.stage4(true) {
                val a = ProjectUnits.load(File(g), literals = false, overlay = texts, extra = groups.getValue(g).map { it.path }, returns = false)
                a to KiraTyper.run(a.unit, TyperMode.STRICT)
            }
            for (f in mine) {
                val path = ProjectUnits.canonical(f.path)
                val nc = again.sources[path]
                val why = when {
                    nc == null -> "the new text does not parse: ${again.failures[path]}"
                    else -> Stage4Proof(l.sources.getValue(path), nc, outs.getValue(f).allowed).compare()?.let { "tree: $it" }
                        ?: errors(p, p2, path)
                }
                if (why != null) {
                    bad++
                    println("NOT-PROVED $f: $why")
                    continue
                }
                proved++
                changed++
                println("${if (check) "WOULD-CHANGE" else "CHANGED"} $f")
                if (!check) f.writeText(texts.getValue(path))
            }
        }
        println("kira-dialect stage4: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $hands hand checks in $handFiles files, $bad not converted")
        if (totals.isNotEmpty()) println("kira-dialect stage4 rewrites: " + totals.entries.joinToString(", ") { "${it.value} ${it.key}" })
        val users = facts.types.keys.filter { !it.module.startsWith("kira:") }
        println("kira-dialect stage4 Mutable types: ${users.count { facts.mutable(it) }} of ${users.size}")
        return if (bad > 0 || hands > 0 || (check && changed > 0)) 1 else 0
    }

    /** [group] (with [extra] beside it) as old text, typed with the old rules. */
    fun old(group: File, extra: List<String>): Pair<ProjectUnits.Loaded, TypedProgram> = DialectGates.stage4(false) {
        val l = ProjectUnits.load(group, literals = false, extra = extra, returns = false)
        l to KiraTyper.run(l.unit, TyperMode.STRICT)
    }

    /** Null when [path] holds the same errors under both programs (code and count), else what changed. */
    fun errors(p1: TypedProgram, p2: TypedProgram, path: String): String? {
        val before = codes(p1, path)
        val after = codes(p2, path)
        if (before == after) return null
        val diff = (before.keys + after.keys).filter { before[it] != after[it] }
        val first = p2.diagnostics.firstOrNull { it.isError && it.code in diff && it.source?.file?.let(ProjectUnits::canonical) == path }
        return "type: " + diff.joinToString { "$it ${before[it] ?: 0} became ${after[it] ?: 0}" } + (first?.let { " (${it.render()})" } ?: "")
    }

    private fun codes(p: TypedProgram, path: String): Map<String, Int> =
        p.diagnostics.filter { it.isError && it.source?.file?.let(ProjectUnits::canonical) == path }.groupingBy { it.code }.eachCount()

    fun line(ctx: SourceContext, n: ASTNode): Int = ctx.astOrigins[n]?.lineNumber ?: 0

    /** The edits stage 4 makes to one file, on its old [text] by its tokens; [allowed] the proof's shapes, by old node. */
    class Rewrite(private val text: String, private val ctx: SourceContext, private val p: TypedProgram, private val facts: Stage4Facts) {
        val edits = mutableListOf<DialectConverter.Edit>()
        val hand = mutableListOf<Pair<Int, String>>()
        val allowed: IdentityHashMap<ASTNode, String> = IdentityHashMap()
        val stats = LinkedHashMap<String, Int>()

        /** Each argument node's call. */
        private val callOf: IdentityHashMap<ASTNode, net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr> = IdentityHashMap()

        private val toks: List<Token> = ctx.tokens
        private val lineStarts = DialectConverter.lineStartsOf(text)
        private val indexAt = HashMap<Pair<Int, Int>, Int>().also { m ->
            toks.forEachIndexed { i, t -> m.putIfAbsent(t.canonicalLocation.lineNumber to t.canonicalLocation.column, i) }
        }

        private fun count(what: String) {
            stats[what] = (stats[what] ?: 0) + 1
        }

        fun collect() {
            for (st in ctx.ast.statements) {
                when (val e = (st as? Statement)?.expr) {
                    is ClassDecl, is StructDecl, is TraitDecl -> typeDecl(e)
                    else -> {}
                }
            }
            for (n in AstWalk.all(ctx.ast)) {
                if (n is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr) {
                    n.positionalParameters.forEach { callOf[it] = n }
                    n.namedParameters.forEach { callOf[it] = n }
                }
            }
            for (n in AstWalk.all(ctx.ast)) {
                when (n) {
                    is VariableDecl -> variable(n)
                    is FunctionDecl -> function(n)
                    is FunctionDefExpr -> n.parameters.forEach { param(it, n) }
                    is FunctionCallPositionalParameterExpr -> if (n.isMut) argument(n, n.value)
                    is FunctionCallNamedParameterExpr -> if (n.isMut) argument(n, n.value)
                    is Type -> if (n.isMutParam) fxParam(n)
                    is ForIterationExpr -> if (n.isLegacy) hand += line(n) to "the legacy `for mut i: a..b` loop: write `for i: Int32 in a..(b + 1)`"
                    else -> {}
                }
            }
        }

        private fun line(n: ASTNode): Int = ctx.astOrigins[n]?.lineNumber ?: 0

        private fun symOf(n: ASTNode) = p.model.declSyms[n]

        // ---- types --------------------------------------------------------------------------------

        private fun typeDecl(d: ASTNode) {
            val name = when (d) {
                is ClassDecl -> d.name
                is StructDecl -> d.name
                is TraitDecl -> d.name
                else -> return
            }
            val sym = symOf(d) as? TypeSymbol ?: return
            val k = facts.key(sym)
            val already = when (d) {
                is ClassDecl -> Modifier.MUTABLE in d.modifiers
                is StructDecl -> Modifier.MUTABLE in d.modifiers
                is TraitDecl -> Modifier.MUTABLE in d.modifiers
                else -> false
            }
            if (already || !facts.mutable(k)) return
            val at = index(name) ?: return problem(d, "a type whose name has no token")
            // After the name (and its type parameters), the `:` and the parent list.
            var i = at + 1
            if (toks.getOrNull(i)?.type == Token.Type.S_OPEN_ANGLE) i = closeAngle(i) + 1
            if (toks.getOrNull(i)?.type != Token.Type.S_COLON) return problem(d, "a type with no ':' after its name")
            val first = i + 1
            val struct = parentTokens(first).firstOrNull { toks[it].type == Token.Type.IDENTIFIER && toks[it].content == "Struct" }
            if (d is StructDecl && struct != null) {
                edits += DialectConverter.Edit(offsetOf(struct), endOf(struct), "Mutable<Any>")
                count("value type to Mutable<Any>")
            } else {
                val end = typeEnd(first)
                edits += DialectConverter.Edit(offsetOf(first), offsetOf(first), "Mutable<")
                edits += DialectConverter.Edit(endOf(end), endOf(end), ">")
                count("type to Mutable<P>")
            }
            allowed[d] = "type"
        }

        /** The token indices of a parent list from [first] to its `{` or the line's end, at depth 0. */
        private fun parentTokens(first: Int): List<Int> {
            val out = mutableListOf<Int>()
            var depth = 0
            var j = first
            val lineNo = toks[first].canonicalLocation.lineNumber
            while (j < toks.size) {
                val t = toks[j]
                if (depth == 0 && (t.type == Token.Type.S_OPEN_BRACE || t.canonicalLocation.lineNumber != lineNo)) break
                when (t.type) {
                    Token.Type.S_OPEN_ANGLE, Token.Type.S_OPEN_PARENTHESIS -> depth++
                    Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_PARENTHESIS -> depth--
                    else -> {}
                }
                if (depth == 0) out += j
                j++
            }
            return out
        }

        // ---- bindings and fields ----------------------------------------------------------------------

        private fun variable(v: VariableDecl) {
            val sym = symOf(v)
            val mut = Modifier.MUTABLE in v.modifiers
            val field = sym as? FieldSymbol
            val gains = !mut && field != null && facts.mutableField(facts.key(field.owner), field.name)
            if (!mut && !gains) return
            val nameAt = index(v.name) ?: return problem(v, "a binding whose name has no token")
            if (mut) {
                // `x: Mutable<T>` is stage 4's already.
                val m = mutBefore(nameAt) ?: return
                edits += DialectConverter.Edit(offsetOf(m), offsetOf(m + 1), "")
            }
            val start = index(v.type) ?: return problem(v, "a binding whose type has no token")
            val end = typeEnd(start)
            edits += DialectConverter.Edit(offsetOf(start), offsetOf(start), "Mutable<")
            edits += DialectConverter.Edit(endOf(end), endOf(end), ">")
            if (gains) {
                allowed[v] = "field"
                count("value type field to Mutable<T>")
            } else {
                count(if (field != null) "mut field to Mutable<T>" else "mut binding to Mutable<T>")
            }
        }

        // ---- functions ----------------------------------------------------------------------------

        private fun function(f: FunctionDecl) {
            if (Modifier.MUTABLE !in f.modifiers) return
            val fn = symOf(f) as? FnSymbol
            val owner = fn?.owner
            val nameAt = index(f.name) ?: return problem(f, "a `mut fx` whose name has no token")
            val m = mutBefore(nameAt) ?: return problem(f, "a `mut fx` whose `mut` has no token")
            if (owner == null) {
                hand += line(f) to "a `mut` function outside a type"
                return
            }
            // A host's function (a @_magic, @_opaque or @_extern type's) says it changes its receiver with @_mutates.
            val magic = (owner as? ClassSymbol)?.let { it.kind == ClassKind.MAGIC || it.kind == ClassKind.OPAQUE || it.foreign != null } == true
            edits += DialectConverter.Edit(offsetOf(m), offsetOf(m + 1), if (magic) "@_mutates " else "")
            allowed[f] = if (magic) "mutates" else "function"
            count(if (magic) "mut fx to @_mutates" else "mut fx to a function of a Mutable type")
        }

        private fun param(pd: FunctionDeclParameterExpr, def: FunctionDefExpr) {
            if (Modifier.MUTABLE !in pd.modifiers) return
            val sym = symOf(pd) as? ParamSymbol
            val t = sym?.type
            val why = when {
                t == null -> "a `mut` parameter the typer did not reach"
                assigned(def, sym) -> "the `mut` parameter '${pd.name.value}' is assigned, which no parameter can be: return the new value"
                // A host's function has no body to read: its `mut` parameter may be written (a C++ T& or T*).
                !shared(t) && (def.body == null || written(def, sym)) -> "the `mut` parameter '${pd.name.value}' of ${t.display()}, which does not change: return the new value instead"
                else -> null
            }
            if (why != null) {
                hand += line(pd) to why
                return
            }
            val nameAt = index(pd.name) ?: return problem(pd, "a parameter whose name has no token")
            val m = mutBefore(nameAt) ?: return problem(pd, "a `mut` parameter whose `mut` has no token")
            edits += DialectConverter.Edit(offsetOf(m), offsetOf(m + 1), "")
            allowed[pd] = "param"
            count(if (shared(t!!)) "mut parameter of a Mutable type dropped" else "mut parameter the function never writes dropped")
        }

        /** Whether [def]'s body changes the parameter [sym] in place: a field or element written, a mutator, a `mut` argument, a MutView. */
        private fun written(def: FunctionDefExpr, sym: ParamSymbol): Boolean {
            fun mine(e: net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr?): Boolean =
                e != null && (p.model.readPlace(e)?.root() as? Place.Param)?.sym === sym
            var hit = false
            def.body?.forEach { s ->
                net.exoad.kira.compiler.analysis.types.AstTree.walk(s) { n ->
                    when (n) {
                        is AssignmentExpr -> if (mine(n.target)) hit = true
                        is net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr -> if (mine(n.left)) hit = true
                        is net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr -> if (mine(n.target)) hit = true
                        is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr -> {
                            val rc = p.model.calls[n]
                            if (rc != null && rc.fn?.isMutMethod == true && mine(rc.receiver)) hit = true
                            if (rc != null && rc.args.any { a -> a is net.exoad.kira.compiler.analysis.types.ArgBinding.Given && a.byRef && mine(a.expr) }) hit = true
                            if (rc != null && rc.fn?.name in setOf("from", "slice", "view") && mine(rc.receiver) &&
                                (p.model.types[n] as? net.exoad.kira.compiler.analysis.types.KType.Nominal)?.sym?.name == "MutView") hit = true
                        }
                        else -> {}
                    }
                }
            }
            return hit
        }

        /** Whether the `mut` of the declared parameter [sym] goes (as [param] decides it), from its function's own body. */
        private fun drops(sym: ParamSymbol): Boolean {
            val def = (sym.fn?.decl as? FunctionDecl)?.def ?: return false
            return !assigned(def, sym) && (shared(sym.type) || (def.body != null && !written(def, sym)))
        }

        /** A value whose `mut` passing goes: a Mutable type's (shared), or a type parameter's (shared when it is one). */
        private fun shared(t: net.exoad.kira.compiler.analysis.types.KType): Boolean =
            t is net.exoad.kira.compiler.analysis.types.KType.Param || facts.mutable(facts.key(t))

        /** Whether [def]'s body assigns the parameter [sym] itself. */
        private fun assigned(def: FunctionDefExpr, sym: ParamSymbol): Boolean {
            var hit = false
            def.body?.forEach { s ->
                net.exoad.kira.compiler.analysis.types.AstTree.walk(s) { n ->
                    if (n is AssignmentExpr && (p.model.places[n.target] as? Place.Param)?.sym === sym) hit = true
                }
            }
            return hit
        }

        private fun argument(arg: ASTNode, value: net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr) {
            val t = p.model.types[value]
            // The argument of a parameter whose `mut` goes: of a Mutable type, or one the callee never writes.
            val call = callOf[arg]
            val param = call?.let { p.model.calls[it] }?.let { rc -> rc.args.indexOfFirst { (it as? net.exoad.kira.compiler.analysis.types.ArgBinding.Given)?.expr === value } }
                ?.takeIf { it >= 0 }?.let { i -> p.model.calls[call]?.fn?.params?.getOrNull(i) }
            if (t == null || !(shared(t) || (param != null && drops(param)))) {
                hand += line(arg) to "the `mut` argument '${net.exoad.kira.compiler.analysis.types.KiraUnparser.text(value)}' of ${t?.display() ?: "an unknown type"}, which does not change"
                return
            }
            val at = index(value) ?: return problem(arg, "a `mut` argument with no token")
            // `f(mut x)` or `f(out = mut x)`: the `mut` right before the value.
            val m = (at - 1).takeIf { it >= 0 && toks[it].type == Token.Type.K_MODIFIER_MUTABLE } ?: return problem(arg, "a `mut` argument whose `mut` is not before its value")
            edits += DialectConverter.Edit(offsetOf(m), offsetOf(m + 1), "")
            allowed[arg] = "arg"
            count("mut argument dropped")
        }

        /** `Tuple1<mut T>` or a function type's `(mut v: T)`: the `mut` before the type, or before its parameter's name. */
        private fun fxParam(t: Type) {
            val resolved = p.model.typeRefs[t]
            if (resolved == null || !shared(resolved)) {
                hand += line(t) to "a `mut` parameter of a function type, ${resolved?.display() ?: "unresolved"}, which does not change"
                return
            }
            val at = index(t) ?: return problem(t, "a `mut` type with no token")
            var m = if (toks[at].type == Token.Type.K_MODIFIER_MUTABLE) at + 1 else at
            m -= 1
            if (m >= 1 && toks[m].type == Token.Type.S_COLON && toks[m - 1].type == Token.Type.IDENTIFIER) m -= 2
            if (m < 0 || toks[m].type != Token.Type.K_MODIFIER_MUTABLE) {
                hand += line(t) to "a `mut` parameter of a function type written from a parameter list: drop its `mut` by hand"
                return
            }
            edits += DialectConverter.Edit(offsetOf(m), offsetOf(m + 1), "")
            allowed[t] = "fxparam"
            count("mut Fx parameter dropped")
        }

        // ---- tokens ---------------------------------------------------------------------------------

        private fun problem(n: ASTNode, why: String) {
            hand += line(n) to why
        }

        private fun index(n: ASTNode): Int? = ctx.astOrigins[n]?.let { indexAt[it.lineNumber to it.column] }

        /** The `mut` among the modifiers before the name at [nameAt]. */
        private fun mutBefore(nameAt: Int): Int? {
            var j = nameAt - 1
            while (j >= 0) {
                when (toks[j].type) {
                    Token.Type.K_MODIFIER_MUTABLE -> return j
                    in Token.Type.modifiers, Token.Type.INTRINSIC_IDENTIFIER -> j--
                    Token.Type.S_CLOSE_PARENTHESIS -> j = openParen(j) - 1
                    else -> return null
                }
            }
            return null
        }

        private fun openParen(close: Int): Int {
            var depth = 0
            for (j in close downTo 0) {
                when (toks[j].type) {
                    Token.Type.S_CLOSE_PARENTHESIS -> depth++
                    Token.Type.S_OPEN_PARENTHESIS -> if (--depth == 0) return j
                    else -> {}
                }
            }
            return 0
        }

        private fun closeAngle(open: Int): Int {
            var depth = 0
            for (j in open until toks.size) {
                when (toks[j].type) {
                    Token.Type.S_OPEN_ANGLE -> depth++
                    Token.Type.S_CLOSE_ANGLE -> if (--depth == 0) return j
                    else -> {}
                }
            }
            return toks.size - 1
        }

        private fun closeParen(open: Int): Int {
            var depth = 0
            for (j in open until toks.size) {
                when (toks[j].type) {
                    Token.Type.S_OPEN_PARENTHESIS -> depth++
                    Token.Type.S_CLOSE_PARENTHESIS -> if (--depth == 0) return j
                    else -> {}
                }
            }
            return toks.size - 1
        }

        /** The last token of the type that starts at [i]: a name (maybe `a.Name`) with its `<...>`, or `(...) R`. */
        private fun typeEnd(i: Int): Int {
            if (toks[i].type == Token.Type.S_OPEN_PARENTHESIS) return typeEnd(closeParen(i) + 1)
            var j = i
            while (toks.getOrNull(j + 1)?.type == Token.Type.S_DOT && toks.getOrNull(j + 2)?.type == Token.Type.IDENTIFIER) j += 2
            if (toks.getOrNull(j + 1)?.type == Token.Type.S_OPEN_ANGLE) j = closeAngle(j + 1)
            return j
        }

        private fun offsetOf(i: Int): Int {
            if (i >= toks.size) return text.length
            val t = toks[i]
            if (t.type == Token.Type.S_EOF) return text.length
            val pos = t.canonicalLocation
            val at = if (t.type == Token.Type.INTRINSIC_IDENTIFIER) 1 else 0
            return lineStarts[pos.lineNumber - 1] + pos.column - 1 - at
        }

        private fun endOf(i: Int): Int = offsetOf(i) + toks[i].content.length + (if (toks[i].type == Token.Type.INTRINSIC_IDENTIFIER) 1 else 0)

        fun apply(): String {
            val sb = StringBuilder()
            var at = 0
            for (e in edits.sortedWith(compareBy({ it.start }, { it.end }))) {
                if (e.start < at) error("overlapping edits at offset ${e.start}")
                sb.append(text, at, e.start).append(e.text)
                at = e.end
            }
            sb.append(text, at, text.length)
            return sb.toString()
        }
    }
}
