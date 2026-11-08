package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ContinueStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import net.exoad.kira.source.ForLoopRecord
import net.exoad.kira.source.SourceContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The migration for the removed `for` (every loop is `while <condition> { }`): the diagnostic's text, and the rewrite of
 * a file's loops. A range gets a counter before the loop, a collection an index; a loop whose rewrite could change what
 * it does is left for a person and listed with why.
 */
object ForFix {
    /** What one file's rewrite did: loops rewritten, and each one left alone with why. */
    data class Result(val text: String, val fixed: Int, val skipped: List<String>)

    /** What a loop head iterates: `a..b` (both ends in), `a.until(b)`, `xs.indices()`, or the elements of a value. */
    sealed class Shape {
        data class Inclusive(val begin: String, val end: String) : Shape()
        data class Until(val begin: String, val end: String) : Shape()
        data class Indices(val receiver: String) : Shape()
        data class Items(val target: String) : Shape()
    }

    /** A loop head `x: T in target`. */
    class Head(val variable: String, val type: String, val shape: Shape)

    private val HEAD = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.+?)\\s+in\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The head [text] (what stands between `for` and the body's `{`), read; null when it is not `x: T in e`. */
    fun head(text: String): Head? {
        val one = unwrap(text.trim()).trim().replace(Regex("\\s+"), " ")
        val m = HEAD.matchEntire(one) ?: return null
        return Head(m.groupValues[1], m.groupValues[2].trim(), shape(m.groupValues[3].trim()))
    }

    private fun shape(t: String): Shape {
        topLevelDots(t)?.let { return Shape.Inclusive(unwrap(t.substring(0, it).trim()), unwrap(t.substring(it + 2).trim())) }
        callOf(t, "until")?.let { (recv, arg) -> return Shape.Until(unwrap(recv), arg.trim()) }
        if (t.endsWith(".indices()")) return Shape.Indices(t.removeSuffix(".indices()"))
        return Shape.Items(t)
    }

    /** The `diagnostic` text for a `for` whose head is [head]: the `while` it becomes. */
    fun message(text: String): String {
        val base = "`for` is removed: every loop is `while <condition> { }`."
        val tail = " `kira --fix-for FILE...` rewrites a file."
        val h = head(text) ?: return "$base Declare a counter before the loop, `i: Mutable<Int32> { 0 }`, test it in the `while`, and step it last, `i += 1`.$tail"
        val v = h.variable
        val t = h.type
        return when (val s = h.shape) {
            is Shape.Inclusive -> "$base For '$v: $t in ${s.begin}..${s.end}' write '$v: Mutable<$t> { ${s.begin} }', then 'while $v <= ${paren(s.end)} { ... $v += 1 }'.$tail"
            is Shape.Until -> "$base For '$v: $t in ${s.begin}.until(${s.end})' write '$v: Mutable<$t> { ${s.begin} }', then 'while $v < ${paren(s.end)} { ... $v += 1 }'.$tail"
            is Shape.Indices -> "$base For '$v: $t in ${s.receiver}.indices()' write '$v: Mutable<$t> { 0 }', then 'while $v < ${s.receiver}.size() { ... $v += 1 }'.$tail"
            is Shape.Items -> "$base For '$v: $t in ${s.target}' write '${v}Index: Mutable<Size> { 0 }', then 'while ${v}Index < ${s.target}.size() { $v: $t { ${s.target}.get(${v}Index) } ... ${v}Index += 1 }'" +
                " (a Set goes through toArr(), a Map through entries()).$tail"
        }
    }

    // ---- the rewrite --------------------------------------------------------------------------

    /** A line inserted before raw line [line]; [order] puts a body's first line before a nested loop's counter. */
    private class Ins(val line: Int, val order: Int, val text: String)

    /** The columns [start] up to [end] (1-based, exclusive) of raw line [line] become [text]. */
    private class Span(val line: Int, val start: Int, val end: Int, val text: String)

    /** A counter this rewrite declared: its [name] and [type], and the block (offsets) whose later loops may reuse it. */
    private class Counter(val name: String, val type: String, val from: Int, val blockOpen: Int, val blockClose: Int, val region: Int)

    private val MUTATING = setOf("add", "addAll", "set", "removeAt", "clear", "put", "remove", "push", "pop", "enqueue", "dequeue", "sort", "insert", "append", "push", "pushBack", "pushFront", "popBack", "popFront")
    private val RESIZING = setOf("add", "addAll", "removeAt", "clear", "put", "remove", "push", "pop", "enqueue", "dequeue", "insert", "append", "pushBack", "pushFront", "popBack", "popFront")
    private val MAXES = mapOf(
        "UInt8" to 255L, "Int8" to 127L, "UInt16" to 65535L, "Int16" to 32767L,
        "Int32" to 2147483647L, "UInt32" to 4294967295L, "Int64" to Long.MAX_VALUE,
    )
    private val NARROW = setOf("UInt8", "Int8", "UInt16", "Int16")

    /** A loop whose iterable's type the file does not write: its `for` line, and the expression the typer is asked about. */
    class Need(val line: Int, val expr: String)

    /** A range loop: its two ends as text, and whether the end is in. */
    private class Ends(val begin: String, val end: String, val inclusive: Boolean)

    private val CHAIN = Regex("^$NAME(\\.$NAME)+$")

    /**
     * [source] without its `for`s. [typed] answers the loops whose iterable is not written in the file (by `for` line, the
     * type the typer gave it); [needs], when given, collects those loops, so a caller can ask the typer first.
     */
    fun rewrite(source: String, file: String = "fix.kira", typed: Map<Int, String>? = null, needs: MutableList<Need>? = null): Result {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val ctx = SourceContext(processed, file, tokens)
        ctx.lenientFor = true
        ctx.lenientDeclEquals = true
        KiraParser.parse(ctx)
        val records = ctx.astFors.sortedWith(compareBy({ it.forAt.lineNumber }, { it.forAt.column }))
        if (records.isEmpty()) return Result(source, 0, emptyList())
        val rawLines = source.split(Regex("(?<=\n)"))
        val procLines = processed.split("\n")
        val eol = rawLines.firstOrNull { it.endsWith("\n") }?.let { if (it.endsWith("\r\n")) "\r\n" else "\n" } ?: "\n"
        val inserts = ArrayList<Ins>()
        val spans = ArrayList<Span>()
        val skipped = ArrayList<String>()
        var fixed = 0

        // Braces and regions: a region is one top-level item (a function, a class, a constant), which holds every name a loop could clash with.
        val blocks = braceBlocks(tokens)
        val regions = regionsOf(tokens)
        val userNames = tokens.filter { it.type == Token.Type.IDENTIFIER }.map { it.content }.toSet()
        val taken = userNames.toMutableSet()
        val mine = HashSet<String>()
        val forVars = records.mapNotNull { r -> head(r.head)?.let { r to it.variable } }
        val counters = ArrayList<Counter>()

        fun fresh(base: String): String {
            var n = base
            var k = 2
            while (n in taken) n = base + k++
            taken += n
            return n
        }

        loops@ for (r in records) {
            val fl = r.forAt.lineNumber
            val fc = r.forAt.column
            val ol = r.openAt.lineNumber
            val oc = r.openAt.column
            val cl = r.closeAt.lineNumber
            val cc = r.closeAt.column
            val where = "$file:$fl"
            fun skip(why: String) { skipped += "$where: $why" }
            val pl = procLines.getOrNull(fl - 1) ?: continue
            if (pl.substring(0, fc - 1).isNotBlank()) { skip("`for` does not start its line"); continue }
            if (ol != fl) { skip("the head spans lines"); continue }
            if (procLines[ol - 1].substring(minOf(oc, procLines[ol - 1].length)).isNotBlank()) { skip("the body starts on the head's line"); continue }
            if (cl <= ol || procLines[cl - 1].substring(0, cc - 1).isNotBlank()) { skip("the body closes on a line that holds more"); continue }
            val rawFor = rawLines[fl - 1].trimEnd('\r', '\n')
            if (!rawFor.startsWith(pl.substring(0, oc))) { skip("a comment moves the head's columns"); continue }
            val h = head(r.head) ?: run { skip("the head is not `x: T in e`: `${r.head.trim()}`"); null } ?: continue
            val v = h.variable
            val bodyOffsetTo = tokens.first { it.canonicalLocation.lineNumber == cl && it.canonicalLocation.column == cc }.pointerPosition
            val forOffset = tokens.first { it.canonicalLocation.lineNumber == fl && it.canonicalLocation.column == fc }.pointerPosition
            val region = regions.indexOfFirst { forOffset >= it.first && forOffset <= it.last }
            val regionRange = regions.getOrNull(region) ?: run { skip("no enclosing item found"); null } ?: continue
            val body = r.loop.statements
            val bodyText = (ol + 1..cl - 1).joinToString("\n") { procLines[it - 1] }

            // The body: own `continue`s step the counter first (a lambda copies what it captures, as it did the loop variable).
            val continues = ownContinues(body)
            val badContinue = continues.firstOrNull { c -> ctx.astOrigins[c]?.let { procLines[it.lineNumber - 1].trim() != "continue" } != false }
            if (badContinue != null) { skip("a `continue` shares its line with other code"); continue }

            val ind = rawFor.takeWhile { it == ' ' || it == '\t' }
            val first = (ol + 1..cl - 1).map { rawLines[it - 1] }.firstOrNull { it.isNotBlank() }
            val inner = first?.takeWhile { it == ' ' || it == '\t' } ?: (ind + "    ")
            val unit = if (inner.length > ind.length && inner.startsWith(ind)) inner.substring(ind.length) else "    "
            val last = body.lastOrNull()
            val ends = last is ReturnStatement && !last.implicit || last is BreakStatement || last is ContinueStatement || (last != null && last.expr is ThrowExpr)

            val pre = ArrayList<String>()   // lines before the `for`
            val open = ArrayList<String>()  // lines at the top of the body
            var cond: String
            var step: List<String>
            var range: Ends? = null

            /** The kind of [target]'s type, with the type text when the typer gave it: from what the file writes of [name], else the typer; null with the loop left. */
            fun kindOfTarget(target: String, name: String?): Pair<String, String?>? {
                val kinds = if (name != null) kindsOf(ctx, regionRange, regions.indexOf(regionRange), name) else emptySet()
                kinds.singleOrNull()?.let { return it to null }
                val answer = typed?.get(fl)
                if (answer != null) return answer.substringBefore('<').trim() to answer
                needs?.add(Need(fl, target))
                skip(
                    when {
                        typed != null -> "the typer gave no type for `$target`"
                        name == null -> "iterates `$target`, whose kind is not written in the file"
                        kinds.isEmpty() -> "the type of `$name` is not written in the file"
                        else -> "the type of `$name` is written two ways: $kinds"
                    }
                )
                return null
            }

            when (val s = h.shape) {
                is Shape.Items -> {
                    val literal = Regex("^@(arrOf|listOf|setOf)\\(").find(s.target)?.groupValues?.get(1)
                    val name = Regex("^(this\\.)?($NAME)$").matchEntire(s.target)?.groupValues?.get(2)
                    val chain = name == null && literal == null && CHAIN.matches(s.target)
                    val kind: String
                    var written: String? = null
                    if (literal != null) {
                        kind = literal.removeSuffix("Of").replaceFirstChar { it.uppercase() }
                    } else {
                        val k = kindOfTarget(s.target, name) ?: continue@loops
                        kind = k.first
                        written = k.second
                    }
                    if (written != null && kind != "Map") {
                        val element = elementOf(written)
                        if (element != null && !sameType(element, h.type)) {
                            skip("`${s.target}` is a $written, whose elements are $element, not ${h.type}")
                            continue@loops
                        }
                    }
                    if (name != null && hasMutation(bodyText, name, MUTATING)) { skip("the body changes `$name`"); continue@loops }
                    if (chain && hasMutationOf(bodyText, s.target, MUTATING)) { skip("the body changes `${s.target}`"); continue@loops }
                    // What is walked: a name or a field chain as it stands, anything else (a call, a literal) read once into a local of its own.
                    var walked = s.target
                    if (kind == "Range") {
                        if (name == null && !chain) {
                            walked = fresh(v + "Range")
                            pre += "$walked: ${written ?: "Range<${h.type}>"} { ${s.target} }"
                        }
                        range = Ends("$walked.begin", "$walked.end", true)
                    } else {
                        if (kind in setOf("List", "Arr", "View", "MutView") && name == null && !chain) {
                            walked = fresh(v + "Items")
                            pre += "$walked: ${written ?: "$kind<${h.type}>"} { ${s.target} }"
                        }
                        // The index: a counter of this name declared by a loop before, in a block that holds this one, is set again.
                        val want = v + "Index"
                        val again = counters.any { c -> c.name == want && c.region == region && c.type == "Mutable<Size>" && forOffset > c.from && forOffset in c.blockOpen..c.blockClose }
                        val idx = if (again || (want in mine && want !in userNames)) want else fresh(want).also { mine += it }
                        val src = when (kind) {
                            "List", "Arr", "View", "MutView" -> walked
                            "Set", "Map" -> {
                                val items = fresh(v + "Items")
                                pre += "$items: Arr<${h.type}> { $walked.${if (kind == "Set") "toArr" else "entries"}() }"
                                items
                            }
                            else -> { skip("`${s.target}` is a ${written ?: kind}, not a collection"); continue@loops }
                        }
                        if (again) {
                            pre += "$idx = 0"
                        } else {
                            pre += "$idx: Mutable<Size> { 0 }"
                            val blk = blocks.filter { forOffset > it.first && forOffset < it.last }.minByOrNull { it.last - it.first }
                            counters += Counter(idx, "Mutable<Size>", forOffset, blk?.first ?: Int.MIN_VALUE, blk?.last ?: Int.MAX_VALUE, region)
                        }
                        cond = "$idx < $src.size()"
                        open += "$v: ${h.type} { $src.get($idx) }"
                        step = listOf("$idx += 1")
                        edits(inserts, spans, continues, ctx, rawLines, ol, cl, fl, fc, oc, ends, inner, ind, eol, pre, open, step, cond)
                        fixed++
                        continue@loops
                    }
                }
                is Shape.Inclusive -> range = Ends(s.begin, s.end, true)
                is Shape.Until -> range = Ends(s.begin, s.end, false)
                is Shape.Indices -> {
                    val name = Regex("^(this\\.)?($NAME)$").matchEntire(s.receiver)?.groupValues?.get(2)
                    val kind = kindOfTarget(s.receiver, name)?.first ?: continue@loops
                    range = Ends("0", if (kind == "Str") "${s.receiver}.length()" else "${s.receiver}.size()", false)
                }
            }

            val e = range ?: error("unreachable")
            val begin = e.begin
            val end = e.end
            val inclusive = e.inclusive
            // An end at the type's maximum cannot be stepped past: the loop tests before it steps.
            var safe = false
            var atMax = false
            if (inclusive) {
                val literal = end.toLongOrNull() ?: end.removePrefix("0x").toLongOrNull(16)?.takeIf { end.startsWith("0x") }
                val max = MAXES[h.type]
                if (literal != null && max != null && literal > max) { skip("the end $end is past ${h.type}'s maximum"); continue@loops }
                if (literal != null && max != null && literal == max) { safe = true; atMax = true }
                if (literal == null && h.type in NARROW) safe = true
            }
            val volatile = unstable(end) || endRoots(end).any { hasMutation(bodyText, it, RESIZING) } || (safe && !atMax && !isPlain(end))
            val limitName = if (volatile) fresh(v + "Limit") else null
            val limit = limitName ?: end
            // The counter: this loop's own variable; one a loop before declared in an enclosing block is set again.
            val own = forOffset..bodyOffsetTo
            val clash = tokens.indices.any { i ->
                val t = tokens[i]
                t.type == Token.Type.IDENTIFIER && t.content == v && t.pointerPosition in regionRange &&
                    t.pointerPosition !in own && !(i > 0 && tokens[i - 1].content == ".") &&
                    forVars.none { (rec, name) -> name == v && t.pointerPosition in offsetsOf(tokens, rec) }
            }
            if (clash) { skip("`$v` is also a name outside its loops in this item"); continue@loops }
            val earlier = counters.firstOrNull { c ->
                c.name == v && c.region == region && forOffset > c.from && forOffset in c.blockOpen..c.blockClose
            }
            val nested = records.any { o -> o !== r && head(o.head)?.variable == v && forOffset in offsetsOf(tokens, o) }
            if (nested) { skip("an enclosing loop also counts with `$v`"); continue@loops }
            if (earlier != null && earlier.type != "Mutable<${h.type}>") { skip("`$v` was declared as ${earlier.type} by a loop before"); continue@loops }
            if (earlier != null) {
                pre += "$v = $begin"
            } else {
                pre += "$v: Mutable<${h.type}> { $begin }"
                val blk = blocks.filter { forOffset > it.first && forOffset < it.last }.minByOrNull { it.last - it.first }
                counters += Counter(v, "Mutable<${h.type}>", forOffset, blk?.first ?: Int.MIN_VALUE, blk?.last ?: Int.MAX_VALUE, region)
            }
            // The start is read before the end, as the loop read them.
            if (limitName != null) pre += "$limitName: ${h.type} { $end }"
            if (safe) {
                val more = fresh(v + "More")
                pre += "$more: Mutable<Bool> { ${if (atMax) "true" else "$v <= ${paren(limit)}"} }"
                cond = more
                step = listOf("if $v == ${paren(limit)} {", "$unit$more = false", "} else {", "$unit$v += 1", "}")
            } else {
                cond = "$v ${if (inclusive) "<=" else "<"} ${paren(limit)}"
                step = listOf("$v += 1")
            }
            edits(inserts, spans, continues, ctx, rawLines, ol, cl, fl, fc, oc, ends, inner, ind, eol, pre, open, step, cond)
            fixed++
        }

        val out = StringBuilder()
        val byLine = inserts.groupBy { it.line }
        for ((i, raw) in rawLines.withIndex()) {
            val n = i + 1
            byLine[n]?.sortedBy { it.order }?.let { l -> l.forEach { out.append(it.text) } }
            var line = raw
            for (sp in spans.filter { it.line == n }.sortedByDescending { it.start }) {
                line = line.substring(0, sp.start - 1) + sp.text + line.substring(minOf(sp.end - 1, line.length))
            }
            out.append(line)
        }
        return Result(out.toString(), fixed, skipped)
    }

    /** Everything checked: the loop's edits, the step before each own `continue` and at the body's end, the lines before and inside. */
    private fun edits(
        inserts: MutableList<Ins>, spans: MutableList<Span>, continues: List<ContinueStatement>, ctx: SourceContext, rawLines: List<String>,
        ol: Int, cl: Int, fl: Int, fc: Int, oc: Int, ends: Boolean, inner: String, ind: String, eol: String,
        pre: List<String>, open: List<String>, step: List<String>, cond: String,
    ) {
        for (c in continues) {
            val at = ctx.astOrigins[c]!!
            val line = rawLines[at.lineNumber - 1].takeWhile { it == ' ' || it == '\t' }
            step.forEach { inserts += Ins(at.lineNumber, 2, line + it + eol) }
        }
        if (!ends) step.forEach { inserts += Ins(cl, 2, inner + it + eol) }
        for (l in open) inserts += Ins(ol + 1, 0, inner + l + eol)
        for (l in pre) inserts += Ins(fl, 1, ind + l + eol)
        spans += Span(fl, fc, oc, "while $cond ")
    }

    /** Whether two spellings are one type: spaces aside, and the old `Tuple2` is the `T2` it was renamed to. */
    private fun sameType(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(t: String): String = t.replace(" ", "").replace(Regex("Tuple(\\d)"), "T$1")

    /** The first type argument of [type] (`List<Str>` gives `Str`, `Arr<UInt8, 4>` gives `UInt8`), or null when it has none. */
    private fun elementOf(type: String): String? {
        val open = type.indexOf('<')
        if (open < 0 || !type.endsWith(">")) return null
        val inner = type.substring(open + 1, type.length - 1)
        var depth = 0
        for ((i, ch) in inner.withIndex()) {
            when (ch) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) return inner.substring(0, i).trim()
            }
        }
        return inner.trim()
    }

    /** Whether [end] is a name, a field chain or a number: read twice with no harm unless the body changes it. */
    private fun isPlain(end: String): Boolean = NAME.matches(end) || CHAIN.matches(end) || end.toLongOrNull() != null

    /** Whether [text] changes the field chain [chain]: assigns to it, or calls one of [methods] on it. */
    private fun hasMutationOf(text: String, chain: String, methods: Set<String>): Boolean {
        val n = Regex.escape(chain)
        val assign = Regex("(^|[^A-Za-z0-9_.])$n(\\[[^\\]]*\\])?\\s*([-+*/%&|^]|<<)?=(?!=)")
        val call = Regex("(^|[^A-Za-z0-9_.])$n\\.(${methods.joinToString("|")})\\(")
        return assign.containsMatchIn(text) || call.containsMatchIn(text)
    }

    /**
     * [source] for the typer: each `for` that can be rewritten becomes `while kprobeN(iterable) {` (N its line; `true` for a
     * loop that asks nothing) with its variable declared from the head on the next line, so a loop inside sees it.
     */
    fun probeText(source: String, file: String, needs: List<Need>): String {
        val processed = KiraPreprocessor(source).process().processedContent
        val tokens = KiraLexer(SourceContext(processed, file, emptyList())).tokenize()
        val ctx = SourceContext(processed, file, tokens)
        ctx.lenientFor = true
        ctx.lenientDeclEquals = true
        KiraParser.parse(ctx)
        val rawLines = source.split(Regex("(?<=\n)")).toMutableList()
        val procLines = processed.split("\n")
        val byLine = needs.associateBy { it.line }
        val eol = rawLines.firstOrNull { it.endsWith("\n") }?.let { if (it.endsWith("\r\n")) "\r\n" else "\n" } ?: "\n"
        val after = HashMap<Int, String>()
        for (r in ctx.astFors) {
            val fl = r.forAt.lineNumber
            val fc = r.forAt.column
            val oc = r.openAt.column
            val pl = procLines.getOrNull(fl - 1) ?: continue
            if (pl.substring(0, fc - 1).isNotBlank() || r.openAt.lineNumber != fl || r.closeAt.lineNumber <= fl) continue
            if (procLines[fl - 1].substring(minOf(oc, procLines[fl - 1].length)).isNotBlank()) continue
            val raw = rawLines[fl - 1]
            if (!raw.trimEnd('\r', '\n').startsWith(pl.substring(0, oc))) continue
            val h = head(r.head) ?: continue
            val callee = byLine[fl]?.let { "kprobe$fl(${it.expr})" } ?: "true"
            rawLines[fl - 1] = raw.substring(0, fc - 1) + "while $callee {" + raw.substring(oc)
            val ind = raw.takeWhile { it == ' ' || it == '\t' }
            after[fl] = ind + "    " + h.variable + ": " + h.type + eol
        }
        val out = StringBuilder()
        for ((i, line) in rawLines.withIndex()) {
            out.append(line)
            after[i + 1]?.let { out.append(it) }
        }
        return out.toString()
    }

    /** Rewrites [file] in place (written beside, synced, renamed over). */
    fun rewriteFile(file: File): Result {
        val r = rewrite(file.readText(), file.path)
        if (r.fixed != 0) writeFile(file, r.text)
        return r
    }

    /** [text] over [file]: written beside, synced, renamed over. */
    fun writeFile(file: File, text: String) {
        val beside = File(file.path + ".fix")
        Files.newByteChannel(beside.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            (ch as java.nio.channels.FileChannel).write(java.nio.ByteBuffer.wrap(text.toByteArray())); ch.force(true)
        }
        Files.move(beside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // ---- reading the tree ----------------------------------------------------------------------

    /** The offsets of [r]'s loop: its `for` to its closing brace. */
    private fun offsetsOf(tokens: List<Token>, r: ForLoopRecord): IntRange {
        val a = tokens.first { it.canonicalLocation.lineNumber == r.forAt.lineNumber && it.canonicalLocation.column == r.forAt.column }.pointerPosition
        val b = tokens.first { it.canonicalLocation.lineNumber == r.closeAt.lineNumber && it.canonicalLocation.column == r.closeAt.column }.pointerPosition
        return a..b
    }

    /** Every `{` ... `}` pair of the file, by offset. */
    private fun braceBlocks(tokens: List<Token>): List<IntRange> {
        val out = ArrayList<IntRange>()
        val stack = ArrayDeque<Int>()
        for (t in tokens) {
            if (t.type == Token.Type.S_OPEN_BRACE) stack.addLast(t.pointerPosition)
            else if (t.type == Token.Type.S_CLOSE_BRACE && stack.isNotEmpty()) out += stack.removeLast()..t.pointerPosition
        }
        return out
    }

    /** The top-level items by offset: from the token after the last top-level `}` up to the next one. */
    private fun regionsOf(tokens: List<Token>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var depth = 0
        var start = 0
        for (t in tokens) {
            if (t.type == Token.Type.S_OPEN_BRACE) depth++
            else if (t.type == Token.Type.S_CLOSE_BRACE && --depth == 0) {
                out += start..t.pointerPosition
                start = t.pointerPosition + 1
            }
        }
        out += start..Int.MAX_VALUE
        return out
    }

    /** The statements of [body] that `continue` this loop: not those of a nested loop or a lambda. */
    private fun ownContinues(body: List<Statement>): List<ContinueStatement> {
        val out = ArrayList<ContinueStatement>()
        fun visit(n: ASTNode) {
            if (n is WhileIterationStatement || n is LambdaExpr) return
            if (n is ContinueStatement) out += n
            AstTree.children(n).forEach { visit(it) }
        }
        body.forEach { visit(it) }
        return out
    }

    /** The kinds (`List`, `Str`, ...) the file writes for [name] in item [index] and at top level: parameters and declarations. */
    private fun kindsOf(ctx: SourceContext, region: IntRange, index: Int, name: String): Set<String> {
        val kinds = HashSet<String>()
        fun note(n: String, type: String) {
            if (n != name) return
            var t = type.removePrefix("mut ").trim()
            while (t.startsWith("Mutable<") && t.endsWith(">")) t = t.removePrefix("Mutable<").removeSuffix(">").trim()
            kinds += t.substringBefore('<').trim()
        }
        val tokens = ctx.tokens
        for (st in ctx.ast.statements) {
            val at = ctx.astOrigins[st] ?: continue
            val offset = tokens.firstOrNull { it.canonicalLocation.lineNumber == at.lineNumber && it.canonicalLocation.column == at.column }?.pointerPosition ?: continue
            val inRegion = offset in region
            AstTree.walk(st) { n ->
                if (n is VariableDecl && (inRegion || (st as? Statement)?.expr === n)) note(n.name.value, KiraUnparser.type(n.type))
                if (n is FunctionDeclParameterExpr && inRegion) note(n.name.value, KiraUnparser.type(n.typeSpecifier))
            }
        }
        // A loop's own variable is written in its head: `row: List<Int32> in grid`.
        for (r in ctx.astFors) {
            val offset = tokens.firstOrNull { it.canonicalLocation.lineNumber == r.forAt.lineNumber && it.canonicalLocation.column == r.forAt.column }?.pointerPosition ?: continue
            if (offset in region) head(r.head)?.let { note(it.variable, it.type) }
        }
        return kinds
    }

    // ---- reading the text ----------------------------------------------------------------------

    /** The roots (`xs` in `xs.size()`) of the names in a bound. */
    private fun endRoots(end: String): List<String> =
        NAME.findAll(end).filter { m -> m.range.first == 0 || end[m.range.first - 1] != '.' }.map { it.value }.toList()

    /** Whether [text] changes [name]: assigns to it, or calls one of [methods] on it. */
    private fun hasMutation(text: String, name: String, methods: Set<String>): Boolean {
        val n = Regex.escape(name)
        val assign = Regex("(^|[^A-Za-z0-9_.])(this\\.)?$n(\\[[^\\]]*\\])?\\s*([-+*/%&|^]|<<)?=(?!=)")
        val call = Regex("(^|[^A-Za-z0-9_.])(this\\.)?$n\\.(${methods.joinToString("|")})\\(")
        return assign.containsMatchIn(text) || call.containsMatchIn(text)
    }

    /** Whether a loop bound runs code each time it is read: a call that is not `size()` or `length()`. */
    private fun unstable(end: String): Boolean {
        val plain = end.replace(".size()", "").replace(".length()", "")
        return Regex("[A-Za-z0-9_\\]]\\s*\\(").containsMatchIn(plain) || plain.contains('@')
    }

    /** [s] in parentheses unless it already reads as one operand. */
    private fun paren(s: String): String {
        var depth = 0
        for (ch in s) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '?', '&', '|', '=', '<', '>', '!' -> if (depth == 0) return "($s)"
            }
        }
        return s
    }

    private fun skipQuoted(s: String, open: Int): Int {
        val q = s[open]
        var i = open + 1
        while (i < s.length) {
            if (s[i] == '\\') i++ else if (s[i] == q) return i
            i++
        }
        return s.length
    }

    /** The index of the `..` outside brackets and strings, or null. */
    private fun topLevelDots(s: String): Int? {
        var depth = 0
        var i = 0
        while (i < s.length - 1) {
            when (val ch = s[i]) {
                '"', '\'' -> i = skipQuoted(s, i)
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '.' -> if (depth == 0 && s[i + 1] == '.') return i
            }
            i++
        }
        return null
    }

    /** `recv.name(arg)` where the call is the whole of [s]: the receiver and the argument text, or null. */
    private fun callOf(s: String, name: String): Pair<String, String>? {
        if (!s.endsWith(")")) return null
        var depth = 0
        var i = s.length - 1
        while (i >= 0) {
            if (s[i] == ')') depth++ else if (s[i] == '(') { depth--; if (depth == 0) break }
            i--
        }
        if (i < 0) return null
        val before = s.substring(0, i)
        if (!before.endsWith(".$name")) return null
        return before.removeSuffix(".$name") to s.substring(i + 1, s.length - 1)
    }

    /** [s] without one pair of parentheses that holds all of it: `(n + 1)` gives `n + 1`. */
    private fun unwrap(s: String): String {
        if (!s.startsWith("(") || !s.endsWith(")")) return s
        var depth = 0
        for ((i, ch) in s.withIndex()) {
            if (ch == '(') depth++ else if (ch == ')') depth--
            if (depth == 0 && i < s.length - 1) return s
        }
        return s.substring(1, s.length - 1)
    }
}
