package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.rules.FoldRun
import net.exoad.kira.compiler.analysis.types.rules.WithFold
import java.io.File

/**
 * `kira --fix-with FILE...`: each run of statements in a row that act on one Mutable class value (`x.f()`, `x.field = v`,
 * what the `rules.with.fold` hint names) becomes `x with { ... }`, the receiver's prefix dropped from each statement and the
 * statements indented a step. Only in a project whose target lowers a `with` (py, cpp). Lines keep their comments, their line
 * ends and their tabs; a second run finds nothing, as a `with` body is never folded again.
 */
object WithFix {
    /** The targets that lower a `with`: py, and cpp for a Mutable class (edited in place). */
    private val LOWERS_WITH = setOf("py", "cpp")

    /** What one file's fold came to: its new text and the runs folded, or why the file was refused. */
    class Outcome(val text: String?, val folded: Int, val refused: String?)

    /** [source] with each of [runs] folded; a run whose lines do not read as it was found (a comment moved its columns) is left. */
    internal fun fold(source: String, runs: List<FoldRun>): Pair<String, Int> {
        val lines = source.split(Regex("(?<=\n)")).toMutableList()
        val eol = lines.firstOrNull { it.endsWith("\n") }?.let { if (it.endsWith("\r\n")) "\r\n" else "\n" } ?: "\n"
        var folded = 0
        for (run in runs.sortedByDescending { it.firstLine }) {
            val first = lines.getOrNull(run.firstLine - 1) ?: continue
            val ind = first.takeWhile { it == ' ' || it == '\t' }
            val prefix = ind + run.receiver + "."
            if (run.startLines.any { lines.getOrNull(it - 1)?.startsWith(prefix) != true }) continue
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
            lines.add(run.firstLine - 1, ind + run.receiver + " with {" + eol)
            folded++
        }
        return lines.joinToString("") to folded
    }

    /** [files] folded (not written), each by the project above it; a file of a project that does not lower `with` is refused. */
    fun foldFiles(files: List<File>): Map<File, Outcome> {
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
                        val runs = WithFold.runs(program, modules).groupBy { it.module.source.file }
                        group.associateWith { f ->
                            val (text, n) = fold(f.readText(), runs[f.canonicalPath].orEmpty())
                            Outcome(text, n, null)
                        }
                    }
                }
            } catch (e: Throwable) {
                group.forEach { out[it] = Outcome(null, 0, "the project at $dir did not load: ${e.message?.lineSequence()?.firstOrNull()}") }
            }
        }
        return files.associateWith { out.getValue(it) }
    }

    /** One module's [text] folded, against the stdlib under `./kira`: for tests. */
    fun foldSnippet(text: String, uri: String = "test:main"): Pair<String, Int> {
        val (pkg, dotted) = uri.split(":", limit = 2)
        val path = "$pkg/${dotted.replace('.', '/')}.kira"
        val (_, program) = TypedFix.typeSources(listOf(TypedFix.Src(path, text, uri, true)), bootstrap = true, options = TyperOptions(withFold = true))
        return fold(text, WithFold.runs(program, program.workspaceModules.toSet()).filter { it.module.source.file == path })
    }
}
