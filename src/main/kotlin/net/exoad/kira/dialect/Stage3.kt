package net.exoad.kira.dialect

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.source.SourceContext
import java.io.File

/**
 * `kira-dialect stage3 [--check] PATH...`: stage 2's text to stage 3's, `is` as `==` and the returns by [ReturnRewrite],
 * proved by the tree ([ReturnProof]) and by the types (its group's errors kept); what is left is listed (HAND).
 */
object Stage3 {
    /** The totals of one run, by rewrite. */
    val totals = LinkedHashMap<String, Int>()

    /** Read as text from before stage 5 is: an import's pub names bare, as `use` had them. */
    fun convertAll(paths: List<String>, check: Boolean): Int = DialectGates.aliasOnlyImports(false) { convertAllAsWritten(paths, check) }

    private fun convertAllAsWritten(paths: List<String>, check: Boolean): Int {
        val files = paths.flatMap { p -> File(p).walkTopDown().filter { it.isFile && it.extension == "kira" }.toList() }.distinct().sorted()
        var changed = 0
        var proved = 0
        var bad = 0
        var full = 0
        var handFiles = 0
        var hands = 0
        totals.clear()
        for ((group, members) in files.groupBy { DialectMain.newUriOf(it).second }) {
            val outs = LinkedHashMap<File, String>()
            for (f in members) {
                val r = convertText(f.readText(), f.path, DialectMain.newUriOf(f).first)
                r.notes.forEach { println(it) }
                if (r.problem != null) {
                    bad++
                    println("NOT-PROVED $f: ${r.problem}")
                    continue
                }
                proved++
                if (r.hand.isNotEmpty()) {
                    handFiles++
                    hands += r.hand.size
                    r.hand.forEach { (line, why) -> println("HAND $f:$line: $why") }
                }
                if (r.text != null) outs[f] = r.text
                if (r.full) full++
            }
            if (outs.isEmpty()) continue
            // The group typed again with the new texts: each file holds the errors it did, no more, no fewer.
            typed(File(group), members.map { it.path }, outs).forEach { (f, why) ->
                bad++
                proved--
                outs.remove(f)
                println("NOT-PROVED $f: type: $why")
            }
            for ((f, next) in outs) {
                changed++
                println("${if (check) "WOULD-CHANGE" else "CHANGED"} $f")
                if (!check) f.writeText(next)
            }
        }
        println("kira-dialect stage3: ${files.size} files, $proved proved equal, $changed ${if (check) "would change" else "changed"}, $full in stage 3's dialect, $hands hand fixes in $handFiles files, $bad not converted")
        if (totals.isNotEmpty()) println("kira-dialect stage3 rewrites: " + totals.entries.joinToString(", ") { "${it.value} ${it.key}" })
        return if (bad > 0 || hands > 0 || (check && changed > 0)) 1 else 0
    }

    /** One text's conversion: the new text (null when unchanged), what is left for a hand fix, whether it is all stage 3, or why it is not converted. */
    class Result(val text: String?, val hand: List<Pair<Int, String>>, val full: Boolean, val problem: String?, val notes: List<String>, val stats: Map<String, Int>)

    /** [text] (module [uri]) from stage 2's dialect to stage 3's, proved; [rewritesIs]: its `is` as `==` too. */
    fun convertText(text: String, path: String, uri: String?, rewritesIs: Boolean = true): Result {
        val notes = mutableListOf<String>()
        val (old, error) = ProjectUnits.read(CompilationUnit(bootstrap = false), path, text, uri, literals = true)
        if (old == null) return Result(null, emptyList(), false, "the stage 2 text does not parse: $error", notes, emptyMap())
        val (afterIs, isProblem) = if (rewritesIs) rewriteIs(text, old, path, uri) else text to null
        if (isProblem != null) return Result(null, emptyList(), false, isProblem, notes, emptyMap())
        val base = if (afterIs == text) old else ProjectUnits.read(CompilationUnit(bootstrap = false), path, afterIs, uri, literals = true).first
            ?: return Result(null, emptyList(), false, "the text after `is` does not parse", notes, emptyMap())
        val rw = ReturnRewrite(afterIs, base)
        rw.collect()
        rw.fileProblem?.let { return Result(null, emptyList(), false, it, notes, emptyMap()) }
        val refused = mutableListOf<Pair<Int, String>>()
        var next: String
        // Each round leaves out the function the proof refused, until the rest proves.
        while (true) {
            next = try {
                rw.apply()
            } catch (e: IllegalStateException) {
                rw.excluded += rw.touched()
                refused += 0 to "the rewrites overlap (${e.message}): left as written"
                continue
            }
            if (rw.touched().all { it in rw.excluded }) break
            val (nc, nerr) = ProjectUnits.read(CompilationUnit(bootstrap = false), path, next, uri, literals = false, returns = true)
            val why: String
            val unit: ReturnRewrite.Unit?
            if (nc == null) {
                why = "the new text does not parse: $nerr"
                val newLine = Regex("""at (\d+):\d+""").findAll(nerr ?: "").lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                val oldLine = newLine?.let { rw.lineOrigins.getOrNull(it - 1) }
                unit = oldLine?.let { rw.topUnitAtLine(it) }
            } else {
                val proof = ReturnProof(base, nc, rw)
                val diff = proof.compare() ?: break
                why = "tree: $diff"
                unit = proof.failedIn?.let { rw.topUnitOf(it) }
            }
            if (unit == null || unit.id in rw.excluded || unit.id !in rw.touched()) {
                rw.excluded += rw.touched()
                refused += 0 to "$why: the file left as written"
            } else {
                rw.excluded += unit.id
                refused += unit.line to "$why: the function left as written"
            }
        }
        val hand = (rw.hand.filter { (line, _) -> rw.topUnitAtLine(line)?.id !in rw.excluded } + refused).sortedBy { it.first }
        for ((k, v) in rw.stats) totals[k] = (totals[k] ?: 0) + v
        val changedText = if (next != text) next else null
        // All of stage 3's dialect: it parses with `return` refused and the ternary rule on.
        val (ok, err) = DialectGates.stage3(true) {
            ProjectUnits.read(CompilationUnit(bootstrap = false), path, next, uri, literals = false, returns = false)
        }
        if (ok == null && hand.isEmpty()) notes += "NOTE $path: not yet stage 3's dialect: $err"
        return Result(changedText, hand, ok != null, null, notes, rw.stats)
    }

    /** `is` was a keyword up to stage 2, so each `is` token of its text is the operator: `==`, proved by the tree. */
    private fun rewriteIs(text: String, old: SourceContext, path: String, uri: String?): Pair<String, String?> {
        val lineStarts = DialectConverter.lineStartsOf(text)
        val at = old.tokens.filter { it.type == Token.Type.IDENTIFIER && it.content == "is" }.map {
            lineStarts[it.canonicalLocation.lineNumber - 1] + it.canonicalLocation.column - 1
        }
        if (at.isEmpty()) return text to null
        val out = StringBuilder(text)
        at.sortedDescending().forEach { out.replace(it, it + 2, "==") }
        val next = out.toString()
        val (nc, newError) = ProjectUnits.read(CompilationUnit(bootstrap = false), path, next, uri, literals = false, returns = true)
        val diff = if (nc == null) "the new text does not parse: $newError" else Stage2.Proof(old, nc, emptyMap()).compare()
        return next to diff
    }

    /**
     * The files of [outs] whose new text, typed in its group (its project, the stdlib, or its
     * folder; [extra] its files outside the project), holds other errors than the old text did, each with why.
     */
    private fun typed(group: File, extra: List<String>, outs: Map<File, String>): List<Pair<File, String>> {
        val old = ProjectUnits.load(group, literals = true, extra = extra)
        val p1 = KiraTyper.run(old.unit, TyperMode.STRICT)
        val texts = outs.entries.associate { (f, t) -> ProjectUnits.canonical(f.path) to t }
        val again = ProjectUnits.load(group, literals = true, texts, extra)
        val p2 = KiraTyper.run(again.unit, TyperMode.STRICT)
        val bad = mutableListOf<Pair<File, String>>()
        for (f in outs.keys) {
            val path = ProjectUnits.canonical(f.path)
            if (again.sources[path] == null) {
                bad += f to "the new text does not parse in its group: ${again.failures[path]}"
                continue
            }
            // The same errors, by code and count: a test's wrong program stays wrong the same way.
            val before = errors(p1, path)
            val after = errors(p2, path)
            if (before != after) {
                val codes = (before.keys + after.keys).filter { before[it] != after[it] }
                val first = p2.diagnostics.firstOrNull { it.isError && it.code in codes && it.source?.file?.let(ProjectUnits::canonical) == path }
                bad += f to codes.joinToString { "$it ${before[it] ?: 0} became ${after[it] ?: 0}" } + (first?.let { " (${it.render()})" } ?: "")
            }
        }
        return bad
    }

    private fun errors(p: TypedProgram, path: String): Map<String, Int> =
        p.diagnostics.filter { it.isError && it.source?.file?.let(ProjectUnits::canonical) == path }.groupingBy { it.code }.eachCount()
}
