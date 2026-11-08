package net.exoad.kira.compiler.fix

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.ProjectFile
import net.exoad.kira.compiler.ProjectSources
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.containsError
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import java.io.File
import java.nio.file.Path

/**
 * The fixers that ask the typer: `kira --fix-for --typed` puts the loops [ForFix] cannot read the iterable of (a call, a field
 * chain, a name the file does not write one type for) to it, and `kira --fix-with` ([WithFix]) asks which receivers a `with`
 * can hold. The project above each file is typed (leniently) with the stdlib it names.
 *
 * For a loop's iterable the project is typed with each such loop head turned into a call of `kprobeN(iterable)`, N its line,
 * and each loop's variable declared from its head, so a loop inside a loop sees it; the call's argument has the type.
 */
object TypedFix {
    /** A source the typer sees: [file] (its key), its [text], its [module] name, and whether it is the project's own. */
    class Src(val file: String, val text: String, val module: String?, val workspace: Boolean)

    /** What the typed rewrite of one file came to: its result, or why the file was refused. */
    class Outcome(val result: ForFix.Result?, val refused: String?)

    private const val PROBE = "kprobe"

    /**
     * [srcs] parsed (a workspace file leniently: `for` and the old declaration form read), a file in [texts] by that text, and
     * typed. A file that does not parse is left out; a module that imports it types without it.
     */
    fun typeSources(srcs: List<Src>, texts: Map<String, String> = emptyMap(), bootstrap: Boolean = false, options: TyperOptions = TyperOptions()): Pair<CompilationUnit, TypedProgram> {
        val unit = CompilationUnit(bootstrap)
        for (s in srcs) {
            try {
                val processed = KiraPreprocessor(texts[s.file] ?: s.text).process().processedContent
                val bare = unit.addSource(s.file, processed, emptyList(), s.module)
                val tokens = KiraLexer(bare).tokenize()
                val ctx = unit.addSource(s.file, bare.content, tokens)
                if (s.workspace) {
                    ctx.lenientFor = true
                    ctx.lenientDeclEquals = true
                }
                KiraParser.parse(ctx)
            } catch (e: Throwable) {
                unit.removeSource(s.file)
            }
        }
        return unit to KiraTyper.run(unit, TyperMode.LENIENT, options)
    }

    /** The typer's answers for [needs] (by file, then `for` line): the iterable's type as written, `Mutable<>` taken off. */
    fun answers(srcs: List<Src>, needs: Map<String, List<ForFix.Need>>, bootstrap: Boolean = false): Map<String, Map<Int, String>> {
        val texts = srcs.filter { it.file in needs }.associate { it.file to ForFix.probeText(it.text, it.file, needs.getValue(it.file)) }
        val (unit, program) = typeSources(srcs, texts, bootstrap)
        val out = HashMap<String, MutableMap<Int, String>>()
        for (file in needs.keys) {
            val ctx = unit.getSource(file) ?: continue
            AstTree.walk(ctx.ast) { n ->
                val id = (n as? FunctionCallExpr)?.name as? Identifier
                val line = id?.value?.takeIf { it.startsWith(PROBE) }?.removePrefix(PROBE)?.toIntOrNull()
                val arg = (n as? FunctionCallExpr)?.positionalParameters?.firstOrNull()?.value
                val t = arg?.let { program.model.types[it] }
                if (line != null && t != null && !t.containsError()) {
                    var text = t.display()
                    while (text.startsWith("Mutable<") && text.endsWith(">")) text = text.removePrefix("Mutable<").removeSuffix(">").trim()
                    out.getOrPut(file) { HashMap() }[line] = text
                }
            }
        }
        return out
    }

    /** [files] rewritten (not written to disk) with the typer's help, each as its own project's file: the project.kira above it. */
    fun rewrite(files: List<File>): Map<File, Outcome> {
        val outcomes = LinkedHashMap<File, Outcome>()
        for ((dir, group) in files.groupBy { projectDirOf(it) }) {
            if (dir == null) {
                group.forEach { outcomes[it] = Outcome(null, "no project.kira above it, so no stdlib to type against") }
                continue
            }
            try {
                outcomes += withProject(dir) { _, srcs -> rewriteSources(srcs, group) }
            } catch (e: Throwable) {
                group.forEach { outcomes[it] = Outcome(null, "the project at $dir did not load: ${e.message?.lineSequence()?.firstOrNull()}") }
            }
        }
        return files.associateWith { outcomes.getValue(it) }
    }

    /** The directory of the nearest project.kira above [file], or null. */
    fun projectDirOf(file: File): Path? {
        var dir: Path? = file.absoluteFile.toPath().normalize().parent
        while (dir != null) {
            if (ProjectFile.find(dir) != null) return dir
            dir = dir.parent
        }
        return null
    }

    /** [body] given the project at [root] and its sources (the stdlib it names, then its own), with the stdlib set as the CLI sets it. */
    fun <T> withProject(root: Path, body: (Project, List<Src>) -> T): T {
        val project: Project = ProjectFile.load(ProjectFile.find(root)!!)
        val stdlib = ProjectSources.dependencies(project, root).distinct().sorted()
        val workspace = ProjectSources.workspace(project, root)
        val names = ModuleNames.assign(project, root, stdlib, workspace)
        val saved = Public.Builtin.intrinsicalStandardLibrarySources
        Public.Builtin.intrinsicalStandardLibrarySources = stdlib.toTypedArray()
        try {
            val srcs = stdlib.map { Src(File(it).canonicalPath, File(it).readText(), names[File(it).canonicalPath], false) } +
                workspace.map { Src(File(it).canonicalPath, File(it).readText(), names[File(it).canonicalPath], true) }
            return body(project, srcs)
        } finally {
            Public.Builtin.intrinsicalStandardLibrarySources = saved
        }
    }

    /** [files] (some of [srcs], by canonical path) rewritten: first to find what the typer is asked, then with its answers. */
    private fun rewriteSources(srcs: List<Src>, files: List<File>, bootstrap: Boolean = false): Map<File, Outcome> {
        val out = LinkedHashMap<File, Outcome>()
        val needs = LinkedHashMap<String, List<ForFix.Need>>()
        val refused = HashMap<File, String>()
        for (f in files) {
            try {
                val list = ArrayList<ForFix.Need>()
                ForFix.rewrite(f.readText(), f.path, null, list)
                if (list.isNotEmpty()) needs[f.canonicalPath] = list
            } catch (e: Throwable) {
                refused[f] = "refused (${e.message?.lineSequence()?.firstOrNull()})"
            }
        }
        val answers = if (needs.isEmpty()) emptyMap() else answers(srcs, needs, bootstrap)
        for (f in files) {
            val why = refused[f]
            out[f] = if (why != null) Outcome(null, why) else Outcome(ForFix.rewrite(f.readText(), f.path, answers[f.canonicalPath] ?: emptyMap()), null)
        }
        return out
    }

    /** One module's [text] rewritten with the typer's help, against the stdlib under `./kira`: for tests. */
    fun rewriteSnippet(text: String, uri: String = "test:main"): ForFix.Result {
        val (pkg, dotted) = uri.split(":", limit = 2)
        val path = "$pkg/${dotted.replace('.', '/')}.kira"
        val needs = ArrayList<ForFix.Need>()
        ForFix.rewrite(text, path, null, needs)
        if (needs.isEmpty()) return ForFix.rewrite(text, path)
        val srcs = listOf(Src(path, text, uri, true))
        return ForFix.rewrite(text, path, answers(srcs, mapOf(path to needs), bootstrap = true)[path] ?: emptyMap())
    }
}
