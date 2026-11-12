package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.rules.FoldJoin
import net.exoad.kira.compiler.analysis.types.rules.FoldRun
import net.exoad.kira.compiler.analysis.types.rules.Folds
import net.exoad.kira.compiler.analysis.types.rules.WithFold
import java.io.File

/**
 * `kira --fix-with FILE...`: each run of statements in a row that act on one Mutable value, a class of the project or a
 * stdlib List, Map, Set, Json and the like (`x.f()`, `x.field = v`, what the `rules.with.fold` hint names), becomes
 * `x with { ... }`, the receiver's prefix dropped from each statement and the statements indented a step. With
 * `--at-declaration`, a run right under the one-value declaration of `x` goes onto it as `x: T { v } with { ... }`, and so
 * does a `x with { }` block already there. Only in a project whose target lowers a `with` (py, cpp). Lines keep their
 * comments, their line ends and their tabs; a second run finds nothing, as a `with` body is never folded again.
 */
object WithFix {
    /** The targets that lower a `with`: py, and cpp for a Mutable value (edited in place). */
    private val LOWERS_WITH = setOf("py", "cpp")

    /**
     * What one file's fold came to: its new text, the runs folded ([stdlib] of them on a stdlib value, [declared] onto a
     * declaration), the blocks [joined] to their declaration, and the runs [left] with why; or why the file was refused.
     */
    class Outcome(
        val text: String?,
        val folded: Int,
        val refused: String?,
        val stdlib: Int = 0,
        val declared: Int = 0,
        val joined: Int = 0,
        val statements: Int = 0,
        val left: List<Left> = emptyList(),
        /** The runs folded, counted by the receiver's type name. */
        val types: Map<String, Int> = emptyMap(),
    )

    /** Statements in a row on one value that stay: [statements] of them on [receiver] from [line] on, and [why]. */
    class Left(val line: Int, val receiver: String, val statements: Int, val why: String)

    private class Folded(val text: String, val runs: Int, val stdlib: Int, val declared: Int, val joined: Int, val statements: Int, val types: Map<String, Int>)

    /** [source] with each of [runs] folded; a run whose lines do not read as it was found (a comment moved its columns) is left. */
    internal fun fold(source: String, runs: List<FoldRun>): Pair<String, Int> = fold(source, runs, emptyList(), false).let { it.text to it.runs }

    /** [source] with [runs] folded and, when [atDeclaration], each run and each of [joins] put onto the declaration above it. */
    private fun fold(source: String, runs: List<FoldRun>, joins: List<FoldJoin>, atDeclaration: Boolean): Folded {
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        val eol = lines.firstOrNull { it.endsWith("\n") }?.let { if (it.endsWith("\r\n")) "\r\n" else "\n" } ?: "\n"
        var folded = 0
        var stdlib = 0
        var declared = 0
        var joined = 0
        var statements = 0
        val types = java.util.TreeMap<String, Int>()

        /** ` with {` put at the end of line [ln], the declaration's closing brace. */
        fun open(ln: Int) {
            val raw = lines[ln - 1]
            lines[ln - 1] = raw.trimEnd() + " with {" + eol
        }
        val edits: List<Pair<Int, Any>> = runs.map { it.firstLine to it } + (if (atDeclaration) joins.map { it.withLine to it } else emptyList())
        for ((_, edit) in edits.sortedByDescending { it.first }) {
            if (edit is FoldJoin) {
                val head = lines.getOrNull(edit.withLine - 1) ?: continue
                val above = lines.getOrNull(edit.declLine - 1) ?: continue
                if (head.trim() != "${edit.receiver} with {" || !above.trimEnd().endsWith("}")) continue
                lines.removeAt(edit.withLine - 1)
                open(edit.declLine)
                joined++
                continue
            }
            val run = edit as FoldRun
            val first = lines.getOrNull(run.firstLine - 1) ?: continue
            val ind = first.takeWhile { it == ' ' || it == '\t' }
            val prefix = ind + run.receiver + "."
            if (run.startLines.any { lines.getOrNull(it - 1)?.startsWith(prefix) != true }) continue
            val onto = run.declLine?.takeIf { atDeclaration && lines.getOrNull(it - 1)?.trimEnd()?.endsWith("}") == true }
            val unit = if ('\t' in ind) "\t" else "    "
            for (ln in run.firstLine..run.lastLine) {
                val raw = lines[ln - 1]
                if (raw.isBlank()) continue
                lines[ln - 1] = if (ln in run.startLines) ind + unit + raw.substring(prefix.length) else unit + raw
            }
            val tail = lines[run.lastLine - 1]
            if (tail.endsWith("\n")) {
                lines.add(run.lastLine, ind + "}" + eol)
            } else {
                lines[run.lastLine - 1] = tail + eol
                lines.add(run.lastLine, ind + "}")
            }
            if (onto != null) {
                open(onto)
                declared++
            } else {
                lines.add(run.firstLine - 1, ind + run.receiver + " with {" + eol)
            }
            folded++
            statements += run.statements
            if (run.stdlib) stdlib++
            types.merge(run.type, 1, Int::plus)
        }
        return Folded(lines.joinToString(""), folded, stdlib, declared, joined, statements, types)
    }

    /** [files] folded (not written), each by the project above it; a file of a project that does not lower `with` is refused. */
    fun foldFiles(files: List<File>, atDeclaration: Boolean = false): Map<File, Outcome> {
        val out = LinkedHashMap<File, Outcome>()
        for ((dir, group) in files.groupBy { TypedFix.projectDirOf(it) }) {
            if (dir == null) {
                group.forEach { out[it] = Outcome(null, 0, "no project.kira above it, so no project to type it in") }
                continue
            }
            try {
                out += TypedFix.withProject(dir) { project, srcs ->
                    if (project.target.lowercase() !in LOWERS_WITH) {
                        group.associateWith { Outcome(null, 0, "the project's target is ${project.target}, which does not lower a `with` (py and cpp do)") }
                    } else {
                        val keys = group.map { it.canonicalPath }.toSet()
                        val (_, program) = TypedFix.typeSources(srcs, options = TyperOptions(withFold = true))
                        val modules = program.workspaceModules.filter { it.source.file in keys }.toSet()
                        val found = WithFold.find(program, modules)
                        group.associateWith { f -> outcome(f.readText(), found, f.canonicalPath, atDeclaration) }
                    }
                }
            } catch (e: Throwable) {
                group.forEach { out[it] = Outcome(null, 0, "the project at $dir did not load: ${e.message?.lineSequence()?.firstOrNull()}") }
            }
        }
        return files.associateWith { out.getValue(it) }
    }

    private fun outcome(text: String, found: Folds, file: String, atDeclaration: Boolean): Outcome {
        val r = fold(text, found.runs.filter { it.module.source.file == file }, found.joins.filter { it.module.source.file == file }, atDeclaration)
        val left = found.left.filter { it.module.source.file == file }.map { Left(it.line, it.receiver, it.statements, it.why) }
        return Outcome(r.text, r.runs, null, r.stdlib, r.declared, r.joined, r.statements, left, r.types)
    }

    /** One module's [text] folded, against the stdlib under `./kira`: for tests. */
    fun foldSnippet(text: String, uri: String = "test:main"): Pair<String, Int> = snippet(text, uri, false).let { it.text!! to it.folded }

    /** One module's [text] folded as [foldFiles] would, against the stdlib under `./kira`: for tests. */
    fun snippet(text: String, uri: String = "test:main", atDeclaration: Boolean = false): Outcome {
        val (pkg, dotted) = uri.split(":", limit = 2)
        val path = "$pkg/${dotted.replace('.', '/')}.kira"
        val (_, program) = TypedFix.typeSources(listOf(TypedFix.Src(path, text, uri, true)), bootstrap = true, options = TyperOptions(withFold = true))
        return outcome(text, WithFold.find(program, program.workspaceModules.toSet()), path, atDeclaration)
    }
}
