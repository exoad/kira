package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.types.TyperTestSupport
import java.io.File

/**
 * The fixture format of the phase-C tests (src/test/resources/typer/body).
 *
 * A fixture is a `.kira` file, or a directory of them typed together. A line may end in a
 * comment holding clauses separated by `;`:
 *
 * - `@type T [key=value ...] [@ expr]`: the expression on this line (the outermost one, or the
 *   one whose text is `expr`, as TypedModelDumper prints it) has type `T`, and its dump line
 *   carries every `key=value` fact given (`call=`, `member=`, `coerce=`, `conv=`, `const=`,
 *   `place=`, `sym=`);
 * - `@error code` / `@warn code`: a diagnostic with that code and severity is on this line.
 *
 * A positive fixture has no diagnostic that is not annotated; so does a negative one, whose
 * point is its annotated errors.
 */
object BodyFixtures {
    data class TypeClause(val line: Int, val type: String, val facts: List<String>, val selector: String?, val raw: String)

    data class DiagClause(val line: Int, val code: String, val severity: Severity)

    data class Parsed(val types: List<TypeClause>, val diags: List<DiagClause>)

    fun sources(fixture: File): List<File> =
        if (fixture.isDirectory) fixture.walkTopDown().filter { it.isFile && it.extension == "kira" }.sortedBy { it.path }.toList() else listOf(fixture)

    fun type(fixture: File): TypedProgram {
        val srcs = sources(fixture).map { TyperTestSupport.Src(it.canonicalPath, it.readText()) }
        return TyperTestSupport.type(*srcs.toTypedArray())
    }

    fun parse(text: String): Parsed {
        val types = mutableListOf<TypeClause>()
        val diags = mutableListOf<DiagClause>()
        text.lines().forEachIndexed { i, line ->
            val at = line.indexOf("// @")
            if (at < 0) {
                return@forEachIndexed
            }
            val comment = line.substring(at + 3)
            for (clause in comment.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    clause.startsWith("@type ") -> types.add(typeClause(i + 1, clause))
                    clause.startsWith("@error ") -> diags.add(DiagClause(i + 1, clause.removePrefix("@error ").trim(), Severity.ERROR))
                    clause.startsWith("@warn ") -> diags.add(DiagClause(i + 1, clause.removePrefix("@warn ").trim(), Severity.WARNING))
                    else -> throw AssertionError("line ${i + 1}: unknown clause '$clause'")
                }
            }
        }
        return Parsed(types, diags)
    }

    /** `@type T [facts] [@ selector]`: T balances <>, each fact balances (), the selector is the rest. */
    private fun typeClause(line: Int, clause: String): TypeClause {
        var rest = clause.removePrefix("@type ").trim()
        var selector: String? = null
        val sel = rest.indexOf(" @ ")
        if (sel >= 0) {
            selector = rest.substring(sel + 3).trim()
            rest = rest.substring(0, sel).trim()
        }
        val (type, afterType) = balanced(rest, '<', '>')
        val facts = mutableListOf<String>()
        var tail = afterType.trim()
        while (tail.isNotEmpty()) {
            val (fact, after) = balanced(tail, '(', ')')
            facts.add(fact)
            tail = after.trim()
        }
        return TypeClause(line, type, facts, selector, clause)
    }

    /** The first space-separated token of [s] whose [open]/[close] pairs balance, and the rest. */
    private fun balanced(s: String, open: Char, close: Char): Pair<String, String> {
        var depth = 0
        for ((i, ch) in s.withIndex()) {
            when {
                ch == open -> depth++
                ch == close -> depth--
                ch == ' ' && depth == 0 -> return s.substring(0, i) to s.substring(i + 1)
            }
        }
        return s to ""
    }

    data class DumpLine(val line: Int, val col: Int, val text: String, val type: String, val facts: List<String>)

    fun dumpLines(program: TypedProgram, file: File): List<DumpLine> {
        val source = program.workspaceModules.firstOrNull { File(it.source.file).canonicalPath == file.canonicalPath }?.source
            ?: throw AssertionError("no module for ${file.path}")
        return TypedModelDumper.dump(program, source).lines().filter { it.isNotBlank() }.map { l ->
            val cols = l.split("  ")
            val pos = cols[0].substringAfter(':').split(':')
            DumpLine(pos[0].toInt(), pos[1].toInt(), cols[1], cols[2], cols.drop(3))
        }
    }

    /** Checks every @type clause of [file]; returns the failures (empty when all hold) and the count checked. */
    fun checkTypes(program: TypedProgram, file: File, parsed: Parsed): Pair<List<String>, Int> {
        val dump = dumpLines(program, file)
        val failures = mutableListOf<String>()
        for (c in parsed.types) {
            val onLine = dump.filter { it.line == c.line }
            val hit = if (c.selector != null) {
                onLine.filter { it.text == c.selector }.minByOrNull { it.col }
            } else {
                onLine.maxWithOrNull(compareBy<DumpLine> { it.text.length }.thenByDescending { it.col })
            }
            if (hit == null) {
                failures.add("${file.name}:${c.line}: no dumped expression${c.selector?.let { " '$it'" } ?: ""} for `${c.raw}`; the line has:\n  " +
                    onLine.joinToString("\n  ") { "${it.text}  ${it.type}  ${it.facts.joinToString("  ")}" })
                continue
            }
            if (hit.type != c.type) {
                failures.add("${file.name}:${c.line}: '${hit.text}' is ${hit.type}, expected ${c.type}  (facts: ${hit.facts.joinToString("  ")})")
            }
            val missing = c.facts.filter { it !in hit.facts }
            if (missing.isNotEmpty()) {
                failures.add("${file.name}:${c.line}: '${hit.text}' lacks ${missing.joinToString(", ")}; it has ${hit.facts.joinToString("  ")}")
            }
        }
        return failures to parsed.types.size
    }

    /**
     * Every annotated diagnostic appears on its line, and nothing else is reported in the
     * fixture's files. Each clause is spent by exactly one diagnostic, so a line annotated
     * `@error x ; @error x` expects two, and a third is unexpected.
     */
    fun checkDiagnostics(program: TypedProgram, files: List<File>, parsed: Map<File, Parsed>): List<String> {
        val failures = mutableListOf<String>()
        val byFile = files.associateBy { it.canonicalPath }
        val unspent = parsed.mapValues { (_, p) -> p.diags.toMutableList() }
        for (d in program.diagnostics) {
            val path = d.source?.file?.let { File(it).canonicalPath }
            val file = path?.let { byFile[it] }
            if (file == null) {
                if (d.severity == Severity.ERROR || d.severity == Severity.WARNING) {
                    failures.add("a diagnostic outside the fixture: ${d.render()}")
                }
                continue
            }
            val line = d.position?.lineNumber ?: -1
            val clauses = unspent[file]!!
            val at = clauses.indexOfFirst { it.line == line && it.code == d.code && it.severity == d.severity }
            if (at < 0) {
                failures.add("unexpected: ${d.render()}")
            } else {
                clauses.removeAt(at)
            }
        }
        for ((file, clauses) in unspent) {
            for (clause in clauses) {
                failures.add("${file.name}:${clause.line}: expected ${clause.severity.name.lowercase()} ${clause.code}, which was not reported")
            }
        }
        return failures
    }
}
