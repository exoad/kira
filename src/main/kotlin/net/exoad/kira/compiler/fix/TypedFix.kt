package net.exoad.kira.compiler.fix

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.ModuleNames
import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.ProjectFile
import net.exoad.kira.compiler.ProjectSources
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import java.io.File
import java.nio.file.Path

/**
 * The fixers that ask the typer: `kira --fix-with` ([WithFix]) asks which receivers a `with` can hold, and `kira --fix-counters`
 * ([CounterFix]) what a loop's bound reads and its body writes. The project above each file is typed with the stdlib it names.
 */
object TypedFix {
    /** A source the typer sees: [file] (its key), its [text], its [module] name, and whether it is the project's own. */
    class Src(val file: String, val text: String, val module: String?, val workspace: Boolean)

    /**
     * [srcs] parsed (a workspace file leniently: `for` and the old declaration form read), a file in [texts] by that text, and
     * typed in [mode]. A file that does not parse is left out; a module that imports it types without it.
     */
    fun typeSources(srcs: List<Src>, texts: Map<String, String> = emptyMap(), bootstrap: Boolean = false, options: TyperOptions = TyperOptions(), mode: TyperMode = TyperMode.LENIENT): Pair<CompilationUnit, TypedProgram> {
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
        return unit to KiraTyper.run(unit, mode, options)
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
}
